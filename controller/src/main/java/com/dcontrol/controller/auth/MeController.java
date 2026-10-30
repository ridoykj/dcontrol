package com.dcontrol.controller.auth;

import com.dcontrol.controller.auth.AuthDtos.UserResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

    private final AuthService authService;

    public MeController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/api/me")
    public UserResponse me() {
        return authService.me();
    }
}
