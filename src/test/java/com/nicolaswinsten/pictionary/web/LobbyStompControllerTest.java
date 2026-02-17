package com.nicolaswinsten.pictionary.web;

import com.nicolaswinsten.pictionary.web.LobbyStompController.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LobbyStompControllerTest {

    @Mock
    SimpMessagingTemplate messagingTemplate;

    @Captor
    ArgumentCaptor<Object> messageCaptor;

    LobbyStompController controller;

    @BeforeEach
    void setUp() {
        controller = new LobbyStompController(messagingTemplate);
    }

    // ── lobby() ─────────────────────────────────────────────────────────────

    @Test
    void joinBroadcastsJoinedStatus() {
        controller.lobby(new LobbyMessage("room1", "client-1"), "session-1");

        verify(messagingTemplate).convertAndSend(
            eq("/topic/lobby/room1/players"),
            messageCaptor.capture()
        );
        PlayerStatusMessage msg = (PlayerStatusMessage) messageCaptor.getValue();
        assertThat(msg.clientId()).isEqualTo("client-1");
        assertThat(msg.status()).isEqualTo(PlayerStatus.JOINED);
        assertThat(msg.score()).isZero();
        assertThat(msg.name()).isNotBlank();
    }

    @Test
    void joinWithNullMessageIsIgnored() {
        controller.lobby(null, "session-1");
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void joinWithBlankCodeIsIgnored() {
        controller.lobby(new LobbyMessage("  ", "client-1"), "session-1");
        verifyNoInteractions(messagingTemplate);
    }

    // ── ready() ─────────────────────────────────────────────────────────────

    @Test
    void readyBroadcastsReadyStatus() {
        // Two players so that readying one doesn't immediately trigger drawer assignment
        controller.lobby(new LobbyMessage("room1", "c1"), "s1");
        controller.lobby(new LobbyMessage("room1", "c2"), "s2");
        reset(messagingTemplate);

        controller.ready("s1");

        verify(messagingTemplate).convertAndSend(
            eq("/topic/lobby/room1/players"),
            messageCaptor.capture()
        );
        PlayerStatusMessage msg = (PlayerStatusMessage) messageCaptor.getValue();
        assertThat(msg.status()).isEqualTo(PlayerStatus.READY);
    }

    @Test
    void allReadyTriggersDrawerAssignment() {
        controller.lobby(new LobbyMessage("room1", "c1"), "s1");
        controller.lobby(new LobbyMessage("room1", "c2"), "s2");
        reset(messagingTemplate);

        controller.ready("s1");
        controller.ready("s2");

        // Should have sent READY for each player, then DRAWING + chat + word for the drawer
        verify(messagingTemplate, atLeastOnce()).convertAndSend(
            eq("/topic/lobby/room1/players"),
            messageCaptor.capture()
        );
        List<Object> allMessages = messageCaptor.getAllValues();
        boolean hasDrawing = allMessages.stream()
            .filter(m -> m instanceof PlayerStatusMessage)
            .map(m -> (PlayerStatusMessage) m)
            .anyMatch(m -> m.status() == PlayerStatus.DRAWING);
        assertThat(hasDrawing).isTrue();
    }

    @Test
    void readyWithUnknownSessionIsIgnored() {
        controller.ready("unknown-session");
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void drawerAlreadySetSkipsReassignment() {
        // Two players, both ready — drawer is assigned
        controller.lobby(new LobbyMessage("room1", "c1"), "s1");
        controller.lobby(new LobbyMessage("room1", "c2"), "s2");
        controller.ready("s1");
        controller.ready("s2");
        reset(messagingTemplate);

        // A third player joins and readies up — should not reassign drawer
        controller.lobby(new LobbyMessage("room1", "c3"), "s3");
        reset(messagingTemplate);
        controller.ready("s3");

        verify(messagingTemplate).convertAndSend(
            eq("/topic/lobby/room1/players"),
            messageCaptor.capture()
        );
        List<Object> msgs = messageCaptor.getAllValues();
        boolean hasDrawing = msgs.stream()
            .filter(m -> m instanceof PlayerStatusMessage)
            .map(m -> (PlayerStatusMessage) m)
            .anyMatch(m -> m.status() == PlayerStatus.DRAWING);
        assertThat(hasDrawing).isFalse();
    }

    // ── guess() ─────────────────────────────────────────────────────────────

    @Test
    void incorrectGuessBroadcastsChat() {
        // Set up a lobby with a drawer and a word
        controller.lobby(new LobbyMessage("room1", "c1"), "s1");
        controller.lobby(new LobbyMessage("room1", "c2"), "s2");
        controller.ready("s1");
        controller.ready("s2");
        reset(messagingTemplate);

        controller.guess(new GuessInput("wrong answer"), "s2");

        verify(messagingTemplate).convertAndSend(
            eq("/topic/lobby/room1/chat"),
            messageCaptor.capture()
        );
        GuessMessage msg = (GuessMessage) messageCaptor.getValue();
        assertThat(msg.text()).isEqualTo("wrong answer");
        assertThat(msg.clientId()).isNotEqualTo("system");
    }

    @Test
    void correctGuessAwardsPointAndStartsNextRound() {
        controller.lobby(new LobbyMessage("room1", "c1"), "s1");
        controller.lobby(new LobbyMessage("room1", "c2"), "s2");
        controller.ready("s1");
        controller.ready("s2");

        // Find the word and the non-drawer session
        // We need to figure out who the drawer is and guess from the other player
        reset(messagingTemplate);

        // Try guessing every possible word from both sessions — the correct guess
        // from the non-drawer will trigger scoring
        // Instead, let's use a more targeted approach: guess from s2 with various words
        // We can't know the word, but we can set it via reflection or just test the flow
        // by capturing what was sent after ready

        // Re-setup to capture the word
        controller = new LobbyStompController(messagingTemplate);
        controller.lobby(new LobbyMessage("room1", "c1"), "s1");
        controller.lobby(new LobbyMessage("room1", "c2"), "s2");
        controller.ready("s1");
        controller.ready("s2");

        // Capture the WordMessage to find the actual word
        ArgumentCaptor<Object> wordCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(
            eq("/topic/lobby/room1/word"),
            wordCaptor.capture()
        );
        WordMessage wordMsg = (WordMessage) wordCaptor.getValue();
        String word = wordMsg.word();
        String drawerClientId = wordMsg.targetClientId();

        // Determine which session is the non-drawer
        String guesserSession = drawerClientId.equals("c1") ? "s2" : "s1";

        reset(messagingTemplate);
        controller.guess(new GuessInput(word), guesserSession);

        // Verify SCORED status was broadcast
        verify(messagingTemplate, atLeastOnce()).convertAndSend(
            eq("/topic/lobby/room1/players"),
            messageCaptor.capture()
        );
        List<Object> allMsgs = messageCaptor.getAllValues();

        PlayerStatusMessage scoredMsg = allMsgs.stream()
            .filter(m -> m instanceof PlayerStatusMessage)
            .map(m -> (PlayerStatusMessage) m)
            .filter(m -> m.status() == PlayerStatus.SCORED)
            .findFirst()
            .orElse(null);
        assertThat(scoredMsg).isNotNull();
        assertThat(scoredMsg.score()).isEqualTo(1);

        // Verify a DRAWING status was sent (next round started)
        PlayerStatusMessage drawingMsg = allMsgs.stream()
            .filter(m -> m instanceof PlayerStatusMessage)
            .map(m -> (PlayerStatusMessage) m)
            .filter(m -> m.status() == PlayerStatus.DRAWING)
            .findFirst()
            .orElse(null);
        assertThat(drawingMsg).isNotNull();

        // Verify system chat announcement
        verify(messagingTemplate, atLeastOnce()).convertAndSend(
            eq("/topic/lobby/room1/chat"),
            any(GuessMessage.class)
        );
    }

    @Test
    void correctGuessCaseInsensitive() {
        controller.lobby(new LobbyMessage("room1", "c1"), "s1");
        controller.lobby(new LobbyMessage("room1", "c2"), "s2");
        controller.ready("s1");
        controller.ready("s2");

        ArgumentCaptor<Object> wordCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(
            eq("/topic/lobby/room1/word"),
            wordCaptor.capture()
        );
        WordMessage wordMsg = (WordMessage) wordCaptor.getValue();
        String guesserSession = wordMsg.targetClientId().equals("c1") ? "s2" : "s1";

        reset(messagingTemplate);
        // Guess in all lowercase
        controller.guess(new GuessInput(wordMsg.word().toLowerCase()), guesserSession);

        verify(messagingTemplate, atLeastOnce()).convertAndSend(
            eq("/topic/lobby/room1/players"),
            messageCaptor.capture()
        );
        boolean scored = messageCaptor.getAllValues().stream()
            .filter(m -> m instanceof PlayerStatusMessage)
            .map(m -> (PlayerStatusMessage) m)
            .anyMatch(m -> m.status() == PlayerStatus.SCORED);
        assertThat(scored).isTrue();
    }

    @Test
    void guessWithNullInputIsIgnored() {
        controller.lobby(new LobbyMessage("room1", "c1"), "s1");
        reset(messagingTemplate);
        controller.guess(null, "s1");
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void guessWithBlankTextIsIgnored() {
        controller.lobby(new LobbyMessage("room1", "c1"), "s1");
        reset(messagingTemplate);
        controller.guess(new GuessInput("   "), "s1");
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void guessBeforeWordIsSetBroadcastsAsChat() {
        // Join but don't ready up — no word is set
        controller.lobby(new LobbyMessage("room1", "c1"), "s1");
        reset(messagingTemplate);

        controller.guess(new GuessInput("something"), "s1");

        verify(messagingTemplate).convertAndSend(
            eq("/topic/lobby/room1/chat"),
            messageCaptor.capture()
        );
        GuessMessage msg = (GuessMessage) messageCaptor.getValue();
        assertThat(msg.text()).isEqualTo("something");
    }

    // ── draw() ──────────────────────────────────────────────────────────────

    @Test
    void drawerCanSendDrawEvents() {
        controller.lobby(new LobbyMessage("room1", "c1"), "s1");
        controller.lobby(new LobbyMessage("room1", "c2"), "s2");
        controller.ready("s1");
        controller.ready("s2");

        // Figure out who the drawer is
        ArgumentCaptor<Object> wordCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(
            eq("/topic/lobby/room1/word"),
            wordCaptor.capture()
        );
        String drawerClientId = ((WordMessage) wordCaptor.getValue()).targetClientId();
        String drawerSession = drawerClientId.equals("c1") ? "s1" : "s2";

        reset(messagingTemplate);
        DrawEvent event = new DrawEvent("start", 10, 20, drawerClientId);
        controller.draw(event, drawerSession);

        verify(messagingTemplate).convertAndSend(
            eq("/topic/lobby/room1/draw"),
            eq(event)
        );
    }

    @Test
    void nonDrawerCannotSendDrawEvents() {
        controller.lobby(new LobbyMessage("room1", "c1"), "s1");
        controller.lobby(new LobbyMessage("room1", "c2"), "s2");
        controller.ready("s1");
        controller.ready("s2");

        ArgumentCaptor<Object> wordCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(
            eq("/topic/lobby/room1/word"),
            wordCaptor.capture()
        );
        String drawerClientId = ((WordMessage) wordCaptor.getValue()).targetClientId();
        String nonDrawerSession = drawerClientId.equals("c1") ? "s2" : "s1";

        reset(messagingTemplate);
        controller.draw(new DrawEvent("start", 10, 20, "whoever"), nonDrawerSession);

        verifyNoInteractions(messagingTemplate);
    }

    // ── newcomer catch-up ───────────────────────────────────────────────────

    @Test
    void newcomerReceivesExistingPlayers() {
        controller.lobby(new LobbyMessage("room1", "c1"), "s1");
        controller.ready("s1");
        reset(messagingTemplate);

        // Second player joins — should receive catch-up messages targeted at c2
        controller.lobby(new LobbyMessage("room1", "c2"), "s2");

        verify(messagingTemplate, atLeastOnce()).convertAndSend(
            eq("/topic/lobby/room1/players"),
            messageCaptor.capture()
        );
        List<Object> msgs = messageCaptor.getAllValues();

        // Should have a JOINED replay for c1 targeted at c2
        boolean hasTargetedJoin = msgs.stream()
            .filter(m -> m instanceof PlayerStatusMessage)
            .map(m -> (PlayerStatusMessage) m)
            .anyMatch(m -> m.clientId().equals("c1")
                && m.status() == PlayerStatus.JOINED
                && "c2".equals(m.targetClientId()));
        assertThat(hasTargetedJoin).isTrue();

        // Should have a READY replay for c1 targeted at c2
        boolean hasTargetedReady = msgs.stream()
            .filter(m -> m instanceof PlayerStatusMessage)
            .map(m -> (PlayerStatusMessage) m)
            .anyMatch(m -> m.clientId().equals("c1")
                && m.status() == PlayerStatus.READY
                && "c2".equals(m.targetClientId()));
        assertThat(hasTargetedReady).isTrue();
    }
}
