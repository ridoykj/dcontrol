package com.dcontrol.controller.spa;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves index.html for client-side routes so deep links and reloads work. {@code assets} (the Vite bundle) is
 * excluded because handler mappings win over static resources: forwarding it would answer every script with HTML.
 */
@Controller
public class SpaForwardingController {

    @GetMapping({"/{path:^(?!api$|ws$|actuator$|assets$)[^.]*}",
            "/{path:^(?!api$|ws$|actuator$|assets$)[^.]*}/**"})
    public String forward() {
        return "forward:/index.html";
    }
}
