package org.pragmatica.peg.v6.token;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.peg.v6.token.TokenArray.FIRST_USER_KIND;
import static org.pragmatica.peg.v6.token.TokenArray.KIND_BLOCK_COMMENT;
import static org.pragmatica.peg.v6.token.TokenArray.KIND_LINE_COMMENT;
import static org.pragmatica.peg.v6.token.TokenArray.KIND_WHITESPACE;

class TokenArrayTest {
    private static final int IDENT = FIRST_USER_KIND;
    private static final int NUMBER = FIRST_USER_KIND + 1;

    private static final String[] DEFAULT_NAMES = {
        "WHITESPACE", "LINE_COMMENT", "BLOCK_COMMENT", "DOC_LINE_COMMENT", "DOC_BLOCK_COMMENT", "IDENT", "NUMBER"
    };

    @Test
    void emptyArray_countZero_nextNonTriviaFromZeroIsZero() {
        var array = new TokenArrayBuilder("").build(DEFAULT_NAMES);
        assertThat(array.count())
        .isZero();
        assertThat(array.nextNonTrivia(0))
        .isZero();
        assertThat(array.input())
        .isEmpty();
    }

    @Test
    void singleTriviaToken_isTriviaTrue_textRoundTrips_nextNonTriviaSkips() {
        var input = "   ";
        var b = new TokenArrayBuilder(input);
        b.append(KIND_WHITESPACE, 0, 3);
        var array = b.build(DEFAULT_NAMES);
        assertThat(array.count())
        .isEqualTo(1);
        assertThat(array.isTrivia(0))
        .isTrue();
        assertThat(array.textAt(0)
                        .toString())
        .isEqualTo("   ");
        assertThat(array.nextNonTrivia(0))
        .isEqualTo(1);
        assertThat(array.kindName(0))
        .isEqualTo("WHITESPACE");
    }

    @Test
    void mixedSequence_nextNonTriviaWalksCorrectly() {
        var input = " abc 42 ";
        var b = new TokenArrayBuilder(input);
        b.append(KIND_WHITESPACE, 0, 1);
        b.append(IDENT, 1, 4);
        b.append(KIND_WHITESPACE, 4, 5);
        b.append(NUMBER, 5, 7);
        b.append(KIND_WHITESPACE, 7, 8);
        var array = b.build(DEFAULT_NAMES);
        assertThat(array.count())
        .isEqualTo(5);
        assertThat(array.nextNonTrivia(0))
        .isEqualTo(1);
        assertThat(array.nextNonTrivia(1))
        .isEqualTo(1);
        assertThat(array.nextNonTrivia(2))
        .isEqualTo(3);
        assertThat(array.nextNonTrivia(3))
        .isEqualTo(3);
        assertThat(array.nextNonTrivia(4))
        .isEqualTo(5);
        assertThat(array.nextNonTrivia(5))
        .isEqualTo(5);
    }

    @Test
    void nextNonTrivia_returnsCount_whenAllRemainingAreTrivia() {
        var input = "x   ";
        var b = new TokenArrayBuilder(input);
        b.append(IDENT, 0, 1);
        b.append(KIND_WHITESPACE, 1, 2);
        b.append(KIND_LINE_COMMENT, 2, 3);
        b.append(KIND_BLOCK_COMMENT, 3, 4);
        var array = b.build(DEFAULT_NAMES);
        assertThat(array.nextNonTrivia(1))
        .isEqualTo(array.count());
        assertThat(array.nextNonTrivia(4))
        .isEqualTo(array.count());
    }

    @Test
    void textAt_roundTripsToInput_byConcatenatingAllTokenSubsequences() {
        var input = "  foo /*c*/ 12\n";
        var b = new TokenArrayBuilder(input);
        b.append(KIND_WHITESPACE, 0, 2);
        b.append(IDENT, 2, 5);
        b.append(KIND_WHITESPACE, 5, 6);
        b.append(KIND_BLOCK_COMMENT, 6, 11);
        b.append(KIND_WHITESPACE, 11, 12);
        b.append(NUMBER, 12, 14);
        b.append(KIND_WHITESPACE, 14, 15);
        var array = b.build(DEFAULT_NAMES);
        var rebuilt = new StringBuilder();
        for (var i = 0; i < array.count(); i++ ) {
            rebuilt.append(array.textAt(i));
        }
        assertThat(rebuilt.toString())
        .isEqualTo(input);
        assertThat(array.textAt(1)
                        .toString())
        .isEqualTo("foo");
        assertThat(array.textAt(3)
                        .toString())
        .isEqualTo("/*c*/");
        assertThat(array.textAt(5)
                        .toString())
        .isEqualTo("12");
    }

