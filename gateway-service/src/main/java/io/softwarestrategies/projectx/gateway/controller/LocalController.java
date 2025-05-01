package io.softwarestrategies.projectx.gateway.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LocalController {

    /**
     * This endpoint is resolved directly by this application using standard Spring MVC.
     * It is NOT processed by the gateway routing functions.
     */
    @GetMapping("/api/v1/get-local")
    public String localEndpoint() {
        return "Hello from the local endpoint in the Gateway MVC application!";
    }

    @GetMapping("/api/v1/telephony/xentityx/get-external")
    public String localEndpointEntityX() {
        return "Hello from the local entity X endpoint in the Gateway MVC application!";
    }
}
