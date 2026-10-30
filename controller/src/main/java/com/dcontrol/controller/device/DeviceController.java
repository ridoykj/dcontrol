package com.dcontrol.controller.device;

import com.dcontrol.controller.device.DeviceDtos.DeviceResponse;
import com.dcontrol.controller.device.DeviceDtos.RenameRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping
    public List<DeviceResponse> list(@RequestParam(defaultValue = "false") boolean deleted) {
        return deviceService.listResponses(deleted);
    }

    @GetMapping("/{id}")
    public DeviceResponse get(@PathVariable UUID id) {
        return deviceService.getResponse(id);
    }

    @PatchMapping("/{id}")
    public DeviceResponse rename(@PathVariable UUID id, @Valid @RequestBody RenameRequest request) {
        return deviceService.rename(id, request.name());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        deviceService.delete(id);
    }

    @PostMapping("/{id}/restore")
    public DeviceResponse restore(@PathVariable UUID id) {
        return deviceService.restoreResponse(id);
    }
}
