package org.example;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Core game logic: secret code generation, validation, guess checking.
 */
public class GameLogic {

    private Supplier<Integer> randomSupplier;

    public GameLogic() {
        var rng = new Random();
        this.randomSupplier = () -> 1000 + rng.nextInt(9000);
    }

    public void setRandomSupplier(Supplier<Integer> supplier) {
        this.randomSupplier = supplier;
    }

    // ── Secret Code Generation ───────────────────────────────────────────────

    public String generateSecretCode() {
        return applyRules(randomSupplier.get());
    }

    private String applyRules(int raw) {
        int[] digits = toDigits(raw);
        int sum = 0;
        for (int d : digits) sum += d;

        if (sum % 2 == 0) {
            digits = reverse(digits);
        } else {
            for (int i = 0; i < digits.length; i++) {
                digits[i] = (digits[i] + 1) % 10;
            }
        }

        String result = digitsToString(digits);
        return isPalindrome(result) ? "7777" : result;
    }

    // ── Validate Guess ───────────────────────────────────────────────────────

    public String validateGuess(String guess) {

        if (guess == null) throw new IllegalArgumentException("Guess must not be null.");
        String trimmed = guess.trim();
        if (trimmed.length() != 4)
            throw new IllegalArgumentException("Guess must be exactly 4 digits.");
        for (char c : trimmed.toCharArray())
            if (!Character.isDigit(c))
                throw new IllegalArgumentException("Guess must contain only digits.");
        return trimmed;
    }

    // ── Check Guess ──────────────────────────────────────────────────────────

    public record GuessResult(int correctPosition, int wrongPosition, boolean isWin) {
        @Override
        public String toString() {
            return "Correct position: %d | Wrong position: %d"
                    .formatted(correctPosition, wrongPosition);
        }
    }

    public GuessResult checkGuess(String secret, String guess) {
        int correct = 0, wrong = 0;
        boolean[] secretUsed = new boolean[4];
        boolean[] guessUsed  = new boolean[4];

        for (int i = 0; i < 4; i++) {
            if (secret.charAt(i) == guess.charAt(i)) {
                correct++;
                secretUsed[i] = guessUsed[i] = true;
            }
        }
        for (int i = 0; i < 4; i++) {
            if (guessUsed[i]) continue;
            for (int j = 0; j < 4; j++) {
                if (!secretUsed[j] && guess.charAt(i) == secret.charAt(j)) {
                    wrong++;
                    secretUsed[j] = guessUsed[i] = true;
                    break;
                }
            }
        }
        return new GuessResult(correct, wrong, correct == 4);
    }

    // ── Timestamp ────────────────────────────────────────────────────────────

    public String generateTimestampPrefix() {
        return "[" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "]";
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private int[] toDigits(int n) {
        return new int[]{ n / 1000, (n / 100) % 10, (n / 10) % 10, n % 10 };
    }

    private int[] reverse(int[] arr) {
        int[] r = new int[arr.length];
        for (int i = 0; i < arr.length; i++) r[i] = arr[arr.length - 1 - i];
        return r;
    }

    private String digitsToString(int[] digits) {
        var sb = new StringBuilder();
        for (int d : digits) sb.append(d);
        return sb.toString();
    }

    private boolean isPalindrome(String s) {
        return s.equals(new StringBuilder(s).reverse().toString());
    }
}
