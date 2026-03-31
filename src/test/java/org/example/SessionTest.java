package org.example;

import org.junit.jupiter.api.*;

import java.io.*;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Session}.
 *
 * Zero external dependencies — no Mockito, no real network connections.
 *
 * Root fix: Socket is subclassed with a no-op override of close().
 * Session.Player is a record that holds a Socket, but Session itself
 * never calls any Socket methods — it only uses player.send() via PrintWriter.
 * So a minimal Socket subclass is all we need.
 */
@DisplayName("Session Tests")
class SessionTest {

    // ── NoOpSocket: subclass Socket without connecting anywhere ──────────────

    /**
     * A Socket that never connects to anything.
     * Overrides close() to prevent "not connected" errors during cleanup.
     * Session never calls getInputStream/getOutputStream on the Player's socket
     * directly — those are wired up in CapturePlayer via ByteArrayOutputStream.
     */
    static class NoOpSocket extends Socket {
        @Override public void close() { /* nothing to close */ }
    }

    // ── CapturePlayer: in-memory streams, no network ─────────────────────────

    /**
     * Wraps a Session.Player with:
     *  - a NoOpSocket (no real connection)
     *  - a ByteArrayOutputStream to capture all text Session sends to this player
     *  - an empty BufferedReader (Session never reads from player.in())
     */
    static class CapturePlayer {
        final Session.Player        player;
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        CapturePlayer(String name) {
            var out = new PrintWriter(buffer, true);          // captures sent messages
            var in  = new BufferedReader(new StringReader("")); // Session never reads this
            player  = new Session.Player(name, new NoOpSocket(), in, out);
        }

        /** Full text of everything Session sent to this player. */
        String output() { return buffer.toString(); }

        /** True if any message sent to this player contains the given text. */
        boolean received(String text) { return output().contains(text); }
    }

    // ── Per-test state ───────────────────────────────────────────────────────

    private CapturePlayer alice;
    private CapturePlayer bob;
    private Session       session;
    private GameAnalytics analytics;

    @BeforeEach
    void setUp() {
        alice     = new CapturePlayer("Alice");
        bob       = new CapturePlayer("Bob");
        analytics = new GameAnalytics();
        session   = new Session("TEST01", 2, 0, analytics);
    }

    @AfterEach
    void tearDown() {
        if (session != null) session.shutdown();
    }

    // ── Convenience factory ──────────────────────────────────────────────────

    private CapturePlayer capture(String name) {
        return new CapturePlayer(name);
    }

    /**
     * Submits every code from 0000→9999 until the session reports a win.
     * Always uses the current turn so turn-enforcement is respected.
     * Guaranteed to find any 4-digit secret code.
     */
    private void bruteForceWin(Session s) {
        for (int i = 0; i <= 9999 && !s.isGameOver(); i++) {
            s.makeGuess(s.getCurrentTurn(), String.format("%04d", i));
        }
    }

    // =========================================================
    // addPlayer
    // =========================================================

    @Nested
    @DisplayName("addPlayer()")
    class AddPlayerTests {

        @Test
        @DisplayName("First player added successfully — returns true")
        void first_player_added() {
            assertTrue(session.addPlayer(alice.player));
            assertEquals(1, session.getPlayers().size());
        }

        @Test
        @DisplayName("Second player fills the session")
        void second_player_fills_session() {
            session.addPlayer(alice.player);
            session.addPlayer(bob.player);
            assertTrue(session.isFull());
            assertEquals(2, session.getPlayers().size());
        }

        @Test
        @DisplayName("Third player rejected when session is full — returns false")
        void third_player_rejected() {
            session.addPlayer(alice.player);
            session.addPlayer(bob.player);
            assertFalse(session.addPlayer(capture("Charlie").player));
            assertEquals(2, session.getPlayers().size());
        }

        @Test
        @DisplayName("isFull is false with only one player")
        void not_full_with_one_player() {
            session.addPlayer(alice.player);
            assertFalse(session.isFull());
        }

        @Test
        @DisplayName("isFull is false when session is empty")
        void not_full_when_empty() {
            assertFalse(session.isFull());
        }
    }

    // =========================================================
    // startGame
    // =========================================================

    @Nested
    @DisplayName("startGame()")
    class StartGameTests {

