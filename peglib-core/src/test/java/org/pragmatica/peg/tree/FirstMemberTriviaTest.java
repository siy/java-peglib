package org.pragmatica.peg.tree;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.grammar.GrammarParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.6.1 — regression test for the first-member trivia-loss bug documented in
 * {@code docs/bugs/first-member-trivia-loss-2026-05-12.md}.
 *
 * <p>{@link TriviaPostPass#rebuildNonTerminal} previously initialised its scan
 * cursor at {@code spanStart} (the offset of the parent's opening delimiter,
 * e.g. {@code &#123;}). Because the character at {@code spanStart} is the
 * delimiter itself and not whitespace, {@code scanWhitespaceFast} failed on its
 * first probe and returned an empty list — silently dropping any trivia
 * (whitespace + comments) between the opening delimiter and the first child.
 *
 * <p>The fix advances {@code cursor} past any non-whitespace-startable prefix
 * of the gap so the trivia scan can begin where the {@code %whitespace} rule
 * can actually match.
 */
class FirstMemberTriviaTest {
    private static final String GRAMMAR = """
        Container <- '{' Member* '}'
        Member <- 'int' Identifier ';'
        Identifier <- < [a-z]+ >
        %whitespace <- ([ \\t\\r\\n]+ / '//' [^\\n]* '\\n'?)+
        """;

    private static String triviaText(java.util.List<Trivia> list) {
        var sb = new StringBuilder();
        for (var t : list) sb.append(t.text());
        return sb.toString();
    }

    private static CstNode firstMember(CstNode root) {
        var container = (CstNode.NonTerminal) root;
        for (var c : container.children()) {
            if (c instanceof CstNode.NonTerminal child && "Member".equals(child.rule())) {
                return child;
            }
        }
        throw new AssertionError("no Member child under Container");
    }

    @Test
    void firstMember_inheritsLeadingLineCommentAfterOpeningDelimiter() {
        var grammar = GrammarParser.parse(GRAMMAR).unwrap();
        var parser = PegParser.fromGrammar(GRAMMAR).unwrap();
        var input = "{ // doc on first field\n  int x;\n}\n";

        var cst = parser.parseCst(input).unwrap();
        var rebuilt = TriviaPostPass.assignTrivia(input, cst, grammar);

        var member = firstMember(rebuilt);

        assertThat(member.leadingTrivia())
            .as("first Member's leadingTrivia should contain the // doc line comment")
            .anyMatch(t -> t instanceof Trivia.LineComment lc
                           && lc.text().contains("doc on first field"));
    }

    @Test
    void firstMember_inheritsWhitespaceOnlyTriviaAfterOpeningDelimiter() {
        var grammar = GrammarParser.parse(GRAMMAR).unwrap();
        var parser = PegParser.fromGrammar(GRAMMAR).unwrap();
        var input = "{\n  int x;\n}\n";

        var cst = parser.parseCst(input).unwrap();
        var rebuilt = TriviaPostPass.assignTrivia(input, cst, grammar);

        var member = firstMember(rebuilt);

        assertThat(triviaText(member.leadingTrivia()))
            .as("first Member's leadingTrivia should include the newline+indent between { and int")
            .contains("\n  ");
    }

    @Test
    void firstMember_inheritsMultipleCommentsAfterOpeningDelimiter() {
        var grammar = GrammarParser.parse(GRAMMAR).unwrap();
        var parser = PegParser.fromGrammar(GRAMMAR).unwrap();
        var input = "{ // first doc line\n  // second doc line\n  int x;\n}\n";

        var cst = parser.parseCst(input).unwrap();
        var rebuilt = TriviaPostPass.assignTrivia(input, cst, grammar);

        var member = firstMember(rebuilt);
        long lineCommentCount = member.leadingTrivia()
                                       .stream()
                                       .filter(t -> t instanceof Trivia.LineComment)
                                       .count();
        assertThat(lineCommentCount)
            .as("first Member's leadingTrivia should contain both // doc lines")
            .isEqualTo(2);
    }
}
