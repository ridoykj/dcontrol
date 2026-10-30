package com.dcontrol.controller.transfer;

import com.dcontrol.controller.device.DeviceApiAuth;
import com.dcontrol.controller.device.DeviceApiAuth.DeviceCaller;
import com.dcontrol.controller.transfer.FileTransfer.Direction;
import com.dcontrol.controller.transfer.TransferDtos.TransferView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Phone side of file transfers; a phone only reaches transfers of its own device. */
@RestController
@RequestMapping("/api/device-api/transfers")
public class DeviceTransferController {

    private static final Logger log = LoggerFactory.getLogger(DeviceTransferController.class);

    private final TransferService transfers;
    private final TransferSpool spool;
    private final DeviceApiAuth auth;

    public DeviceTransferController(TransferService transfers, TransferSpool spool, DeviceApiAuth auth) {
        this.transfers = transfers;
        this.spool = spool;
        this.auth = auth;
    }

    @GetMapping("/{id}")
    public TransferView get(@PathVariable UUID id, HttpServletRequest request) {
        DeviceCaller device = auth.require(request);
        return TransferView.of(transfers.getForDevice(device.actor(), device.deviceKey(), id));
    }

    @PutMapping("/{id}/content")
    public TransferView upload(@PathVariable UUID id, @RequestHeader(HttpHeaders.CONTENT_RANGE) String contentRange,
                               HttpServletRequest request) throws IOException {
        DeviceCaller device = auth.require(request);
        return transfers.appendChunk(device.actor(), device.deviceKey(), Direction.FROM_DEVICE, id, contentRange,
                request.getContentLengthLong(), request.getInputStream());
    }

    @GetMapping("/{id}/content")
    public void download(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
                         HttpServletRequest request, HttpServletResponse response) {
        DeviceCaller device = auth.require(request);
        FileTransfer transfer = transfers.openDeviceDownload(device.actor(), device.deviceKey(), id);
        try {
            ContentResponses.stream(spool, transfer, range, false, response);
        } catch (IOException e) {
            log.debug("Device download of transfer {} aborted: {}", id, e.getMessage());
        }
    }
}
