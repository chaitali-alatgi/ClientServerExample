package org.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GameLogic Tests")
public class GameLogicTest {
    private GameLogic logic;

    @BeforeEach
    void setUp() {
        logic = new GameLogic();
    }

    @Nested
    @DisplayName("validateGuess()")
    class ValidateGuessTests {

        @Test
        @DisplayName("Valid 4-digit string passes through unchanged")
        void valid_4digit_passes() {
            assertEquals("1234", logic.validateGuess("1234"));
        }

        @Test
        @DisplayName("Leading and trailing whitespace is trimmed")
        void trims_whitespace() {
            assertEquals("9931", logic.validateGuess(" 9931"));
            assertEquals("1000", logic.validateGuess("1000 "));
            assertEquals("5678", logic.validateGuess("  5678  "));
        }

        @Test
        @DisplayName("All zeros is valid")
        void all_zeros_valid() {
            assertEquals("0000", logic.validateGuess("0000"));
        }

        @Test
        @DisplayName("All nines is valid")
        void all_nines_valid() {
            assertEquals("9999", logic.validateGuess("9999"));
        }
        @Test
        @DisplayName("Null input throws IllegalArgumentException")
        void null_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> logic.validateGuess(null));
        }

        @Test
        @DisplayName("Empty string throws IllegalArgumentException")
        void empty_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> logic.validateGuess(""));
        }

        @Test
        @DisplayName("Blank string (spaces only) throws IllegalArgumentException")
        void blank_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> logic.validateGuess("    "));
        }

        @ParameterizedTest
        @ValueSource(strings = {"123", "12345", "123456"})
        @DisplayName("Wrong length throws IllegalArgumentException")
        void wrong_length_throws(String input) {
            assertThrows(IllegalArgumentException.class,
                    () -> logic.validateGuess(input));
        }

        @ParameterizedTest
        @ValueSource(strings = {"abcd", "12ab", "!234", "12.4", "12 4"})
        @DisplayName("Non-digit characters throw IllegalArgumentException")
        void non_digits_throw(String input) {
            assertThrows(IllegalArgumentException.class,
                    () -> logic.validateGuess(input));
        }
    }

    @Nested
    @DisplayName("generateSecretCode()")
    class GenerateSecretCodeTests {

        /**
         * Helper: override the random supplier with a fixed value, generate code.
         */
        private String generate(int raw) {
            logic.setRandomSupplier(() -> raw);
            return logic.generateSecretCode();
        }

        @Test
        @DisplayName("Even digit sum → digits are reversed")
        void even_sum_reverses() {
            // 1234: 1+2+3+4 = 10 (even) → reverse → "4321"
            // "4321" is not a palindrome → final = "4321"
            assertEquals("4321", generate(1234));
        }

        @Test
        @DisplayName("Odd digit sum → each digit incremented by 1")
        void odd_sum_increments() {
            // 1235: 1+2+3+5 = 11 (odd) → each +1 → 2346
            assertEquals("2346", generate(1235));
        }

        @Test
        @DisplayName("Digit 9 wraps to 0 on increment")
        void digit_9_wraps_to_0() {
            // 1299: 1+2+9+9 = 21 (odd) → each+1 → 2,3,0,0 → "2300"
            assertEquals("2300", generate(1299));
        }

        @Test
        @DisplayName("Palindrome result is replaced with 7777")
        void palindrome_replaced_with_7777() {
            // 1221: 1+2+2+1 = 6 (even) → reverse → "1221" → palindrome → "7777"
            assertEquals("7777", generate(1221));
        }

        @Test
        @DisplayName("9999 (even sum) reverses to 9999 (palindrome) → 7777")
        void all_nines_becomes_7777() {
            // 9+9+9+9 = 36 (even) → reverse "9999" → palindrome → "7777"
            assertEquals("7777", generate(9999));
        }

        @Test
        @DisplayName("Result is always exactly 4 characters")
        void always_4_chars() {
            int[] raws = {1000, 1111, 2345, 5678, 9990};
            for (int raw : raws) {
                String code = generate(raw);
                assertEquals(4, code.length(),
                        "Expected 4 chars for raw=" + raw + " but got: " + code);
            }
        }

        @Test
        @DisplayName("Result contains only digit characters")
        void result_only_digits() {
            int[] raws = {1000, 2345, 6789, 9999};
            for (int raw : raws) {
                String code = generate(raw);
                assertTrue(code.matches("\\d{4}"),
                        "Expected all digits in: " + code);
            }
        }

        @Test
        @DisplayName("Non-palindrome even-sum result is NOT replaced")
        void non_palindrome_even_sum_not_replaced() {
            // 1234: even sum → reverse "4321" → not palindrome → stays "4321"
            String code = generate(1234);
            assertNotEquals("7777", code);
            assertEquals("4321", code);
        }
    }

    @Nested
    @DisplayName("checkGuess()")
    class CheckGuessTests {

        @Test
        @DisplayName("All 4 digits in correct position → isWin = true")
        void all_correct_position_is_win() {
            var result = logic.checkGuess("1234", "1234");
            assertEquals(4, result.correctPosition());
            assertEquals(0, result.wrongPosition());
            assertTrue(result.isWin());
        }

        @Test
        @DisplayName("No matching digits → 0 correct, 0 wrong")
        void no_match() {
            var result = logic.checkGuess("1234", "5678");
            assertEquals(0, result.correctPosition());
            assertEquals(0, result.wrongPosition());
            assertFalse(result.isWin());
        }

        @Test
        @DisplayName("All digits present but all in wrong position")
        void all_wrong_position() {
            // Secret "1234", Guess "4321": all digits exist, none in right place
            var result = logic.checkGuess("1234", "4321");
            assertEquals(0, result.correctPosition());
            assertEquals(4, result.wrongPosition());
            assertFalse(result.isWin());
        }

        @Test
        @DisplayName("Mixed: some correct position, some wrong position")
        void mixed_correct_and_wrong() {
            // Secret "1234", Guess "1243"
            // pos0: 1==1 ✓, pos1: 2==2 ✓, pos2: 4≠3 but 4 is in secret, pos3: 3≠4 but 3 is in secret
            var result = logic.checkGuess("1234", "1243");
            assertEquals(2, result.correctPosition());
            assertEquals(2, result.wrongPosition());
        }

        @Test
        @DisplayName("Duplicate guess digits handled correctly — no over-counting")
        void duplicate_guess_no_overcounting() {
            // Secret "1123", Guess "1111"
            // pos0: 1==1 ✓, pos1: 1==1 ✓
            // Remaining secret: [2,3], remaining guess: [1,1] → no wrong-pos matches
            var result = logic.checkGuess("1123", "1111");
            assertEquals(2, result.correctPosition());
            assertEquals(0, result.wrongPosition());
        }

        @Test
        @DisplayName("Duplicate secret digits handled correctly")
        void duplicate_secret_digits() {
            // Secret "1122", Guess "2211"
            // No exact matches; digits: 1,2 present twice each → 4 wrong-pos
            var result = logic.checkGuess("1122", "2211");
            assertEquals(0, result.correctPosition());
            assertEquals(4, result.wrongPosition());
        }

        @Test
        @DisplayName("One correct position, one wrong position")
        void one_correct_one_wrong() {
            // Secret "1234", Guess "1342"
            // pos0: 1==1 ✓; pos1: 3≠2 (3 is in secret at pos2); pos2: 4≠3 (4 is at pos3); pos3: 2≠4
            var result = logic.checkGuess("1234", "1342");
            assertEquals(1, result.correctPosition());
            assertEquals(2, result.wrongPosition());
        }

        @Test
        @DisplayName("toString() contains correct and wrong position counts")
        void tostring_contains_counts() {
            var result = logic.checkGuess("1234", "1256");
            String str = result.toString();
            assertTrue(str.contains("Correct position: 2"));
            assertTrue(str.contains("Wrong position: 0"));
        }

        @ParameterizedTest
        @CsvSource({
                "1234, 1234, 4, 0",   // all correct
                "1234, 5678, 0, 0",   // no match
                "1234, 1000, 1, 0",   // one correct pos only
                "1234, 2134, 2, 2",   // 2 correct pos, 2 wrong pos
        })
        @DisplayName("Parameterized: various secret/guess combinations")
        void parameterized_guess_check(String secret, String guess,
                                       int expectedCorrect, int expectedWrong) {
            var result = logic.checkGuess(secret, guess);
            assertEquals(expectedCorrect, result.correctPosition(),
                    "correctPosition mismatch for guess=" + guess);
            assertEquals(expectedWrong, result.wrongPosition(),
                    "wrongPosition mismatch for guess=" + guess);
        }
    }

    @Nested
    @DisplayName("generateTimestampPrefix()")
    class TimestampPrefixTests {

        @Test
        @DisplayName("Returns non-null string")
        void not_null() {
            assertNotNull(logic.generateTimestampPrefix());
        }

        @Test
        @DisplayName("Format matches [HH:mm:ss]")
        void format_matches() {
            String prefix = logic.generateTimestampPrefix();
            assertTrue(prefix.matches("\\[\\d{2}:\\d{2}:\\d{2}]"),
                    "Expected [HH:mm:ss] format, got: " + prefix);
        }

        @Test
        @DisplayName("Starts with '[' and ends with ']'")
        void wrapped_in_brackets() {
            String prefix = logic.generateTimestampPrefix();
            assertTrue(prefix.startsWith("["));
            assertTrue(prefix.endsWith("]"));
        }

        @Test
        @DisplayName("Two consecutive calls both return valid format")
        void multiple_calls_valid() {
            String p1 = logic.generateTimestampPrefix();
            String p2 = logic.generateTimestampPrefix();
            assertTrue(p1.matches("\\[\\d{2}:\\d{2}:\\d{2}]"));
            assertTrue(p2.matches("\\[\\d{2}:\\d{2}:\\d{2}]"));
        }
    }

    }
