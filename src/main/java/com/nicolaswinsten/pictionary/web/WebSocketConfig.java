package com.nicolaswinsten.pictionary.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Configures the STOMP-over-WebSocket message broker used for all real-time game traffic.
 *
 * <h3>How messages flow</h3>
 * <ol>
 *   <li>The browser opens a WebSocket (or SockJS fallback) connection to {@code /ws}.</li>
 *   <li>Messages the client sends to {@code /app/*} are routed to
 *       {@link LobbyStompController @MessageMapping} methods.</li>
 *   <li>The server publishes responses to {@code /topic/*} destinations,
 *       which the built-in simple broker fans out to every subscribed client.</li>
 * </ol>
 *
 * <h3>Key destinations</h3>
 * <table>
 *   <tr><th>Destination</th><th>Direction</th><th>Purpose</th></tr>
 *   <tr><td>{@code /app/lobby}</td><td>client → server</td><td>join a lobby</td></tr>
 *   <tr><td>{@code /app/draw}</td><td>client → server</td><td>send a drawing stroke</td></tr>
 *   <tr><td>{@code /app/guess}</td><td>client → server</td><td>submit a guess</td></tr>
 *   <tr><td>{@code /app/ready}</td><td>client → server</td><td>mark yourself ready</td></tr>
 *   <tr><td>{@code /topic/lobby/{code}/players}</td><td>server → clients</td><td>player-status updates</td></tr>
 *   <tr><td>{@code /topic/lobby/{code}/draw}</td><td>server → clients</td><td>drawing stroke broadcast</td></tr>
 *   <tr><td>{@code /topic/lobby/{code}/chat}</td><td>server → clients</td><td>guess / chat messages</td></tr>
 * </table>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Enable an in-memory STOMP broker on {@code /topic} and route
     * client-sent messages prefixed with {@code /app} to controller methods.
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    /**
     * Expose {@code /ws} as the WebSocket/SockJS handshake endpoint.
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns("https://*.fly.dev", "http://localhost:*", "http://127.0.0.1:*")
            .withSockJS();
    }
}
