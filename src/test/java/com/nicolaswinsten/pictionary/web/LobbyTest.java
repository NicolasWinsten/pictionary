package com.nicolaswinsten.pictionary.web;

import com.nicolaswinsten.pictionary.web.LobbyStompController.Lobby;
import com.nicolaswinsten.pictionary.web.LobbyStompController.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LobbyTest {

    private Lobby lobby;

    @BeforeEach
    void setUp() {
        lobby = new Lobby();
    }

    // ── addPlayer / removePlayer / isEmpty ──────────────────────────────────

    @Test
    void newLobbyIsEmpty() {
        assertThat(lobby.isEmpty()).isTrue();
    }

    @Test
    void addPlayerMakesLobbyNonEmpty() {
        lobby.addPlayer("s1", new Player("c1", "Alice", false, 0));
        assertThat(lobby.isEmpty()).isFalse();
    }

    @Test
    void removePlayerReturnsPlayer() {
        Player p = new Player("c1", "Alice", false, 0);
        lobby.addPlayer("s1", p);
        Player removed = lobby.removePlayer("s1");
        assertThat(removed).isEqualTo(p);
        assertThat(lobby.isEmpty()).isTrue();
    }

    @Test
    void removeNonexistentPlayerReturnsNull() {
        assertThat(lobby.removePlayer("missing")).isNull();
    }

    // ── markReady / isReady ─────────────────────────────────────────────────

    @Test
    void markReadySetsPlayerReady() {
        lobby.addPlayer("s1", new Player("c1", "Alice", false, 0));
        lobby.markReady("s1");
        assertThat(lobby.players.get("s1").ready()).isTrue();
    }

    @Test
    void markReadyPreservesScore() {
        lobby.addPlayer("s1", new Player("c1", "Alice", false, 5));
        lobby.markReady("s1");
        Player p = lobby.players.get("s1");
        assertThat(p.ready()).isTrue();
        assertThat(p.score()).isEqualTo(5);
    }

    @Test
    void isReadyFalseWhenEmpty() {
        assertThat(lobby.isReady()).isFalse();
    }

    @Test
    void isReadyFalseWhenNotAllReady() {
        lobby.addPlayer("s1", new Player("c1", "Alice", true, 0));
        lobby.addPlayer("s2", new Player("c2", "Bob", false, 0));
        assertThat(lobby.isReady()).isFalse();
    }

    @Test
    void isReadyTrueWhenAllReady() {
        lobby.addPlayer("s1", new Player("c1", "Alice", true, 0));
        lobby.addPlayer("s2", new Player("c2", "Bob", true, 0));
        assertThat(lobby.isReady()).isTrue();
    }

    @Test
    void isReadyTrueAfterMarkReady() {
        lobby.addPlayer("s1", new Player("c1", "Alice", false, 0));
        assertThat(lobby.isReady()).isFalse();
        lobby.markReady("s1");
        assertThat(lobby.isReady()).isTrue();
    }

    // ── chooseWord ──────────────────────────────────────────────────────────

    @Test
    void chooseWordSetsNonNullWord() {
        assertThat(lobby.word).isNull();
        lobby.chooseWord();
        assertThat(lobby.word).isNotNull().isNotBlank();
    }

    @Test
    void chooseWordChangesWord() {
        // Run enough times that we'd expect at least one change (100 words in pool)
        lobby.chooseWord();
        String first = lobby.word;
        boolean changed = false;
        for (int i = 0; i < 50; i++) {
            lobby.chooseWord();
            if (!lobby.word.equals(first)) {
                changed = true;
                break;
            }
        }
        assertThat(changed).isTrue();
    }

    // ── findNextSessionId ───────────────────────────────────────────────────

    @Test
    void findNextSessionIdReturnsNullWhenEmpty() {
        assertThat(lobby.findNextSessionId("c1")).isNull();
    }

    @Test
    void findNextSessionIdWrapsAround() {
        // ConcurrentHashMap doesn't guarantee insertion order, so we test the
        // wrap-around property: iterating through all players by repeatedly
        // calling findNextSessionId should visit every session exactly once.
        lobby.addPlayer("s1", new Player("c1", "Alice", false, 0));
        lobby.addPlayer("s2", new Player("c2", "Bob", false, 0));
        lobby.addPlayer("s3", new Player("c3", "Carol", false, 0));

        // Start from any player and walk through all of them
        String firstSessionId = lobby.findNextSessionId(null);

        java.util.Set<String> visited = new java.util.LinkedHashSet<>();

        String currentClientId = lobby.players.get(firstSessionId).clientId();
        visited.add(currentClientId);
        for (int i = 0; i < 3; i++) {
            String nextSession = lobby.findNextSessionId(currentClientId);
            assertThat(nextSession).isNotNull();
            currentClientId = lobby.players.get(nextSession).clientId();
            visited.add(currentClientId);
        }
        // After 3 steps from the start, we should have visited all 3 and wrapped back
        assertThat(visited).containsExactlyInAnyOrder("c1", "c2", "c3");
    }

    @Test
    void findNextSessionIdWithSinglePlayerReturnsSelf() {
        lobby.addPlayer("s1", new Player("c1", "Alice", false, 0));
        String next = lobby.findNextSessionId("c1");
        assertThat(next).isEqualTo("s1");
    }

    @Test
    void findNextSessionIdWithUnknownClientReturnsFirst() {
        lobby.addPlayer("s1", new Player("c1", "Alice", false, 0));
        // Unknown client ID — never found, so wraps to first
        String next = lobby.findNextSessionId("unknown");
        assertThat(next).isEqualTo("s1");
    }
}
