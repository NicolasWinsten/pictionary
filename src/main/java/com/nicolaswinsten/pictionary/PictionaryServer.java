package com.nicolaswinsten.pictionary;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Pictionary multiplayer drawing game.
 *
 * <p>This Spring Boot application serves a single-page Alpine.js frontend and
 * handles all real-time game communication over STOMP/WebSocket.
 * There is no database — all game state lives in memory for the lifetime of the process.
 *
 * @see com.nicolaswinsten.pictionary.web.WebSocketConfig  WebSocket/STOMP wiring
 * @see com.nicolaswinsten.pictionary.web.LobbyStompController  game logic
 */
@SpringBootApplication
public class PictionaryServer {
    public static void main(String[] args) {
        SpringApplication.run(PictionaryServer.class, args);
    }
}