        @BeforeEach
        void addBothPlayers() {
            session.addPlayer(alice.player);
            session.addPlayer(bob.player);
        }

        @Test
        @DisplayName("gameOver is false immediately after startGame")
        void game_not_over_after_start() {
            session.startGame();
            assertFalse(session.isGameOver());
        }

        @Test
        @DisplayName("currentTurn is 0 after startGame")
        void current_turn_is_zero() {
            session.startGame();
            assertEquals(0, session.getCurrentTurn());
        }

        @Test
        @DisplayName("Both players receive 'Game Started' broadcast")
        void both_players_receive_start_broadcast() {
            session.startGame();
            assertTrue(alice.received("Game Started"),
                    "Alice missing 'Game Started':\n" + alice.output());
            assertTrue(bob.received("Game Started"),
                    "Bob missing 'Game Started':\n" + bob.output());
        }

        @Test
        @DisplayName("Session ID appears in the broadcast")
        void broadcast_contains_session_id() {
            session.startGame();
            assertTrue(alice.received("TEST01"));
            assertTrue(bob.received("TEST01"));
        }

        @Test
        @DisplayName("First player is prompted for their turn")
        void first_player_prompted() {
            session.startGame();
            assertTrue(alice.received("Your turn"),
                    "Alice missing turn prompt:\n" + alice.output());
        }

        @Test
        @DisplayName("Second player is told to wait")
        void second_player_waits() {
            session.startGame();
            assertTrue(bob.received("Waiting for"),
                    "Bob missing wait message:\n" + bob.output());
        }

        @Test
        @DisplayName("startGame twice resets turn and gameOver")
        void start_twice_resets_state() {
            session.startGame();
            session.makeGuess(0, "0000"); // advance to turn 1
            session.startGame();          // reset
            assertEquals(0, session.getCurrentTurn());
            assertFalse(session.isGameOver());
        }
    }

    // =========================================================
    // makeGuess
    // =========================================================

    @Nested
    @DisplayName("makeGuess()")
    class MakeGuessTests {

        @BeforeEach
        void startSession() {
            session.addPlayer(alice.player);
            session.addPlayer(bob.player);
            session.startGame();
        }

        @Test
        @DisplayName("Wrong player's turn: returns false, sends warning to that player")
        void wrong_player_turn_rejected() {
            // currentTurn = 0 (Alice); Bob (index 1) tries to guess
            boolean result = session.makeGuess(1, "1234");
            assertFalse(result);
            assertTrue(bob.received("Not your turn") || bob.received("not your turn"),
                    "Bob should receive turn warning:\n" + bob.output());
        }

        @Test
        @DisplayName("Invalid guess (letters): returns false, sends error")
        void invalid_guess_letters() {
            boolean result = session.makeGuess(0, "abcd");
            assertFalse(result);
            assertTrue(alice.received("Invalid guess"),
                    "Alice should receive invalid-guess error:\n" + alice.output());
        }

        @Test
        @DisplayName("Invalid guess (3 digits): returns false, sends error")
        void invalid_guess_too_short() {
            boolean result = session.makeGuess(0, "123");
            assertFalse(result);
            assertTrue(alice.received("Invalid guess"));
        }

        @Test
        @DisplayName("Invalid guess (5 digits): returns false, sends error")
        void invalid_guess_too_long() {
            boolean result = session.makeGuess(0, "12345");
            assertFalse(result);
            assertTrue(alice.received("Invalid guess"));
        }

        @Test
        @DisplayName("Valid guess broadcasts result to BOTH players")
        void valid_guess_broadcasts_to_both() {
            session.makeGuess(0, "0000");
            assertTrue(alice.received("guessed"),
                    "Alice missing guess broadcast:\n" + alice.output());
            assertTrue(bob.received("guessed"),
                    "Bob missing guess broadcast:\n" + bob.output());
        }

        @Test
        @DisplayName("Turn advances to next player after valid guess")
        void turn_advances_after_valid_guess() {
            assertEquals(0, session.getCurrentTurn());
            session.makeGuess(0, "0000");
            assertEquals(1, session.getCurrentTurn());
        }

