package org.example;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class GameAnalytics {
    private final AtomicInteger totalGames   = new AtomicInteger(0);
    private final AtomicInteger totalWins    = new AtomicInteger(0);
    private final AtomicInteger totalLosses  = new AtomicInteger(0);
    private final AtomicInteger totalGuesses = new AtomicInteger(0);

    // secretCode → total guesses needed across all games where this code appeared
    private final ConcurrentHashMap<String, AtomicInteger> codeGuessCounts
            = new ConcurrentHashMap<>();

    public void recordGame(String secretCode, boolean won, int guessCount) {
        totalGames.incrementAndGet();
        totalGuesses.addAndGet(guessCount);

        if (won) totalWins.incrementAndGet();
        else     totalLosses.incrementAndGet();

        codeGuessCounts
                .computeIfAbsent(secretCode, k -> new AtomicInteger(0))
                .addAndGet(guessCount);
    }

    public String getReport() {
        int games   = totalGames.get();
        int wins    = totalWins.get();
        int losses  = totalLosses.get();
        int guesses = totalGuesses.get();
        double avgGuesses = games == 0 ? 0.0 : (double) guesses / games;

        var hardest = getHardestCodes(5);
        var hardestStr = hardest.isEmpty()
                ? "  (no data yet)"
                : hardest.entrySet().stream()
                .map(e -> "    %-6s → %d guesses".formatted(e.getKey(), e.getValue()))
                .collect(Collectors.joining("\n"));

        return """
                ╔══════════════════════════════════════════╗
                ║           SERVER ANALYTICS               ║
                ╚══════════════════════════════════════════╝
                  Total games played : %d
                  Wins               : %d
                  Losses             : %d
                  Total guesses      : %d
                  Avg guesses/game   : %.1f
 
                  Top hardest codes (most guesses needed):
                %s
                ══════════════════════════════════════════════"""
                .formatted(games, wins, losses, guesses, avgGuesses, hardestStr);
    }

    /**
     * Returns the top N hardest codes sorted by total guesses descending.
     */
    public LinkedHashMap<String, Integer> getHardestCodes(int topN) {
        return codeGuessCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue().get() - a.getValue().get())
                .limit(topN)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().get(),
                        (x, y) -> x,
                        LinkedHashMap::new
                ));
    }

    // Simple getters for tests
    public int getTotalGames()   { return totalGames.get(); }
    public int getTotalWins()    { return totalWins.get(); }
    public int getTotalLosses()  { return totalLosses.get(); }
    public int getTotalGuesses() { return totalGuesses.get(); }
}
