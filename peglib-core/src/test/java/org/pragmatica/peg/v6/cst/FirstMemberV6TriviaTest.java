package org.pragmatica.peg.v6.cst;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.v6.PegParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.6.1 — independent verification that the v6 generated-parser path
 * ({@link CstArray#leadingTriviaTokens(int)}) does not exhibit the
 * first-member trivia-loss bug described in
 * {@code docs/bugs/first-member-trivia-loss-2026-05-12.md}.
 *
 * <p>The v6 attribution walks tokens backward from a node's first content
 * token, accumulating consecutive trivia tokens until a non-trivia token
 * is found. The opening delimiter ({@code &#123;}) is itself a content
 * token, so the walk terminates there and the intervening trivia (whitespace
 * and {@code //} or {@code ///} comments) is correctly attributed to the
 * first child.
 */
class FirstMemberV6TriviaTest {
    private static final String GRAMMAR = """
        Container <- '{' Member* '}'
        Member <- '@' Name ';'
        Name <- < [a-z]+ >
        %whitespace <- ([ \\t\\r\\n]+ / '//' [^\\n]* '\\n'?)+
        """;

    @Test
    void firstMemberV6_inheritsLineCommentAfterOpeningDelimiter() {
        var parser = PegParser.fromGrammar(GRAMMAR).unwrap();
        var input = "{ // doc on first field\n  @x;\n}\n";
        var result = parser.parse(input);

        assertThat(result.isSuccess())
            .as("parse must succeed; diagnostics: %s", result.diagnostics())
            .isTrue();

        var cst = result.cst();
        // root is _ROOT synthetic wrapper; descend to find first Member.
        int memberIdx = findFirstByKindName(cst, 0, "Member");
        assertThat(memberIdx).as("must find a Member node").isGreaterThanOrEqualTo(0);

        var leadingText = cst.leadingTriviaText(memberIdx).toString();
        assertThat(leadingText)
            .as("first Member's leadingTrivia text should contain the // doc line comment")
            .contains("// doc on first field");
    }

    @Test
    void firstMemberV6_inheritsDocLineCommentAfterOpeningDelimiter() {
        var parser = PegParser.fromGrammar(GRAMMAR).unwrap();
        var input = "{ /// doc on first field\n  @x;\n}\n";
        var result = parser.parse(input);

        assertThat(result.isSuccess())
            .as("parse must succeed; diagnostics: %s", result.diagnostics())
            .isTrue();

        var cst = result.cst();
        int memberIdx = findFirstByKindName(cst, 0, "Member");
        assertThat(memberIdx).as("must find a Member node").isGreaterThanOrEqualTo(0);

        var leadingText = cst.leadingTriviaText(memberIdx).toString();
        assertThat(leadingText)
            .as("first Member's leadingTrivia text should contain the /// doc line comment")
            .contains("/// doc on first field");
    }

    private static int findFirstByKindName(CstArray cst, int root, String name) {
        var iter = cst.descendants(root).iterator();
        while (iter.hasNext()) {
            int idx = iter.nextInt();
            if (name.equals(cst.kindNameAt(idx))) {
                return idx;
            }
        }
        return -1;
    }
}
