package com.dcontrol.controller.pairing;

import com.dcontrol.controller.pairing.PairingDtos.PairingResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Web side of pairing: issue, poll and cancel tokens. */
@RestController
@RequestMapping("/api/pairings")
public class PairingController {

    private final PairingService pairingService;

    public PairingController(PairingService pairingService) {
        this.pairingService = pairingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PairingResponse create() {
        return pairingService.create();
    }

    @GetMapping("/{id}")
    public PairingResponse get(@PathVariable UUID id) {
        return pairingService.get(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID id) {
        pairingService.cancel(id);
    }
}
