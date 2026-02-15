package com.nicolaswinsten.pictionary.web;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Controller
public class LobbyStompController {
    private static final Logger LOGGER = LoggerFactory.getLogger(LobbyStompController.class);
    private final SimpMessagingTemplate messagingTemplate;
    private final ConcurrentMap<String, String> sessionLobby = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> sessionClientId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> sessionPlayerName = new ConcurrentHashMap<>();

    private static final String[] NAME_PREFIXES = {
        "Brisk", "Sunny", "Clever", "Swift", "Bright", "Quiet", "Bold", "Kind",
        "Lucky", "Merry", "Nimble", "Calm", "Witty", "Gentle", "Stellar", "Zesty"
    };
    private static final String[] NAME_SUFFIXES = {
        "Fox", "Otter", "Hawk", "Panda", "Whale", "Lynx", "Robin", "Tiger",
        "Kite", "Finch", "Dolphin", "Raven", "Badger", "Koala", "Heron", "Orca"
    };

    public LobbyStompController(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/lobby")
    public void lobby(LobbyMessage message, @Header("simpSessionId") String sessionId) {
        if (message == null
                || message.code() == null
                || message.code().isBlank()
                || message.clientId() == null
                || message.clientId().isBlank()
                || sessionId == null) {
            return;
        }
        String code = message.code().trim();
        String clientId = message.clientId().trim();
        String name = sessionPlayerName.computeIfAbsent(sessionId, key -> generateName(clientId));
        sessionLobby.put(sessionId, code);
        sessionClientId.put(sessionId, clientId);
        sendExistingPlayersToNewcomer(code, sessionId, clientId);
        messagingTemplate.convertAndSend(
            "/topic/lobby/" + code + "/players",
            new PlayerJoinedMessage(clientId, name, null)
        );
        LOGGER.info("Lobby joined: code={}, clientId={}, name={}", code, clientId, name);
    }

    @MessageMapping("/draw")
    public void draw(DrawEvent event, @Header("simpSessionId") String sessionId) {
        if (event == null || sessionId == null) {
            return;
        }
        String code = sessionLobby.get(sessionId);
        if (code == null || code.isBlank()) {
            return;
        }
        messagingTemplate.convertAndSend("/topic/lobby/" + code + "/draw", event);
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        if (event.getSessionId() != null) {
            sessionLobby.remove(event.getSessionId());
            sessionClientId.remove(event.getSessionId());
            sessionPlayerName.remove(event.getSessionId());
        }
    }

    private static String generateName(String clientId) {
        int hash = Math.abs(clientId.hashCode());
        String prefix = NAME_PREFIXES[hash % NAME_PREFIXES.length];
        String suffix = NAME_SUFFIXES[(hash / NAME_PREFIXES.length) % NAME_SUFFIXES.length];
        return prefix + " " + suffix;
    }

    private void sendExistingPlayersToNewcomer(String code, String sessionId, String targetClientId) {
        sessionLobby.forEach((existingSessionId, existingCode) -> {
            if (!code.equals(existingCode) || existingSessionId.equals(sessionId)) {
                return;
            }
            String existingClientId = sessionClientId.get(existingSessionId);
            String existingName = sessionPlayerName.get(existingSessionId);
            if (existingClientId == null || existingName == null) {
                return;
            }
            messagingTemplate.convertAndSend(
                "/topic/lobby/" + code + "/players",
                new PlayerJoinedMessage(existingClientId, existingName, targetClientId)
            );
        });
    }

    public record LobbyMessage(String code, String clientId) {}
    public record DrawEvent(String type, double x, double y, String sourceId) {}
    public record PlayerJoinedMessage(String clientId, String name, String targetClientId) {}
}
