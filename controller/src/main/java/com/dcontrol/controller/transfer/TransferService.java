package com.dcontrol.controller.transfer;

import com.dcontrol.controller.common.config.AppProperties;
import com.dcontrol.controller.common.context.Actor;
import com.dcontrol.controller.common.context.EventChannel;
import com.dcontrol.controller.common.error.ApiException;
import com.dcontrol.controller.common.error.ErrorCode;
import com.dcontrol.controller.common.persistence.ActorTx;
import com.dcontrol.controller.common.validation.FileName;
import com.dcontrol.controller.common.ws.DeviceSessionRegistry;
import com.dcontrol.controller.common.ws.Envelope;
import com.dcontrol.controller.common.ws.MessageCodec;
import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.common.ws.ProtocolException;
import com.dcontrol.controller.common.ws.RelayException;
import com.dcontrol.controller.common.ws.WebSessionRegistry;
import com.dcontrol.controller.device.DeviceCommandRelay;
import com.dcontrol.controller.device.DeviceConnectionListener;
import com.dcontrol.controller.device.DeviceEventHandler;
import com.dcontrol.controller.device.DeviceRepository;
import com.dcontrol.controller.device.DeviceState;
import com.dcontrol.controller.transfer.ByteRanges.ContentRange;
import com.dcontrol.controller.transfer.FileTransfer.Direction;
import com.dcontrol.controller.transfer.TransferDtos.DownloadTicketResponse;
import com.dcontrol.controller.transfer.TransferDtos.TransferView;
import com.dcontrol.controller.transfer.TransferProtocol.Cancel;
import com.dcontrol.controller.transfer.TransferProtocol.Finished;
import com.dcontrol.controller.transfer.TransferProtocol.Progress;
import com.dcontrol.controller.transfer.TransferProtocol.ProgressForWeb;
import com.dcontrol.controller.transfer.TransferProtocol.ReceiveCommand;
import com.dcontrol.controller.transfer.TransferProtocol.SendCommand;
import com.dcontrol.controller.transfer.TransferProtocol.SendResult;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * File transfers between browsers and phones through the disk spool (docs/protocol/transfers-v1.md).
 * Database work runs in short {@link ActorTx} transactions so no transaction stays open while bytes move;
 * socket messages are sent after the transaction that caused them has committed.
 */
@Service
public class TransferService implements DeviceConnectionListener {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final long PROGRESS_INTERVAL_MS = 1_000;
    private static final Set<ErrorCode> RETRY_LATER = EnumSet.of(ErrorCode.DEVICE_OFFLINE,
            ErrorCode.DEVICE_DISCONNECTED, ErrorCode.TIMEOUT);

    /** Result of a successful ticket check, ready to stream. */
    public record Download(FileTransfer transfer, Actor actor) {
    }

    private final FileTransferRepository transfers;
    private final DeviceRepository devices;
    private final DeviceSessionRegistry deviceSessions;
    private final TransferSpool spool;
    private final DownloadTicketService tickets;
    private final DeviceCommandRelay relay;
    private final WebSessionRegistry web;
    private final MessageCodec codec;
    private final ActorTx actorTx;
    private final Clock clock;
    private final AppProperties.Transfers config;
    private final Map<UUID, ReentrantLock> chunkLocks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastProgress = new ConcurrentHashMap<>();

    public TransferService(FileTransferRepository transfers, DeviceRepository devices,
                           DeviceSessionRegistry deviceSessions, TransferSpool spool, DownloadTicketService tickets,
                           DeviceCommandRelay relay, WebSessionRegistry web, MessageCodec codec, ActorTx actorTx,
                           Clock clock, AppProperties properties) {
        this.transfers = transfers;
        this.devices = devices;
        this.deviceSessions = deviceSessions;
        this.spool = spool;
        this.tickets = tickets;
        this.relay = relay;
        this.web = web;
        this.codec = codec;
        this.actorTx = actorTx;
        this.clock = clock;
        this.config = properties.transfers();
    }

