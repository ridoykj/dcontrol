package com.dcontrol.controller.transfer;

import com.dcontrol.controller.common.fsm.StatefulEntity;
import com.dcontrol.controller.common.tenancy.AccountScopedEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "t_file_transfer")
@AttributeOverride(name = "id", column = @Column(name = "id_file_transfer_key", nullable = false, updatable = false))
@AttributeOverride(name = "version", column = @Column(name = "id_file_transfer_ver", nullable = false))
public class FileTransfer extends AccountScopedEntity implements StatefulEntity<TransferState, TransferAction> {

    public enum Direction { TO_DEVICE, FROM_DEVICE }

    @Convert(converter = TransferState.Converter.class)
    @Column(name = "id_state_key", nullable = false)
    private TransferState state;

    @Convert(converter = TransferAction.Converter.class)
    @Column(name = "id_action_key", nullable = false)
    private TransferAction lastAction;

    @Column(name = "id_device_key", nullable = false, updatable = false)
    private UUID deviceKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "tx_direction", nullable = false, updatable = false, length = 16)
    private Direction direction;

    @Column(name = "tx_device_path", nullable = false, updatable = false, length = 4096)
    private String devicePath;

    @Column(name = "tx_file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "ct_size_bytes")
    private Long size;

    @Column(name = "ct_received_bytes", nullable = false)
    private long receivedBytes;

    @Column(name = "tx_sha256", length = 64)
    private String sha256;

    @Column(name = "tx_error_code", length = 32)
    private String errorCode;

    @Column(name = "dtt_expires", nullable = false)
    private Instant expiresAt;

    @Column(name = "dtt_finished")
    private Instant finishedAt;

    protected FileTransfer() {
    }

    public FileTransfer(UUID deviceKey, Direction direction, String devicePath, String fileName, Long size,
                        Instant expiresAt) {
        this.deviceKey = deviceKey;
        this.direction = direction;
        this.devicePath = devicePath;
        this.fileName = fileName;
        this.size = size;
        this.expiresAt = expiresAt;
    }

    public void recordChunk(long bytes, Instant expiresAt) {
        this.receivedBytes += bytes;
        this.expiresAt = expiresAt;
    }

    public void staged(String sha256, Instant expiresAt) {
        this.sha256 = sha256;
        this.expiresAt = expiresAt;
    }

    public void finished(Instant at, String errorCode) {
        this.finishedAt = at;
        this.errorCode = errorCode;
    }

    public void reportedSize(long size, String fileName) {
        this.size = size;
        this.fileName = fileName;
    }

    public boolean isComplete() {
        return size != null && receivedBytes == size;
    }

    @Override
    public TransferState getState() {
        return state;
    }

    @Override
    public TransferAction getLastAction() {
        return lastAction;
    }

    @Override
    public void applyTransition(TransferState state, TransferAction action) {
        this.state = state;
        this.lastAction = action;
    }

    public UUID getDeviceKey() {
        return deviceKey;
    }

    public Direction getDirection() {
        return direction;
    }

    public String getDevicePath() {
        return devicePath;
    }

    public String getFileName() {
        return fileName;
    }

    public Long getSize() {
        return size;
    }

    public long getReceivedBytes() {
        return receivedBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
