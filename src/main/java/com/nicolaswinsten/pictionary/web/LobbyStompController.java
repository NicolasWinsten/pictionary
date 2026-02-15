package com.nicolaswinsten.pictionary.web;

import java.util.Map;
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
    private final ConcurrentMap<String, Boolean> sessionReady = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> lobbyDrawerClientId = new ConcurrentHashMap<>();

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
            new PlayerStatusMessage(clientId, name, null, "joined")
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
        String clientId = sessionClientId.get(sessionId);
        if (clientId == null || clientId.isBlank()) {
            return;
        }
        String drawerClientId = lobbyDrawerClientId.get(code);
        if (drawerClientId == null || !drawerClientId.equals(clientId)) {
            return;
        }
        messagingTemplate.convertAndSend("/topic/lobby/" + code + "/draw", event);
    }

    @MessageMapping("/guess")
    public void guess(GuessInput message, @Header("simpSessionId") String sessionId) {
        if (message == null || message.text() == null || message.text().isBlank() || sessionId == null) {
            return;
        }
        String code = sessionLobby.get(sessionId);
        if (code == null || code.isBlank()) {
            return;
        }
        String clientId = sessionClientId.get(sessionId);
        if (clientId == null || clientId.isBlank()) {
            return;
        }
        String name = sessionPlayerName.computeIfAbsent(sessionId, key -> generateName(clientId));
        String text = message.text().trim();
        messagingTemplate.convertAndSend(
            "/topic/lobby/" + code + "/chat",
            new GuessMessage(clientId, name, text)
        );
    }

    @MessageMapping("/ready")
    public void ready(ReadyInput message, @Header("simpSessionId") String sessionId) {
        if (sessionId == null) {
            return;
        }
        String code = sessionLobby.get(sessionId);
        if (code == null || code.isBlank()) {
            return;
        }
        String clientId = sessionClientId.get(sessionId);
        if (clientId == null || clientId.isBlank()) {
            return;
        }
        sessionReady.put(sessionId, true);
        String name = sessionPlayerName.computeIfAbsent(sessionId, key -> generateName(clientId));
        messagingTemplate.convertAndSend(
            "/topic/lobby/" + code + "/players",
            new PlayerStatusMessage(clientId, name, null, "ready")
        );
        if (lobbyDrawerClientId.containsKey(code)) {
            return;
        }
        if (!isLobbyReady(code)) {
            return;
        }
        String drawerSessionId = findDrawerSessionId(code);
        if (drawerSessionId == null) {
            return;
        }
        String drawerClientId = sessionClientId.get(drawerSessionId);
        if (drawerClientId == null || drawerClientId.isBlank()) {
            return;
        }
        String drawerName = sessionPlayerName.get(drawerSessionId);
        lobbyDrawerClientId.put(code, drawerClientId);
        messagingTemplate.convertAndSend(
            "/topic/lobby/" + code + "/players",
            new PlayerStatusMessage(drawerClientId, drawerName, null, "drawing")
        );
        String label = drawerName == null || drawerName.isBlank() ? "A player" : drawerName;
        messagingTemplate.convertAndSend(
            "/topic/lobby/" + code + "/chat",
            new GuessMessage("system", "System", label + " is drawing.")
        );
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        if (sessionId == null) {
            return;
        }
        String code = sessionLobby.get(sessionId);
        String clientId = sessionClientId.get(sessionId);
        String name = sessionPlayerName.get(sessionId);
        sessionLobby.remove(sessionId);
        sessionClientId.remove(sessionId);
        sessionPlayerName.remove(sessionId);
        sessionReady.remove(sessionId);
        if (code != null && clientId != null && clientId.equals(lobbyDrawerClientId.get(code))) {
            lobbyDrawerClientId.remove(code);
        }
        if (code != null && !code.isBlank() && clientId != null && !clientId.isBlank()) {
            messagingTemplate.convertAndSend(
                "/topic/lobby/" + code + "/players",
                new PlayerStatusMessage(clientId, name, null, "left")
            );
        }
    }

    private boolean isLobbyReady(String code) {
        boolean hasPlayer = false;
        for (String sessionId : sessionLobby.keySet()) {
            if (!code.equals(sessionLobby.get(sessionId))) {
                continue;
            }
            hasPlayer = true;
            if (!Boolean.TRUE.equals(sessionReady.get(sessionId))) {
                return false;
            }
        }
        return hasPlayer;
    }

    private String findDrawerSessionId(String code) {
        for (String sessionId : sessionLobby.keySet()) {
            if (code.equals(sessionLobby.get(sessionId))) {
                return sessionId;
            }
        }
        return null;
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
                new PlayerStatusMessage(existingClientId, existingName, targetClientId, "joined")
            );
            if (Boolean.TRUE.equals(sessionReady.get(existingSessionId))) {
                messagingTemplate.convertAndSend(
                    "/topic/lobby/" + code + "/players",
                    new PlayerStatusMessage(existingClientId, existingName, targetClientId, "ready")
                );
            }
        });
        String drawerClientId = lobbyDrawerClientId.get(code);
        if (drawerClientId != null && !drawerClientId.isBlank()) {
            String drawerSessionId = sessionClientId.entrySet().stream()
                .filter(entry -> drawerClientId.equals(entry.getValue()))
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElse(null);
            String drawerName = drawerSessionId == null ? null : sessionPlayerName.get(drawerSessionId);
            messagingTemplate.convertAndSend(
                "/topic/lobby/" + code + "/players",
                new PlayerStatusMessage(drawerClientId, drawerName, targetClientId, "drawing")
            );
        }
    }

    public record LobbyMessage(String code, String clientId) {}
    public record DrawEvent(String type, double x, double y, String sourceId) {}
    public record PlayerStatusMessage(String clientId, String name, String targetClientId, String status) {}
    public record ReadyInput() {}
    public record GuessInput(String text) {}
    public record GuessMessage(String clientId, String name, String text) {}
}