    @Test
    void kindStartEnd_parityWithBuilderCalls() {
        var input = "ab12";
        var b = new TokenArrayBuilder(input);
        b.append(IDENT, 0, 2);
        b.append(NUMBER, 2, 4);
        var array = b.build(DEFAULT_NAMES);
        assertThat(array.kindAt(0))
        .isEqualTo(IDENT);
        assertThat(array.startAt(0))
        .isZero();
        assertThat(array.endAt(0))
        .isEqualTo(2);
        assertThat(array.kindAt(1))
        .isEqualTo(NUMBER);
        assertThat(array.startAt(1))
        .isEqualTo(2);
        assertThat(array.endAt(1))
        .isEqualTo(4);
        assertThat(array.kindName(1))
        .isEqualTo("NUMBER");
        assertThat(array.isTrivia(0))
        .isFalse();
        assertThat(array.isTrivia(1))
        .isFalse();
    }

    @Test
    void tokenAtEndOfInput_isValid() {
        var input = "x";
        var b = new TokenArrayBuilder(input);
        b.append(IDENT, 0, 1);
        b.append(KIND_WHITESPACE, 1, 1);
        var array = b.build(DEFAULT_NAMES);
        assertThat(array.endAt(1))
        .isEqualTo(input.length());
        assertThat(array.textAt(1)
                        .toString())
        .isEmpty();
    }

    @Test
    void buildIsSingleShot_isBuiltSetAfterFirstCall() {
        var b = new TokenArrayBuilder("a");
        b.append(IDENT, 0, 1);
        b.build(DEFAULT_NAMES);
        assertThat(b.isBuilt()).isTrue();
    }

    @Test
    void growth_handlesAppendsBeyondInitialCapacity() {
        var len = 1000;
        var input = "x".repeat(len);
        var b = new TokenArrayBuilder(input, 4);
        for (var i = 0; i < len; i++ ) {
            b.append(IDENT, i, i + 1);
        }
        var array = b.build(DEFAULT_NAMES);
        assertThat(array.count())
        .isEqualTo(len);
        assertThat(array.startAt(999))
        .isEqualTo(999);
        assertThat(array.endAt(999))
        .isEqualTo(1000);
    }

    @Test
    void kindNameTable_isCopied_so_externalMutationDoesNotLeak() {
        var names = DEFAULT_NAMES.clone();
        var b = new TokenArrayBuilder("a");
        b.append(IDENT, 0, 1);
        var array = b.build(names);
        names[IDENT] = "MUTATED";
        assertThat(array.kindName(0))
        .isEqualTo("IDENT");
    }

    @Test
    void nextNonTrivia_precomputedTable_matchesLinearScanReference() {
        // Mixed trivia/non-trivia layout including consecutive trivia runs.
        var input = "  ab // c\n  42  ";
        var b = new TokenArrayBuilder(input);
        b.append(KIND_WHITESPACE, 0, 2);
        b.append(IDENT, 2, 4);
        b.append(KIND_WHITESPACE, 4, 5);
        b.append(KIND_LINE_COMMENT, 5, 9);
        b.append(KIND_WHITESPACE, 9, 11);
        b.append(NUMBER, 11, 13);
        b.append(KIND_WHITESPACE, 13, 15);
        b.append(KIND_BLOCK_COMMENT, 15, 15);
        var array = b.build(DEFAULT_NAMES);
        // Reference: linear scan past trivia.
        for (var i = 0; i <= array.count(); i++ ) {
            var expected = i;
            while (expected < array.count() && array.isTrivia(expected)) {
                expected++ ;
            }
            assertThat(array.nextNonTrivia(i))
            .as("nextNonTrivia(%d)", i)
            .isEqualTo(expected);
        }
    }

    @Test
    void nextNonTrivia_atCount_returnsCount() {
        var b = new TokenArrayBuilder("abx");
        b.append(IDENT, 0, 2);
        b.append(KIND_WHITESPACE, 2, 3);
        var array = b.build(DEFAULT_NAMES);
        assertThat(array.nextNonTrivia(array.count()))
        .isEqualTo(array.count());
    }

    @Test
    void nextNonTrivia_atLastIndex_whenLastIsTrivia_returnsCount() {
        var b = new TokenArrayBuilder("ab ");
        b.append(IDENT, 0, 2);
        b.append(KIND_WHITESPACE, 2, 3);
        var array = b.build(DEFAULT_NAMES);
        assertThat(array.nextNonTrivia(array.count() - 1))
        .isEqualTo(array.count());
    }

    @Test
    void nextNonTrivia_atLastIndex_whenLastIsNonTrivia_returnsLastIndex() {
        var b = new TokenArrayBuilder(" ab");
        b.append(KIND_WHITESPACE, 0, 1);
        b.append(IDENT, 1, 3);
        var array = b.build(DEFAULT_NAMES);
        var last = array.count() - 1;
        assertThat(array.nextNonTrivia(last))
        .isEqualTo(last);
    }

    @Test
    void reservedKindConstants_haveExpectedValues() {
        assertThat(KIND_WHITESPACE)
        .isZero();
        assertThat(KIND_LINE_COMMENT)
        .isEqualTo(1);
        assertThat(KIND_BLOCK_COMMENT)
        .isEqualTo(2);
        assertThat(TokenArray.KIND_DOC_LINE_COMMENT)
        .isEqualTo(3);
        assertThat(TokenArray.KIND_DOC_BLOCK_COMMENT)
        .isEqualTo(4);
        assertThat(FIRST_USER_KIND)
        .isEqualTo(5);
    }
}
