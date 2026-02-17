package com.nicolaswinsten.pictionary.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.annotation.JsonValue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Core game controller — handles every STOMP message and maintains all server-side state.
 *
 * <h3>State model</h3>
 * Game state is organised into {@link Lobby} objects, each containing a map of
 * {@link Player} records keyed by STOMP session ID.  A lightweight reverse index
 * ({@code sessionLobby}) maps session IDs back to lobby codes for fast lookup.
 * Nothing is persisted; a server restart wipes all lobbies.
 *
 * <h3>Game lifecycle</h3>
 * <ol>
 *   <li><strong>Join</strong> — client sends {@code /app/lobby} with a lobby code and a
 *       client-generated UUID.  The server stores the mapping, generates a deterministic
 *       animal name, and broadcasts a "joined" status to the lobby.</li>
 *   <li><strong>Ready up</strong> — each player sends {@code /app/ready}.  When every player
 *       in the lobby is ready (and no drawer has been assigned yet), the first player found
 *       is chosen as drawer.</li>
 *   <li><strong>Drawing</strong> — the drawer sends pointer events via {@code /app/draw};
 *       the server verifies the sender is the drawer and re-broadcasts to the lobby.</li>
 *   <li><strong>Guessing</strong> — non-drawers send guesses via {@code /app/guess};
 *       the server broadcasts them to the lobby chat.</li>
 *   <li><strong>Disconnect</strong> — on WebSocket close the server cleans up all session
 *       state and broadcasts a "left" status.  If the drawer disconnects, the drawer slot
 *       is cleared.  Empty lobbies are removed from the map.</li>
 * </ol>
 *
 * <h3>Newcomer catch-up</h3>
 * When a player joins a lobby that already has members, the server replays synthetic
 * "joined" / "ready" / "drawing" messages targeted at the newcomer so their player list
 * is immediately up to date (see {@link #sendExistingPlayersToNewcomer}).
 */
@Controller
public class LobbyStompController {
    private static final Logger LOGGER = LoggerFactory.getLogger(LobbyStompController.class);
    private final SimpMessagingTemplate messagingTemplate;

    /** Lobby code → lobby instance. */
    private final ConcurrentMap<String, Lobby> lobbies = new ConcurrentHashMap<>();
    /** STOMP session ID → lobby code (reverse index for fast lookup). */
    private final ConcurrentMap<String, String> sessionLobby = new ConcurrentHashMap<>();

    private static final String[] NAME_PREFIXES = {
        "Brisk", "Sunny", "Clever", "Swift", "Bright", "Quiet", "Bold", "Kind",
        "Lucky", "Merry", "Nimble", "Calm", "Witty", "Gentle", "Stellar", "Zesty"
    };
    private static final String[] NAME_SUFFIXES = {
        "Fox", "Otter", "Hawk", "Panda", "Whale", "Lynx", "Robin", "Tiger",
        "Kite", "Finch", "Dolphin", "Raven", "Badger", "Koala", "Heron", "Orca"
    };
    private static final String[] NOUNS = {
        // Easy
        "Apple", "Banana", "Car", "Dog", "Cat", "Sun", "Tree", "House",
        "Fish", "Ball", "Hat", "Star", "Moon", "Book", "Flower", "Bird",
        "Cake", "Door", "Key", "Egg", "Cup", "Boat", "Shoe", "Bell",
        "Cloud",
        // Medium
        "Guitar", "Elephant", "Pizza", "Rainbow", "Bicycle", "Snowman",
        "Volcano", "Penguin", "Pirate", "Robot", "Dragon", "Mermaid",
        "Cactus", "Anchor", "Igloo", "Compass", "Hammock", "Lantern",
        "Scarecrow", "Telescope", "Windmill", "Jellyfish", "Campfire",
        "Treasure", "Surfboard", "Lighthouse", "Waterfall", "Parachute",
        "Fireworks", "Kangaroo", "Pineapple", "Astronaut", "Skateboard",
        "Trampoline", "Mushroom", "Suitcase", "Tornado", "Submarine",
        "Flamingo", "Butterfly", "Dinosaur", "Helicopter", "Snowflake",
        "Popcorn", "Giraffe", "Sandwich", "Tadpole", "Pretzel",
        // Hard
        "Eclipse", "Mirage", "Quicksand", "Avalanche", "Hibernate",
        "Reflection", "Silhouette", "Constellation", "Camouflage",
        "Electricity", "Gravity", "Evolution", "Photosynthesis",
        "Labyrinth", "Kaleidoscope", "Hieroglyphics", "Ventriloquist",
        "Claustrophobia", "Sarcasm", "Nostalgia", "Paradox",
        "Superstition", "Democracy", "Encryption", "Perspective"
    };

    public LobbyStompController(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Handles a player joining (or re-joining) a lobby.
     *
     * <p>Stores session state, generates a display name, replays existing lobby members
     * to the newcomer, then broadcasts a "joined" status to the whole lobby.
     *
     * @param message contains the lobby {@code code} and the client's persistent {@code clientId}
     * @param sessionId the STOMP session ID injected by Spring
     */
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
        String name = generateName(clientId);

        Lobby lobby = lobbies.computeIfAbsent(code, k -> new Lobby());
        lobby.addPlayer(sessionId, new Player(clientId, name, false, 0));
        sessionLobby.put(sessionId, code);

        sendExistingPlayersToNewcomer(lobby, code, sessionId, clientId);
        messagingTemplate.convertAndSend(
            "/topic/lobby/" + code + "/players",
            new PlayerStatusMessage(clientId, name, null, PlayerStatus.JOINED, 0)
        );
        LOGGER.info("Lobby joined: code={}, clientId={}, name={}", code, clientId, name);
    }

    /**
     * Relays a drawing stroke to every client in the drawer's lobby.
     *
     * <p>Only the designated drawer may send draw events — messages from any other
     * session are silently dropped.
     */
    @MessageMapping("/draw")
    public void draw(DrawEvent event, @Header("simpSessionId") String sessionId) {
        if (event == null || sessionId == null) {
            return;
        }
        String code = sessionLobby.get(sessionId);
        if (code == null || code.isBlank()) {
            return;
        }
        Lobby lobby = lobbies.get(code);
        if (lobby == null) {
            return;
        }
        Player player = lobby.players.get(sessionId);
        if (player == null) {
            return;
        }
        String drawerClientId = lobby.drawerClientId;
        if (drawerClientId == null || !drawerClientId.equals(player.clientId())) {
            return;
        }
        messagingTemplate.convertAndSend("/topic/lobby/" + code + "/draw", event);
    }

    /**
     * Broadcasts a player's guess to the lobby chat ({@code /topic/lobby/{code}/chat}).
     */
    @MessageMapping("/guess")
    public void guess(GuessInput message, @Header("simpSessionId") String sessionId) {
        if (message == null || message.text() == null || message.text().isBlank() || sessionId == null) {
            return;
        }
        String code = sessionLobby.get(sessionId);
        if (code == null || code.isBlank()) {
            return;
        }
        Lobby lobby = lobbies.get(code);
        if (lobby == null) {
            return;
        }
        Player player = lobby.players.get(sessionId);
        if (player == null) {
            return;
        }
        String text = message.text().trim();
        if (lobby.word != null && text.equalsIgnoreCase(lobby.word)) {
            // Correct guess — increment score
            Player scored = new Player(player.clientId(), player.name(), player.ready(), player.score() + 1);
            lobby.players.put(sessionId, scored);
            messagingTemplate.convertAndSend(
                "/topic/lobby/" + code + "/players",
                new PlayerStatusMessage(scored.clientId(), scored.name(), null, PlayerStatus.SCORED, scored.score())
            );
            messagingTemplate.convertAndSend(
                "/topic/lobby/" + code + "/chat",
                new GuessMessage("system", "System", scored.name() + " correctly guessed " + lobby.word, null)
            );
            startNextRound(lobby, code);
        } else {
            messagingTemplate.convertAndSend(
                "/topic/lobby/" + code + "/chat",
                new GuessMessage(player.clientId(), player.name(), text, null)
            );
        }
    }

    /**
     * Marks a player as ready and, once every player in the lobby is ready,
     * assigns a drawer and kicks off the drawing round.
     *
     * <p>The drawer is chosen by {@link Lobby#findFirstSessionId()} — currently the first
     * session found in the players map (effectively insertion order).
     * A drawer is only assigned when <em>all</em> players are ready <em>and</em> no
     * drawer has been set yet for this lobby.
     */
    @MessageMapping("/ready")
    public void ready(@Header("simpSessionId") String sessionId) {
        String code = sessionLobby.get(sessionId);
        if (code == null || code.isBlank()) {
            return;
        }
        Lobby lobby = lobbies.get(code);
        if (lobby == null) {
            return;
        }
        lobby.markReady(sessionId);
        Player player = lobby.players.get(sessionId);
        if (player == null) {
            return;
        }
        messagingTemplate.convertAndSend(
            "/topic/lobby/" + code + "/players",
            new PlayerStatusMessage(player.clientId(), player.name(), null, PlayerStatus.READY, player.score())
        );
        // If every player is ready and no drawer has been assigned yet, pick the first player as drawer
        if (lobby.isReady() && lobby.drawerClientId == null) {
            startNextRound(lobby, code);
        }
    }

    /**
     * Cleans up all server-side state when a WebSocket connection closes,
     * and notifies the lobby that the player has left.
     * If the disconnecting player was the drawer, the drawer slot is cleared.
     * Empty lobbies are removed from the map.
     */
    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        String code = sessionLobby.remove(sessionId);
        if (code == null) {
            return;
        }
        Lobby lobby = lobbies.get(code);
        if (lobby == null) {
            return;
        }
        Player player = lobby.removePlayer(sessionId);
        if (player == null) {
            return;
        }
        if (player.clientId().equals(lobby.drawerClientId)) {
            lobby.drawerClientId = null;
        }
        if (lobby.isEmpty()) {
            lobbies.remove(code);
        }
        messagingTemplate.convertAndSend(
            "/topic/lobby/" + code + "/players",
            new PlayerStatusMessage(player.clientId(), player.name(), null, PlayerStatus.LEFT, player.score())
        );
    }

    /**
     * Derives a deterministic "Adjective Animal" display name from a client ID.
     * The same client ID always produces the same name (e.g. "Brisk Fox").
     */
    private static String generateName(String clientId) {
        int hash = Math.abs(clientId.hashCode());
        String prefix = NAME_PREFIXES[hash % NAME_PREFIXES.length];
        String suffix = NAME_SUFFIXES[(hash / NAME_PREFIXES.length) % NAME_SUFFIXES.length];
        return prefix + " " + suffix;
    }

    /**
     * Replays "joined", "ready", and "drawing" messages for every existing lobby member
     * so a late-joining client immediately sees the full player list.
     *
     * <p>Messages are sent with {@code targetClientId} set so the frontend can filter
     * them and only apply them to the newcomer's UI.
     */
    private void sendExistingPlayersToNewcomer(Lobby lobby, String code, String sessionId, String targetClientId) {
        for (Map.Entry<String, Player> entry : lobby.players.entrySet()) {
            String existingSessionId = entry.getKey();
            Player existing = entry.getValue();
            if (existingSessionId.equals(sessionId)) {
                continue;
            }
            messagingTemplate.convertAndSend(
                "/topic/lobby/" + code + "/players",
                new PlayerStatusMessage(existing.clientId(), existing.name(), targetClientId, PlayerStatus.JOINED, existing.score())
            );
            if (existing.ready()) {
                messagingTemplate.convertAndSend(
                    "/topic/lobby/" + code + "/players",
                    new PlayerStatusMessage(existing.clientId(), existing.name(), targetClientId, PlayerStatus.READY, existing.score())
                );
            }
        }
        String drawerClientId = lobby.drawerClientId;
        if (drawerClientId != null && !drawerClientId.isBlank()) {
            // Find the drawer from the players map
            Player drawerPlayer = lobby.players.values().stream()
                .filter(p -> drawerClientId.equals(p.clientId()))
                .findFirst()
                .orElse(null);
            if (drawerPlayer != null) {
                messagingTemplate.convertAndSend(
                    "/topic/lobby/" + code + "/players",
                    new PlayerStatusMessage(drawerClientId, drawerPlayer.name(), targetClientId, PlayerStatus.DRAWING, drawerPlayer.score())
                );
            }
        }
    }

    /** Picks the next drawer, chooses a new word, and broadcasts the new round. */
    private void startNextRound(Lobby lobby, String code) {
        String nextSessionId = lobby.findNextSessionId(lobby.drawerClientId);
        if (nextSessionId == null) {
            return;
        }
        Player nextDrawer = lobby.players.get(nextSessionId);
        if (nextDrawer == null) {
            return;
        }
        lobby.drawerClientId = nextDrawer.clientId();
        lobby.chooseWord();
        
        messagingTemplate.convertAndSend(
            "/topic/lobby/" + code + "/players",
            new PlayerStatusMessage(nextDrawer.clientId(), nextDrawer.name(), null, PlayerStatus.DRAWING, nextDrawer.score())
        );
        messagingTemplate.convertAndSend("/topic/lobby/" + code + "/draw", new DrawEvent("clear", 0, 0, null));
        announceDrawer(lobby, code, nextDrawer);
    }

    /** Sends chat announcements and the word message for the current drawer. */
    private void announceDrawer(Lobby lobby, String code, Player drawer) {
        String label = drawer.name() == null || drawer.name().isBlank() ? "A player" : drawer.name();
        messagingTemplate.convertAndSend(
            "/topic/lobby/" + code + "/chat",
            new GuessMessage("system", "System", label + " is drawing.", null)
        );
        messagingTemplate.convertAndSend(
            "/topic/lobby/" + code + "/chat",
            new GuessMessage("system", "System", "Your word is: " + lobby.word, drawer.clientId())
        );
        messagingTemplate.convertAndSend(
            "/topic/lobby/" + code + "/word",
            new WordMessage(lobby.word, drawer.clientId())
        );
    }

    // ── Inner data types ─────────────────────────────────────────────────────

    /** Immutable snapshot of a player's state. Replaced atomically when {@code ready} changes. */
    record Player(String clientId, String name, boolean ready, int score) {}

    /** Mutable, thread-safe lobby containing players and the current drawer. */
    static final class Lobby {
        final ConcurrentMap<String, Player> players = new ConcurrentHashMap<>();
        volatile String drawerClientId;
        volatile String word;

        void addPlayer(String sessionId, Player player) {
            players.put(sessionId, player);
        }

        Player removePlayer(String sessionId) {
            return players.remove(sessionId);
        }

        /** Replaces the player record with {@code ready = true}. */
        void markReady(String sessionId) {
            players.computeIfPresent(sessionId, (k, p) -> new Player(p.clientId(), p.name(), true, p.score()));
        }

        /** Returns {@code true} when at least one player exists and every player is ready. */
        boolean isReady() {
            if (players.isEmpty()) {
                return false;
            }
            for (Player p : players.values()) {
                if (!p.ready()) {
                    return false;
                }
            }
            return true;
        }

        boolean isEmpty() {
            return players.isEmpty();
        }

        /** Picks a random noun from {@link #NOUNS} and stores it as the current word. */
        void chooseWord() {
            word = NOUNS[ThreadLocalRandom.current().nextInt(NOUNS.length)];
        }

        /** Returns the session ID of the first player found (insertion order). */
        String findFirstSessionId() {
            Map.Entry<String, Player> first = players.entrySet().iterator().hasNext()
                ? players.entrySet().iterator().next()
                : null;
            return first == null ? null : first.getKey();
        }

        /** Returns the session ID of the player after {@code currentClientId}, wrapping around. */
        String findNextSessionId(String currentClientId) {
            String firstSessionId = null;
            boolean found = false;
            for (Map.Entry<String, Player> entry : players.entrySet()) {
                if (firstSessionId == null) {
                    firstSessionId = entry.getKey();
                }
                if (found) {
                    return entry.getKey();
                }
                if (entry.getValue().clientId().equals(currentClientId)) {
                    found = true;
                }
            }
            // Wrap around to first player
            return firstSessionId;
        }
    }

    // ── DTOs (deserialized from / serialized to JSON by Spring) ──────────────

    /** Payload sent by the client when joining a lobby ({@code /app/lobby}). */
    public record LobbyMessage(String code, String clientId) {}

    /**
     * A single pointer event in a drawing stroke ({@code /app/draw}).
     * @param type  "start", "move", or "end"
     * @param x     normalised x coordinate on the canvas
     * @param y     normalised y coordinate on the canvas
     * @param sourceId  client ID of the drawer (used by the frontend to ignore own echoes)
     */
    public record DrawEvent(String type, double x, double y, String sourceId) {}

    /** The possible states a player status update can communicate. */
    public enum PlayerStatus {
        JOINED("joined"),
        READY("ready"),
        DRAWING("drawing"),
        LEFT("left"),
        SCORED("scored");

        private final String value;

        PlayerStatus(String value) { this.value = value; }

        @JsonValue
        public String getValue() { return value; }
    }

    public record PlayerStatusMessageInfo(String clientId, String name, String targetClientId, PlayerStatus status, int score) {}

    /**
     * Broadcast to {@code /topic/lobby/{code}/players} whenever a player's status changes.
     * @param clientId       the player this message is about
     * @param name           the player's display name
     * @param targetClientId if non-null, only this client should apply the message
     *                       (used during newcomer catch-up)
     * @param status         one of {@link PlayerStatus}
     */
    public record PlayerStatusMessage(String clientId, String name, String targetClientId, PlayerStatus status, int score) {}

    /** Payload received when a player submits a guess ({@code /app/guess}). */
    public record GuessInput(String text) {}

    /**
     * Chat/guess message broadcast to {@code /topic/lobby/{code}/chat}.
     * Also used for system announcements (clientId = "system").
     * @param targetClientId if non-null, only this client should display the message
     */
    public record GuessMessage(String clientId, String name, String text, String targetClientId) {}

    /**
     * Sent to {@code /topic/lobby/{code}/word} to tell the drawer which word to draw.
     * @param word           the word to draw
     * @param targetClientId the drawer's client ID (only this client should display it)
     */
    public record WordMessage(String word, String targetClientId) {}
}