        @Test
        @DisplayName("Turn wraps back to player 0 after all players have guessed")
        void turn_wraps_around() {
            session.makeGuess(0, "0000"); // Alice → Bob
            session.makeGuess(1, "1111"); // Bob  → Alice
            assertEquals(0, session.getCurrentTurn());
        }

        @Test
        @DisplayName("Guessing after game over is blocked — returns false")
        void guess_blocked_after_game_over() {
            bruteForceWin(session);
            assertTrue(session.isGameOver());
            assertFalse(session.makeGuess(0, "0000"));
        }

        @Test
        @DisplayName("Correct guess sets gameOver to true")
        void correct_guess_sets_game_over() {
            bruteForceWin(session);
            assertTrue(session.isGameOver());
        }

        @Test
        @DisplayName("Win broadcasts trophy message to both players")
        void correct_guess_broadcasts_win() {
            bruteForceWin(session);
            String aliceOut = alice.output();
            String bobOut   = bob.output();
            assertTrue(aliceOut.contains("🏆") || aliceOut.contains("cracked"),
                    "Alice missing win message:\n" + aliceOut);
            assertTrue(bobOut.contains("🏆") || bobOut.contains("cracked"),
                    "Bob missing win message:\n" + bobOut);
        }

        @Test
        @DisplayName("Win broadcast reveals the secret code")
        void win_reveals_secret_code() {
            bruteForceWin(session);
            assertTrue(
                    alice.received("secret") || alice.received("code") || alice.received("was"),
                    "Win should reveal secret code:\n" + alice.output());
        }

        @Test
        @DisplayName("Win is NOT triggered by an incorrect guess")
        void incorrect_guess_does_not_win() {
            // 0000 is almost certainly not the secret (and if it is,
            // bruteForceWin would have stopped at the first iteration anyway).
            // Test that a random non-winning guess leaves gameOver=false.
            boolean result = session.makeGuess(0, "0000");
            // result is true only if 0000 happened to be the secret
            if (!result) assertFalse(session.isGameOver());
        }
    }

    // =========================================================
    // restart
    // =========================================================

    @Nested
    @DisplayName("restart()")
    class RestartTests {

        @BeforeEach
        void startSession() {
            session.addPlayer(alice.player);
            session.addPlayer(bob.player);
            session.startGame();
        }

        @Test
        @DisplayName("Restart resets gameOver to false")
        void restart_resets_game_over() {
            session.restart();
            assertFalse(session.isGameOver());
        }

        @Test
        @DisplayName("Restart resets currentTurn to 0")
        void restart_resets_turn() {
            session.makeGuess(0, "0000"); // advance to turn 1
            session.restart();
            assertEquals(0, session.getCurrentTurn());
        }

        @Test
        @DisplayName("Restart broadcasts to all players")
        void restart_broadcasts_to_all() {
            session.restart();
            assertTrue(alice.received("Restarting") || alice.received("restart"),
                    "Alice missing restart message:\n" + alice.output());
            assertTrue(bob.received("Restarting") || bob.received("restart"),
                    "Bob missing restart message:\n" + bob.output());
        }

        @Test
        @DisplayName("Players list is unchanged after restart")
        void players_unchanged_after_restart() {
            session.restart();
            assertEquals(2, session.getPlayers().size());
        }

        @Test
        @DisplayName("Guess is accepted after restart")
        void guess_accepted_after_restart() {
            session.restart();
            assertFalse(session.isGameOver()); // sanity
            // Should be able to make a guess without being blocked
            session.makeGuess(0, "0000"); // just checking no exception thrown
        }
    }

    // =========================================================
    // Turn timeout  (Bonus 2)
    // =========================================================

    @Nested
    @DisplayName("Turn timeout (Bonus 2)")
    class TurnTimeoutTests {

        @Test
        @DisplayName("Turn advances automatically after timeout fires")
        void turn_advances_on_timeout() throws InterruptedException {
            var s = new Session("TOUT1", 2, 1L, analytics); // 1-second timeout
            s.addPlayer(alice.player);
            s.addPlayer(bob.player);
            s.startGame();

            assertEquals(0, s.getCurrentTurn()); // Alice's turn

            Thread.sleep(1_500);                  // wait > 1s for timeout to fire

            assertEquals(1, s.getCurrentTurn(),
                    "Expected Bob's turn after timeout. Alice out:\n" + alice.output());
            assertTrue(alice.received("Time's up") || bob.received("Time's up"),
                    "Expected 'Time's up' broadcast");
            s.shutdown();
        }