    // ---- browser API ----------------------------------------------------------------------------------

    public TransferView createToDevice(Actor user, UUID deviceKey, String targetDir, String fileName, long size) {
        FileTransfer created = actorTx.write(user, () -> {
            requireDevice(deviceKey);
            checkLimits(deviceKey, size);
            FileTransfer transfer = new FileTransfer(deviceKey, Direction.TO_DEVICE, targetDir, fileName, size,
                    now().plus(config.stallTimeout()));
            TransferFsm.MACHINE.move(transfer, TransferAction.CREATE);
            FileTransfer saved = transfers.saveAndFlush(transfer);
            spool.create(saved.getId());
            if (size == 0) {
                stage(saved, spool.sha256(saved.getId()));
                return transfers.saveAndFlush(saved);
            }
            return saved;
        });
        published(created);
        return TransferView.of(created);
    }

    /** Asks the phone for the file and answers once it reported name and size; the phone then uploads. */
    public TransferView createFromDevice(Actor user, UUID deviceKey, String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        if (!fileName.matches(FileName.REGEX)) {
            throw ApiException.validation("path must name a file");
        }
        FileTransfer created = actorTx.write(user, () -> {
            requireDevice(deviceKey);
            if (deviceSessions.get(deviceKey).isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCode.DEVICE_OFFLINE, "Device is offline");
            }
            checkLimits(deviceKey, 0);
            FileTransfer transfer = new FileTransfer(deviceKey, Direction.FROM_DEVICE, path, fileName, null,
                    now().plus(config.stallTimeout()));
            TransferFsm.MACHINE.move(transfer, TransferAction.CREATE);
            FileTransfer saved = transfers.saveAndFlush(transfer);
            spool.create(saved.getId());
            return saved;
        });
        UUID id = created.getId();
        broadcastStatus(created);

