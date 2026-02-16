package com.nicolaswinsten.pictionary.web;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simple REST controller for health-check / smoke-test purposes.
 *
 * <p>Not part of the game logic — just a quick way to verify the server is running.
 */
@RestController
public class HelloController {
    private static final Logger LOGGER = LoggerFactory.getLogger(HelloController.class);

    /** Returns {@code {"message": "Hello from Spring Boot"}}. */
    @GetMapping("/api/hello")
    public Map<String, String> hello() {
        return Map.of("message", "Hello from Spring Boot");
    }

}
