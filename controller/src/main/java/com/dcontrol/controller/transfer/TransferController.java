package com.dcontrol.controller.transfer;

import com.dcontrol.controller.common.context.Actor;
import com.dcontrol.controller.common.context.ActorContext;
import com.dcontrol.controller.transfer.FileTransfer.Direction;
import com.dcontrol.controller.transfer.TransferDtos.CreateRequest;
import com.dcontrol.controller.transfer.TransferDtos.DownloadTicketResponse;
import com.dcontrol.controller.transfer.TransferDtos.TransferView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Browser side of file transfers (docs/protocol/transfers-v1.md). */
@RestController
public class TransferController {

    private static final Logger log = LoggerFactory.getLogger(TransferController.class);

    private final TransferService transfers;
    private final TransferSpool spool;

    public TransferController(TransferService transfers, TransferSpool spool) {
        this.transfers = transfers;
        this.spool = spool;
    }

    @PostMapping("/api/devices/{deviceId}/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferView create(@PathVariable UUID deviceId, @Valid @RequestBody CreateRequest request) {
        Actor user = ActorContext.current();
        return request.direction() == Direction.TO_DEVICE
                ? transfers.createToDevice(user, deviceId, request.targetDir(), request.fileName(), request.size())
                : transfers.createFromDevice(user, deviceId, request.path());
    }

    @GetMapping("/api/devices/{deviceId}/transfers")
    public List<TransferView> list(@PathVariable UUID deviceId) {
        return transfers.listForDevice(ActorContext.current(), deviceId);
    }

    @GetMapping("/api/transfers/{id}")
    public TransferView get(@PathVariable UUID id) {
        return transfers.get(ActorContext.current(), id);
    }

    @PutMapping("/api/transfers/{id}/content")
    public TransferView upload(@PathVariable UUID id, @RequestHeader(HttpHeaders.CONTENT_RANGE) String contentRange,
                               HttpServletRequest request) throws IOException {
        return transfers.appendChunk(ActorContext.current(), null, Direction.TO_DEVICE, id, contentRange,
                request.getContentLengthLong(), request.getInputStream());
    }

    @PostMapping("/api/transfers/{id}/download-ticket")
    public DownloadTicketResponse downloadTicket(@PathVariable UUID id) {
        return transfers.issueTicket(ActorContext.current(), id);
    }

    /** Native browser download; authorized by the single-use ticket instead of the bearer token. */
    @GetMapping("/api/transfers/{id}/content")
    public void download(@PathVariable UUID id, @RequestParam String ticket,
                         @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
                         HttpServletResponse response) {
        TransferService.Download download = transfers.openBrowserDownload(ticket, id);
        try {
            if (ContentResponses.stream(spool, download.transfer(), range, true, response)) {
                transfers.markDownloaded(download);
            }
        } catch (IOException e) {
            log.debug("Download of transfer {} aborted: {}", id, e.getMessage());
        }
    }

    @PostMapping("/api/transfers/{id}/cancel")
    public TransferView cancel(@PathVariable UUID id) {
        return transfers.cancel(ActorContext.current(), id);
    }
}
