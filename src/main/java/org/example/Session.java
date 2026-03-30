package org.example;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.*;

public class Session {

    public record Player(String name, Socket socket, BufferedReader in, PrintWriter out) {
        public void send(String message) { out.println(message); }
    }

    private final String          sessionId;
    private final int             maxPlayers;       // Bonus 1: configurable N
    private final long            turnTimeoutSecs;  // Bonus 2: 0 = no timeout
    private final GameAnalytics   analytics;        // Bonus 3

    private final List<Player> players = new CopyOnWriteArrayList<>();

    private String  secretCode;
    private int     currentTurn  = 0;
    private boolean gameOver     = false;
    private int     totalGuesses = 0;

    // Bonus 2: scheduled executor for turn timeouts
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("timeout-scheduler").factory());
    private ScheduledFuture<?> turnTimer;
    private final GameLogic logic   = new GameLogic();

    public Session(String sessionId, int maxPlayers,
                   long turnTimeoutSecs, GameAnalytics analytics) {
        this.sessionId       = sessionId;
        this.maxPlayers      = Math.max(1, maxPlayers); // minimum 2
        this.turnTimeoutSecs = turnTimeoutSecs;
        this.analytics       = analytics;
    }

    public synchronized boolean addPlayer(Player player) {
        if (players.size() >= maxPlayers) return false;
        players.add(player);
        return true;
    }

    public boolean isFull()  { return players.size() == maxPlayers; }
    public String  getSessionId() { return sessionId; }
    public int     getMaxPlayers() { return maxPlayers; }
    public List<Player> getPlayers() { return players; }

    public synchronized void startGame() {
        secretCode   = logic.generateSecretCode();
        currentTurn  = 0;
        gameOver     = false;
        totalGuesses = 0;

        broadcast("═".repeat(54));
        broadcast("  🎮  Game Started!  Session: " + sessionId);
        if (turnTimeoutSecs > 0)
            broadcast("  ⏱   Turn timeout: " + turnTimeoutSecs + " seconds");
        broadcast("  Players: " + playerNames());
        broadcast("═".repeat(54));
        notifyTurn();
        scheduleTurnTimeout();
    }

    public synchronized boolean makeGuess(int playerIndex, String rawGuess) {
        if(gameOver) {
            players.get(playerIndex).send("⚠  Game over. Type RESTART or QUIT.");
            return false;
        }
        if(playerIndex != currentTurn) {
            players.get(playerIndex).send("Not your turn.");
        }

        String guess;
        try {
            guess = logic.validateGuess(rawGuess);
        } catch (IllegalArgumentException e) {
            players.get(playerIndex).send("⚠  Invalid guess: " + e.getMessage());
            return false;
        }

        // Cancel timeout — player made it in time
        cancelTurnTimeout();

        totalGuesses++;
        var result    = logic.checkGuess(secretCode, guess);
        var timestamp = logic.generateTimestampPrefix();
        broadcast("""
                %s  %s guessed: %s
                       → %s"""
                .formatted(timestamp, currentPlayer().name(), guess, result));

        if (result.isWin()) {
            gameOver = true;

            broadcast("═".repeat(54));
            broadcast("  🏆  %s cracked the code in %d guess%s!"
                    .formatted(currentPlayer().name(), totalGuesses,
                            totalGuesses == 1 ? "" : "es"));
            broadcast("  The secret code was: " + secretCode);
            broadcast("═".repeat(54));
            broadcast("  Type RESTART to play again or QUIT to exit.");

            // Bonus 3: record win
            if (analytics != null) analytics.recordGame(secretCode, true, totalGuesses);
            return true;
        }
        advanceTurn();
        notifyTurn();
        scheduleTurnTimeout();
        return false;
    }

    /** Restarts the game without disconnecting any player. */
    public synchronized void restart() {
        cancelTurnTimeout();
        broadcast("\n" + "─".repeat(54));
        broadcast("  🔄  Restarting game…");
        startGame();
    }

    private void cancelTurnTimeout() {
        if (turnTimer != null && !turnTimer.isDone()) {
            turnTimer.cancel(false);
        }
    }

    public void shutdown() {
        cancelTurnTimeout();
        scheduler.shutdownNow();
    }

    private void scheduleTurnTimeout() {
        if (turnTimeoutSecs <= 0) return;

        // Capture current turn index for the lambda (avoid race after advance)
        final int timedOutTurn = currentTurn;

        turnTimer = scheduler.schedule(() -> {
            synchronized (this) {
                if (gameOver || currentTurn != timedOutTurn) return;

                broadcast("⏱  Time's up! " + currentPlayer().name()
                        + " forfeits their turn.");
                advanceTurn();
                notifyTurn();
                scheduleTurnTimeout();
            }
        }, turnTimeoutSecs, TimeUnit.SECONDS);
    }

    private void advanceTurn() {
        currentTurn = (currentTurn + 1) % players.size();
    }


    public void broadcast(String message) {
        players.forEach(p -> p.send(message));
    }

    private void notifyTurn() {
        var current = currentPlayer();
        current.send("\n  ▶  Your turn, %s! Enter your 4-digit guess:".formatted(current.name()));
        players.stream()
                .filter(p -> !p.equals(current))
                .forEach(p -> p.send(
                        "\n  ⏳  Waiting for %s to guess…".formatted(current.name())));
    }

    private Player currentPlayer() {
        return players.get(currentTurn);
    }

    private String playerNames() {
        var sb = new StringBuilder();
        for (int i = 0; i < players.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(players.get(i).name());
        }
        return sb.toString();
    }

    public boolean isGameOver()     { return gameOver; }
    public int     getCurrentTurn() { return currentTurn; }
}
