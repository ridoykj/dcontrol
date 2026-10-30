package com.dcontrol.controller.pairing;

import com.dcontrol.controller.pairing.PairingDtos.ClaimRequest;
import com.dcontrol.controller.pairing.PairingDtos.ClaimResponse;
import com.dcontrol.controller.pairing.PairingDtos.PreviewResponse;
import com.dcontrol.controller.pairing.PairingDtos.TokenRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Phone side of pairing. Authenticated by the pairing token itself. */
@RestController
@RequestMapping("/api/device-api/pairings")
public class DevicePairingController {

    private final PairingService pairingService;

    public DevicePairingController(PairingService pairingService) {
        this.pairingService = pairingService;
    }

    @PostMapping("/preview")
    public PreviewResponse preview(@Valid @RequestBody TokenRequest request, HttpServletRequest http) {
        return pairingService.preview(request.token(), http.getRemoteAddr());
    }

    @PostMapping("/claim")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimResponse claim(@Valid @RequestBody ClaimRequest request, HttpServletRequest http) {
        return pairingService.claim(request, http.getRemoteAddr());
    }
}
