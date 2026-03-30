package org.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GameAnalytics Tests")
class GameAnalyticsTest {

    private GameAnalytics analytics;

    @BeforeEach
    void setUp() {
        analytics = new GameAnalytics();
    }

    @Nested
    @DisplayName("Initial state")
    class InitialStateTests {

        @Test
        @DisplayName("All counters start at zero")
        void all_counters_zero() {
            assertEquals(0, analytics.getTotalGames());
            assertEquals(0, analytics.getTotalWins());
            assertEquals(0, analytics.getTotalLosses());
            assertEquals(0, analytics.getTotalGuesses());
        }

        @Test
        @DisplayName("getHardestCodes returns empty map initially")
        void hardest_codes_empty_initially() {
            assertTrue(analytics.getHardestCodes(5).isEmpty());
        }

        @Test
        @DisplayName("Report contains 'no data yet' when no games recorded")
        void report_shows_no_data() {
            String report = analytics.getReport();
            assertTrue(report.contains("no data yet"));
        }
    }

    @Nested
    @DisplayName("recordGame()")
    class RecordGameTests {

        @Test
        @DisplayName("Recording a win increments totalGames and totalWins")
        void record_win_increments_wins() {
            analytics.recordGame("1234", true, 3);
            assertEquals(1, analytics.getTotalGames());
            assertEquals(1, analytics.getTotalWins());
            assertEquals(0, analytics.getTotalLosses());
            assertEquals(3, analytics.getTotalGuesses());
        }

        @Test
        @DisplayName("Recording a loss increments totalGames and totalLosses")
        void record_loss_increments_losses() {
            analytics.recordGame("5678", false, 10);
            assertEquals(1, analytics.getTotalGames());
            assertEquals(0, analytics.getTotalWins());
            assertEquals(1, analytics.getTotalLosses());
            assertEquals(10, analytics.getTotalGuesses());
        }

        @Test
        @DisplayName("Multiple games accumulate correctly")
        void multiple_games_accumulate() {
            analytics.recordGame("1111", true, 2);
            analytics.recordGame("2222", true, 5);
            analytics.recordGame("3333", false, 10);

            assertEquals(3, analytics.getTotalGames());
            assertEquals(2, analytics.getTotalWins());
            assertEquals(1, analytics.getTotalLosses());
            assertEquals(17, analytics.getTotalGuesses());
        }

        @Test
        @DisplayName("Same secret code across multiple games accumulates guess count")
        void same_code_accumulates_guesses() {
            analytics.recordGame("1234", true, 3);
            analytics.recordGame("1234", false, 10);

            var hardest = analytics.getHardestCodes(1);
            assertEquals(1, hardest.size());
            assertEquals(13, hardest.get("1234")); // 3 + 10 = 13
        }

        @Test
        @DisplayName("Zero guesses recorded correctly")
        void zero_guesses_recorded() {
            analytics.recordGame("0000", true, 0);
            assertEquals(0, analytics.getTotalGuesses());
            assertEquals(1, analytics.getTotalGames());
        }
    }

    @Nested
    @DisplayName("getHardestCodes()")
    class HardestCodesTests {

        @Test
        @DisplayName("Returns codes sorted by guess count descending")
        void sorted_by_guess_count_descending() {
            analytics.recordGame("1111", true, 2);
            analytics.recordGame("2222", true, 8);
            analytics.recordGame("3333", true, 5);

            var hardest = analytics.getHardestCodes(3);
            var keys = hardest.keySet().toArray(new String[0]);

            assertEquals("2222", keys[0]); // 8 guesses — hardest
            assertEquals("3333", keys[1]); // 5 guesses
            assertEquals("1111", keys[2]); // 2 guesses — easiest
        }

        @Test
        @DisplayName("Limits result to topN entries")
        void limits_to_top_n() {
            analytics.recordGame("1111", true, 1);
            analytics.recordGame("2222", true, 2);
            analytics.recordGame("3333", true, 3);
            analytics.recordGame("4444", true, 4);
            analytics.recordGame("5555", true, 5);

            var hardest = analytics.getHardestCodes(3);
            assertEquals(3, hardest.size());
        }

        @Test
        @DisplayName("topN larger than available codes returns all codes")
        void top_n_larger_than_available() {
            analytics.recordGame("1234", true, 5);
            analytics.recordGame("5678", true, 3);

            var hardest = analytics.getHardestCodes(10);
            assertEquals(2, hardest.size());
        }

        @Test
        @DisplayName("Returns empty map when no games recorded")
        void empty_when_no_games() {
            assertTrue(analytics.getHardestCodes(5).isEmpty());
        }

        @Test
        @DisplayName("topN = 1 returns only the single hardest code")
        void top_1_returns_hardest_only() {
            analytics.recordGame("1111", true, 3);
            analytics.recordGame("2222", true, 9);
            analytics.recordGame("3333", true, 6);

            var hardest = analytics.getHardestCodes(1);
            assertEquals(1, hardest.size());
            assertTrue(hardest.containsKey("2222"));
        }
    }

    @Nested
    @DisplayName("getReport()")
    class GetReportTests {

        @Test
        @DisplayName("Report contains total games count")
        void report_contains_total_games() {
            analytics.recordGame("1234", true, 4);
            analytics.recordGame("5678", false, 10);
            assertTrue(analytics.getReport().contains("2"));
        }

        @Test
        @DisplayName("Report contains wins and losses")
        void report_contains_wins_losses() {
            analytics.recordGame("1234", true, 4);
            analytics.recordGame("5678", false, 7);
            String report = analytics.getReport();
            assertTrue(report.contains("Wins"));
            assertTrue(report.contains("Losses"));
        }

        @Test
        @DisplayName("Report contains average guesses per game")
        void report_contains_avg_guesses() {
            analytics.recordGame("1234", true, 4);
            analytics.recordGame("5678", true, 6);
            // avg = 10/2 = 5.0
            String report = analytics.getReport();
            assertTrue(report.contains("5.0"));
        }

        @Test
        @DisplayName("Report contains header banner")
        void report_contains_banner() {
            String report = analytics.getReport();
            assertTrue(report.contains("ANALYTICS"));
        }

        @Test
        @DisplayName("Report contains hardest code after games recorded")
        void report_contains_hardest_code() {
            analytics.recordGame("9876", true, 12);
            String report = analytics.getReport();
            assertTrue(report.contains("9876"));
        }

        @Test
        @DisplayName("Average is 0.0 when no games recorded")
        void avg_zero_when_no_games() {
            String report = analytics.getReport();
            assertTrue(report.contains("0.0"));
        }
    }

    @Nested
    @DisplayName("Thread safety")
    class ThreadSafetyTests {

        @Test
        @DisplayName("Concurrent recordGame calls produce correct totals")
        void concurrent_record_game() throws InterruptedException {
            int threadCount = 50;
            var threads = new Thread[threadCount];

            for (int i = 0; i < threadCount; i++) {
                final boolean won = i % 2 == 0;
                threads[i] = new Thread(() ->
                        analytics.recordGame("1234", won, 5));
                threads[i].start();
            }
            for (Thread t : threads) t.join();

            assertEquals(threadCount, analytics.getTotalGames());
            assertEquals(threadCount * 5, analytics.getTotalGuesses());
            assertEquals(threadCount / 2, analytics.getTotalWins());
            assertEquals(threadCount / 2, analytics.getTotalLosses());
        }
    }
}