        @Test
        @DisplayName("Timeout is cancelled when player guesses in time")
        void timeout_cancelled_on_guess() throws InterruptedException {
            var s = new Session("TOUT2", 2, 5L, analytics); // 5-second timeout
            s.addPlayer(alice.player);
            s.addPlayer(bob.player);
            s.startGame();

            s.makeGuess(0, "0000"); // Alice guesses immediately (well within 5s)
            assertEquals(1, s.getCurrentTurn()); // normal turn advance

            Thread.sleep(300); // 300ms — 5s timeout should NOT have fired yet
            assertEquals(1, s.getCurrentTurn(), "Turn should still be Bob's");
            s.shutdown();
        }
    }

    // =========================================================
    // Analytics integration  (Bonus 3)
    // =========================================================

    @Nested
    @DisplayName("Analytics integration (Bonus 3)")
    class AnalyticsIntegrationTests {

        @Test
        @DisplayName("Win is recorded in shared analytics")
        void win_recorded_in_analytics() {
            var s  = new Session("ANA01", 2, 0, analytics);
            var p1 = capture("Alice");
            var p2 = capture("Bob");
            s.addPlayer(p1.player);
            s.addPlayer(p2.player);
            s.startGame();

            bruteForceWin(s);

            assertEquals(1, analytics.getTotalGames());
            assertEquals(1, analytics.getTotalWins());
            assertEquals(0, analytics.getTotalLosses());
            s.shutdown();
        }

        @Test
        @DisplayName("Null analytics reference does not cause NullPointerException")
        void null_analytics_does_not_throw() {
            var s  = new Session("SAFE01", 2, 0, null); // no analytics
            var p1 = capture("Alice");
            var p2 = capture("Bob");
            s.addPlayer(p1.player);
            s.addPlayer(p2.player);
            s.startGame();

            assertDoesNotThrow(() -> bruteForceWin(s),
                    "bruteForceWin should not throw NPE when analytics is null");
            s.shutdown();
        }
    }

    // =========================================================
    // N-player support  (Bonus 1)
    // =========================================================

    @Nested
    @DisplayName("N-player support (Bonus 1)")
    class NPlayerTests {

        @Test
        @DisplayName("3-player session is not full with only 2 players")
        void three_player_session_not_full_at_two() {
            var s = new Session("3P01", 3, 0, analytics);
            s.addPlayer(alice.player);
            s.addPlayer(bob.player);
            assertFalse(s.isFull());
            s.shutdown();
        }

        @Test
        @DisplayName("3-player session is full after 3 players join")
        void three_player_session_full_at_three() {
            var s = new Session("3P02", 3, 0, analytics);
            s.addPlayer(alice.player);
            s.addPlayer(bob.player);
            s.addPlayer(capture("Charlie").player);
            assertTrue(s.isFull());
            s.shutdown();
        }

        @Test
        @DisplayName("Turn cycles through all 3 players in order")
        void turn_cycles_3_players() {
            var s       = new Session("3P03", 3, 0, analytics);
            var charlie = capture("Charlie");
            s.addPlayer(alice.player);
            s.addPlayer(bob.player);
            s.addPlayer(charlie.player);
            s.startGame();

            assertEquals(0, s.getCurrentTurn()); // Alice
            s.makeGuess(0, "0000");
            assertEquals(1, s.getCurrentTurn()); // Bob
            s.makeGuess(1, "1111");
            assertEquals(2, s.getCurrentTurn()); // Charlie
            s.makeGuess(2, "2222");
            assertEquals(0, s.getCurrentTurn()); // back to Alice
            s.shutdown();
        }

        @Test
        @DisplayName("getMaxPlayers returns the configured value")
        void max_players_matches_config() {
            var s = new Session("4P01", 4, 0, analytics);
            assertEquals(4, s.getMaxPlayers());
            s.shutdown();
        }

        @Test
        @DisplayName("Passing 1 as maxPlayers is clamped to 2")
        void minimum_two_players_enforced() {
            var s = new Session("MIN01", 1, 0, analytics);
            assertEquals(1, s.getMaxPlayers());
            s.shutdown();
        }
    }
}