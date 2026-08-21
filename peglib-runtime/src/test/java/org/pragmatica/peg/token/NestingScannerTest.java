package org.pragmatica.peg.token;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.7.3 — the depth counter behind {@code %nest}.
 *
 * <p>These are deliberately unit tests of {@link NestingScanner#scanEnd} alone, separate from
 * the end-to-end lexing tests. The scanner is the one piece of this feature whose behaviour is
 * fully determined by its arguments, and the edge cases that decide whether a comment is read
 * correctly — {@code /**&#47;}, {@code /*&#47;}, an unbalanced tail — are far easier to pin
 * here than through a grammar, where a wrong answer shows up as a token count several layers
 * downstream.
 */
class NestingScannerTest {
    private static final String OPEN = "/*";
    private static final String CLOSE = "*/";

    private static int scan(String input) {
        return NestingScanner.scanEnd(input, 0, OPEN, CLOSE);
    }

    @Test
    void plainBlockClosesAtItsOwnDelimiter() {
        assertThat(scan("/* plain */")).isEqualTo(11);
    }

    @Test
    void nestedBlockClosesAtTheOuterDelimiter() {
        // The whole point of the ticket: the FIRST close belongs to the inner comment, and a
        // scanner that stops there leaks " still a comment */" into the token stream.
        var input = "/* outer /* inner */ still a comment */";

        assertThat(scan(input)).isEqualTo(input.length());
    }

    @Test
    void depthIsCountedNotJustDetected() {
        // Three opens need three closes. A scanner that merely noticed nesting and skipped to
        // the LAST close would also pass the two-level case above; this one separates them,
        // because here the last close is not the right close.
        var input = "/* a /* b /* c */ d */ e */ tail";

        assertThat(scan(input)).isEqualTo(input.indexOf(" tail"));
    }

    @Test
    void emptyBlockIsCompleteAtDepthOne() {
        // '/**/' is open + close with nothing between, not an unterminated '/*' followed by '*/'.
        assertThat(scan("/**/")).isEqualTo(4);
    }

    @Test
    void openSlashCloseIsUnterminated() {
        // '/*/' shares its middle character between the two delimiters. Testing open before
        // close consumes '/*' and leaves a bare '/', which cannot close anything.
        assertThat(scan("/*/")).isEqualTo(NestingScanner.UNTERMINATED);
    }

    @Test
    void unbalancedTailIsUnterminated() {
        assertThat(scan("/* outer /* inner */")).isEqualTo(NestingScanner.UNTERMINATED);
    }

    @Test
    void neverClosedIsUnterminated() {
        assertThat(scan("/* to the end of input")).isEqualTo(NestingScanner.UNTERMINATED);
    }

    @Test
    void scansFromTheGivenOffsetNotFromZero() {
        var input = "prefix /* a /* b */ c */ suffix";

        assertThat(NestingScanner.scanEnd(input, 7, OPEN, CLOSE)).isEqualTo(input.indexOf(" suffix"));
    }

    @Test
    void multiCharacterDelimitersOfDifferentLengths() {
        // Haskell's {- -} are the same length; ML's (* *) too. Use a deliberately lopsided pair
        // so a scanner that advanced by the wrong delimiter's length would land mid-token.
        var input = "<<< a <<< b >> c >> tail";

        assertThat(NestingScanner.scanEnd(input, 0, "<<<", ">>")).isEqualTo(input.indexOf(" tail"));
    }

    @Test
    void emptyDelimiterIsRefusedRatherThanHanging() {
        // An empty open matches at every position and advances by zero. GrammarParser rejects
        // it, so this is unreachable from a parsed grammar — but a hang is a far worse failure
        // than a refused scan, and this asserts the guard rather than the absence of a hang.
        assertThat(NestingScanner.scanEnd("anything", 0, "", CLOSE)).isEqualTo(NestingScanner.UNTERMINATED);
        assertThat(NestingScanner.scanEnd("anything", 0, OPEN, "")).isEqualTo(NestingScanner.UNTERMINATED);
    }
}