        SendResult reported;
        try {
            Envelope answer = relay.send(deviceKey, MessageType.TRANSFER_SEND, new SendCommand(id, path)).join();
            reported = codec.payload(answer, SendResult.class);
        } catch (CompletionException | RelayException e) {
            RelayException error = DeviceCommandRelay.asRelayException(e);
            fail(user, id, error.code());
            throw error.toApiException();
        } catch (ProtocolException e) {
            fail(user, id, ErrorCode.INTERNAL);
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.INTERNAL, "Invalid device answer");
        }

        ApiException[] rejected = new ApiException[1];
        FileTransfer updated = actorTx.write(user, () -> {
            FileTransfer transfer = find(id, null, Direction.FROM_DEVICE);
            if (!transfer.getState().isOpen()) {
                return transfer;
            }
            if (transfer.getSize() != null && !transfer.getSize().equals(reported.size())) {
                rejected[0] = new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.INTERNAL,
                        "Device reported a size that differs from its upload");
            } else if (transfer.getSize() == null) {
                rejected[0] = sizeProblem(reported.size());
            }
            if (rejected[0] != null) {
                failInTx(transfer, rejected[0].code());
                return transfers.saveAndFlush(transfer);
            }
            transfer.reportedSize(reported.size(), reported.fileName());
            if (transfer.isComplete() && transfer.getState() == TransferState.RECEIVING) {
                stage(transfer, spool.sha256(id));
            }
            return transfers.saveAndFlush(transfer);
        });
        broadcastStatus(updated);
        if (rejected[0] != null) {
            relay.notify(deviceKey, MessageType.TRANSFER_CANCEL, new Cancel(id));
            throw rejected[0];
        }
        return TransferView.of(updated);
    }

    public TransferView get(Actor user, UUID id) {
        return actorTx.read(user, () -> TransferView.of(find(id, null, null)));
    }

    public List<TransferView> listForDevice(Actor user, UUID deviceKey) {
        return actorTx.read(user, () -> {
            requireDevice(deviceKey);
            return transfers.findRecentByDevice(deviceKey, now().minus(config.rowRetention())).stream()
                    .map(TransferView::of)
                    .toList();
        });
    }

    public TransferView cancel(Actor user, UUID id) {
        FileTransfer cancelled = actorTx.write(user, () -> {
            FileTransfer transfer = find(id, null, null);
            if (!transfer.getState().isOpen()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE_TRANSITION,
                        "Transfer is already " + transfer.getState());
            }
            TransferFsm.MACHINE.move(transfer, TransferAction.CANCEL);
            transfer.finished(now(), null);
            return transfers.saveAndFlush(transfer);
        });
        relay.notify(cancelled.getDeviceKey(), MessageType.TRANSFER_CANCEL, new Cancel(id));
        broadcastStatus(cancelled);
        return TransferView.of(cancelled);
    }

    public DownloadTicketResponse issueTicket(Actor user, UUID id) {
        actorTx.read(user, () -> requireDownloadable(find(id, null, Direction.FROM_DEVICE)));
        DownloadTicketService.Ticket ticket = tickets.issue(id, user.accountKey(), user.subjectKey());
        return new DownloadTicketResponse(ticket.value(), "/api/transfers/" + id + "/content?ticket=" + ticket.value(),
                ticket.expiresAt());
    }

    public Download openBrowserDownload(String ticketValue, UUID id) {
        DownloadTicketService.Ticket ticket = tickets.consume(ticketValue, id).orElseThrow(() ->
                ApiException.unauthorized(ErrorCode.TOKEN_INVALID, "Download link is invalid or expired"));
        Actor actor = Actor.user(ticket.accountKey(), ticket.userKey(), EventChannel.HTTP);
        FileTransfer transfer = actorTx.read(actor, () -> requireDownloadable(find(id, null, Direction.FROM_DEVICE)));
        return new Download(transfer, actor);
    }

    /** Called after the browser received the whole file. */
    public void markDownloaded(Download download) {
        FileTransfer delivered = actorTx.write(download.actor(), () -> {
            FileTransfer transfer = find(download.transfer().getId(), null, Direction.FROM_DEVICE);
            if (transfer.getState() != TransferState.READY) {
                return null;
            }
            TransferFsm.MACHINE.move(transfer, TransferAction.DELIVER);
            transfer.finished(now(), null);
            return transfers.saveAndFlush(transfer);
        });
        if (delivered != null) {
            broadcastStatus(delivered);
        }
    }

    // ---- chunks (both directions) ---------------------------------------------------------------------

    /**
     * Appends one chunk. Browsers upload {@code TO_DEVICE} transfers ({@code deviceKey} null); phones upload
     * {@code FROM_DEVICE} transfers of their own device.
     */
    public TransferView appendChunk(Actor actor, UUID deviceKey, Direction direction, UUID id, String contentRange,
                                    long contentLength, InputStream body) {
        ContentRange range = ByteRanges.contentRange(contentRange).orElseThrow(() ->
                ApiException.validation("Content-Range must be 'bytes <start>-<end>/<size>'"));
        if (range.length() > config.maxChunkSize().toBytes()) {
            throw tooLarge("Chunk exceeds " + config.maxChunkSize().toMegabytes() + " MiB");
        }
        if (contentLength >= 0 && contentLength != range.length()) {
            throw ApiException.validation("Content-Length does not match Content-Range");
        }
        ReentrantLock lock = chunkLocks.computeIfAbsent(id, key -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT,
                    "Another chunk of this transfer is being uploaded");
        }
        try {
            FileTransfer current = actorTx.read(actor, () -> {
                FileTransfer transfer = find(id, deviceKey, direction);
                requireReceiving(transfer);
                if (transfer.getSize() == null) {
                    ApiException problem = sizeProblem(range.total());
                    if (problem != null) {
                        throw problem;
                    }
                }
                return transfer;
            });
            long size = current.getSize() != null ? current.getSize() : range.total();
            if (range.total() != size) {
                throw ApiException.validation("Content-Range size " + range.total() + " does not match the transfer size "
                        + size);
            }
            if (range.start() != current.getReceivedBytes()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT,
                        "Chunk must start at " + current.getReceivedBytes(),
                        Map.of("receivedBytes", current.getReceivedBytes()));
            }
            long written;
            try {
                written = spool.write(id, range.start(), body, range.length());
            } catch (IOException e) {
                throw ApiException.validation("Upload interrupted");
            }
            if (written != range.length()) {
                throw ApiException.validation("Body length does not match Content-Range");
            }
            boolean completes = range.end() == size - 1;
            String sha256 = completes ? spool.sha256(id) : null;
            FileTransfer updated = actorTx.write(actor, () -> {
                FileTransfer transfer = find(id, deviceKey, direction);
                requireReceiving(transfer);
                if (transfer.getSize() == null) {
                    transfer.reportedSize(size, transfer.getFileName());
                }
                transfer.recordChunk(written, now().plus(config.stallTimeout()));
                if (completes) {
                    stage(transfer, sha256);
                }
                return transfers.saveAndFlush(transfer);
            });
            if (completes) {
                published(updated);
            } else {
                progress(updated, updated.getReceivedBytes());
            }
            return TransferView.of(updated);
        } finally {
            lock.unlock();
            chunkLocks.remove(id, lock);
        }
    }

    // ---- phone API ------------------------------------------------------------------------------------

    public FileTransfer getForDevice(Actor device, UUID deviceKey, UUID id) {
        return actorTx.read(device, () -> find(id, deviceKey, null));
    }

    /** The staged file a phone downloads ({@code TO_DEVICE}, READY). */
    public FileTransfer openDeviceDownload(Actor device, UUID deviceKey, UUID id) {
        FileTransfer transfer = actorTx.read(device, () -> find(id, deviceKey, Direction.TO_DEVICE));
        if (transfer.getState() != TransferState.READY || !spool.exists(id)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE_TRANSITION,
                    "Transfer is " + transfer.getState());
        }
        return transfer;
    }

    public void onProgress(DeviceEventHandler.Source source, Progress progress) {
        actorTx.read(deviceActor(source), () -> transfers.findByIdAndActive(progress.transferId(), true))
                .filter(t -> t.getDeviceKey().equals(source.deviceKey()))
                .filter(t -> t.getDirection() == Direction.TO_DEVICE && t.getState() == TransferState.READY)
                .ifPresent(t -> progress(t, Math.min(progress.bytes(), t.getSize())));
    }

    public void onFinished(DeviceEventHandler.Source source, Finished finished) {
        FileTransfer changed = actorTx.write(deviceActor(source), () -> {
            FileTransfer transfer = transfers.findByIdAndActive(finished.transferId(), true)
                    .filter(t -> t.getDeviceKey().equals(source.deviceKey()))
                    .orElse(null);
            if (transfer == null || !transfer.getState().isOpen()) {
                return null;
            }
            if (Boolean.TRUE.equals(finished.ok())) {
                if (transfer.getDirection() != Direction.TO_DEVICE || transfer.getState() != TransferState.READY) {
                    return null;
                }
                TransferFsm.MACHINE.move(transfer, TransferAction.DELIVER);
                transfer.finished(now(), null);
            } else {
                failInTx(transfer, DeviceCommandRelay.deviceErrorCode(finished.errorCode()));
            }
            return transfers.saveAndFlush(transfer);
        });
        if (changed != null) {
            lastProgress.remove(changed.getId());
            broadcastStatus(changed);
        }
    }

    /** Re-offers unfinished transfers to a phone that (re)connected; phones resume them by id. */
    @Override
    public void onDeviceReady(DeviceEventHandler.Source source) {
        Actor device = deviceActor(source);
        List<FileTransfer> open = actorTx.read(device,
                () -> transfers.findByDeviceInStates(source.deviceKey(), TransferState.OPEN));
        for (FileTransfer transfer : open) {
            if (transfer.getDirection() == Direction.TO_DEVICE && transfer.getState() == TransferState.READY) {
                deliverToDevice(transfer);
            } else if (transfer.getDirection() == Direction.FROM_DEVICE
                    && transfer.getState() == TransferState.RECEIVING) {
                relay.send(source.deviceKey(), MessageType.TRANSFER_SEND,
                                new SendCommand(transfer.getId(), transfer.getDevicePath()))
                        .whenComplete((answer, error) -> onDeviceRejected(transfer, error));
            }
        }
    }

    // ---- cleanup --------------------------------------------------------------------------------------

    /** Expires stalled transfers, deletes old spool files and old rows; returns the number expired. */
    public int cleanUp() {
        Actor system = Actor.system("transfer-cleanup", EventChannel.JOB);
        Instant now = now();
        List<FileTransfer> expired = actorTx.write(system, () -> {
            List<FileTransfer> found = transfers.findExpired(TransferState.OPEN, now);
            found.forEach(transfer -> {
                TransferFsm.MACHINE.move(transfer, TransferAction.EXPIRE);
                transfer.finished(now, null);
            });
            return transfers.saveAllAndFlush(found);
        });
        expired.forEach(transfer -> {
            relay.notify(transfer.getDeviceKey(), MessageType.TRANSFER_CANCEL, new Cancel(transfer.getId()));
            broadcastStatus(transfer);
        });
        actorTx.read(system, () -> transfers.findIdsFinishedBefore(TransferState.FINAL,
                now.minus(config.spoolRetention()))).forEach(spool::delete);
        int deleted = actorTx.write(system, () -> transfers.deleteFinishedBefore(TransferState.FINAL,
                now.minus(config.rowRetention())));
        if (deleted > 0) {
            log.debug("Deleted {} finished transfers", deleted);
        }
        return expired.size();
    }

    // ---- internals ------------------------------------------------------------------------------------

    private FileTransfer find(UUID id, UUID deviceKey, Direction direction) {
        return transfers.findByIdAndActive(id, true)
                .filter(t -> deviceKey == null || t.getDeviceKey().equals(deviceKey))
                .filter(t -> direction == null || t.getDirection() == direction)
                .orElseThrow(() -> ApiException.notFound("Transfer"));
    }

    private void requireDevice(UUID deviceKey) {
        devices.findActiveInState(deviceKey, DeviceState.ACTIVE).orElseThrow(() -> ApiException.notFound("Device"));
    }

    private static void requireReceiving(FileTransfer transfer) {
        if (transfer.getState() != TransferState.RECEIVING) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE_TRANSITION,
                    "Transfer is " + transfer.getState());
        }
    }

    private FileTransfer requireDownloadable(FileTransfer transfer) {
        boolean available = transfer.getState() == TransferState.READY
                || transfer.getState() == TransferState.DELIVERED;
        if (!available || !spool.exists(transfer.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE_TRANSITION,
                    "Transfer is not available for download");
        }
        return transfer;
    }

    /** Runs inside a transaction of the current account. */
    private void checkLimits(UUID deviceKey, long size) {
        ApiException problem = sizeProblem(size);
        if (problem != null) {
            throw problem;
        }
        if (transfers.countByDeviceInStates(deviceKey, TransferState.OPEN) >= config.maxActivePerDevice()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT,
                    "Too many active transfers for this device");
        }
    }

    /** File size and account quota check; runs inside a transaction of the current account. */
    private ApiException sizeProblem(long size) {
        if (size > config.maxFileSize().toBytes()) {
            return tooLarge("File exceeds the maximum transfer size of " + config.maxFileSize().toGigabytes() + " GiB");
        }
        if (transfers.sumSizesInStates(TransferState.OPEN) + size > config.accountQuota().toBytes()) {
            return tooLarge("Transfer space of this account is used up; wait for running transfers to finish");
        }
        return null;
    }

    private void stage(FileTransfer transfer, String sha256) {
        transfer.staged(sha256, now().plus(config.readyTtl()));
        TransferFsm.MACHINE.move(transfer, TransferAction.STAGE);
    }

    private void failInTx(FileTransfer transfer, ErrorCode code) {
        TransferFsm.MACHINE.move(transfer, TransferAction.FAIL);
        transfer.finished(now(), code.name());
    }

    private void fail(Actor actor, UUID id, ErrorCode code) {
        FileTransfer failed = actorTx.write(actor, () -> transfers.findByIdAndActive(id, true)
                .filter(t -> t.getState().isOpen())
                .map(t -> {
                    failInTx(t, code);
                    return transfers.saveAndFlush(t);
                })
                .orElse(null));
        if (failed != null) {
            relay.notify(failed.getDeviceKey(), MessageType.TRANSFER_CANCEL, new Cancel(id));
            broadcastStatus(failed);
        }
    }

    /** Tells the browser about a new or changed transfer and hands staged uploads to the phone. */
    private void published(FileTransfer transfer) {
        broadcastStatus(transfer);
        if (transfer.getDirection() == Direction.TO_DEVICE && transfer.getState() == TransferState.READY) {
            deliverToDevice(transfer);
        }
    }

    private void deliverToDevice(FileTransfer transfer) {
        if (deviceSessions.get(transfer.getDeviceKey()).isEmpty()) {
            return;
        }
        relay.send(transfer.getDeviceKey(), MessageType.TRANSFER_RECEIVE, new ReceiveCommand(transfer.getId(),
                        transfer.getDevicePath(), transfer.getFileName(), transfer.getSize(), transfer.getSha256()))
                .whenComplete((answer, error) -> onDeviceRejected(transfer, error));
    }

    /** A phone refused a transfer command: fail it, unless the phone was just unreachable. */
    private void onDeviceRejected(FileTransfer transfer, Throwable error) {
        if (error == null) {
            return;
        }
        RelayException relayError = DeviceCommandRelay.asRelayException(error);
        if (RETRY_LATER.contains(relayError.code())) {
            log.debug("Transfer {} waits for device {}: {}", transfer.getId(), transfer.getDeviceKey(),
                    relayError.code());
            return;
        }
        fail(Actor.device(transfer.getAccountKey(), transfer.getDeviceKey()), transfer.getId(), relayError.code());
    }

    private void progress(FileTransfer transfer, long bytes) {
        long nowMillis = clock.millis();
        Long previous = lastProgress.get(transfer.getId());
        if (previous != null && nowMillis - previous < PROGRESS_INTERVAL_MS) {
            return;
        }
        lastProgress.put(transfer.getId(), nowMillis);
        web.broadcast(transfer.getAccountKey(), codec.message(MessageType.TRANSFER_PROGRESS, null,
                new ProgressForWeb(transfer.getDeviceKey(), transfer.getId(), bytes, transfer.getSize())));
    }

    /** A state change also restarts progress throttling (READY switches from upload to delivery progress). */
    private void broadcastStatus(FileTransfer transfer) {
        lastProgress.remove(transfer.getId());
        web.broadcast(transfer.getAccountKey(),
                codec.message(MessageType.TRANSFER_STATUS, null, TransferView.of(transfer)));
    }

    private static Actor deviceActor(DeviceEventHandler.Source source) {
        return Actor.device(source.accountKey(), source.deviceKey());
    }

    private static ApiException tooLarge(String detail) {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE, detail);
    }

    private Instant now() {
        return clock.instant();
    }
}
