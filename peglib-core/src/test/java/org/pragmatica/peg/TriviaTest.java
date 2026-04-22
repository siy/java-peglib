package org.pragmatica.peg;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.tree.CstNode;
import org.pragmatica.peg.tree.Trivia;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for trivia (whitespace/comments) handling in CST parsing.
 *
 * Design: Each match of the INNER whitespace expression creates one Trivia item.
 * - For `%whitespace <- [ \t]+`, inner is `[ \t]`, so each char is separate trivia
 * - For `%whitespace <- ([ \t]+ / Comment)+`, inner is `[ \t]+ / Comment`,
 *   so each whitespace-run or comment is separate trivia
 */
class TriviaTest {

    @Test
    void leadingWhitespace_eachCharacterIsSeparateTrivia() {
        // Inner expression is [ \t], so each space is separate
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- [ \\t]+
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        var result = parser.parseCst("  42");
        assertThat(result.isSuccess()).isTrue();

        var node = result.unwrap();
        assertThat(node.leadingTrivia()).hasSize(2);
        assertThat(node.leadingTrivia().get(0)).isInstanceOf(Trivia.Whitespace.class);
        assertThat(node.leadingTrivia().get(0).text()).isEqualTo(" ");
        assertThat(node.leadingTrivia().get(1).text()).isEqualTo(" ");
    }

    @Test
    void whitespaceChunks_withChoiceGrammar_groupedCorrectly() {
        // Inner expression is [ \t]+ / Comment, so each run is one trivia
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- ([ \\t]+ / '//' [^\\n]* '\\n')+
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        var result = parser.parseCst("  42");
        assertThat(result.isSuccess()).isTrue();

        var node = result.unwrap();
        // With choice, "  " is one whitespace chunk
        assertThat(node.leadingTrivia()).hasSize(1);
        assertThat(node.leadingTrivia().getFirst().text()).isEqualTo("  ");
    }

    @Test
    void noWhitespace_emptyTriviaList() {
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- [ \\t]+
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        var result = parser.parseCst("42");
        assertThat(result.isSuccess()).isTrue();

        var node = result.unwrap();
        assertThat(node.leadingTrivia()).isEmpty();
    }

    @Test
    void whitespaceInExpression_preserved() {
        var grammar = """
            Sum <- Number '+' Number
            Number <- < [0-9]+ >
            %whitespace <- [ ]+
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        var result = parser.parseCst("1 + 2");
        assertThat(result.isSuccess()).isTrue();

        var node = result.unwrap();
        // The Sum rule should have empty leading trivia (no leading space)
        assertThat(node.leadingTrivia()).isEmpty();

        // Check children have appropriate trivia
        if (node instanceof CstNode.NonTerminal nt) {
            assertThat(nt.children()).isNotEmpty();
        }
    }

    @Test
    void lineComment_capturedAsTrivia() {
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- ([ \\t]+ / '//' [^\\n]* '\\n')+
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        var result = parser.parseCst("// comment\n42");
        assertThat(result.isSuccess()).isTrue();

        var node = result.unwrap();
        assertThat(node.leadingTrivia()).hasSize(1);
        assertThat(node.leadingTrivia().getFirst()).isInstanceOf(Trivia.LineComment.class);
        assertThat(node.leadingTrivia().getFirst().text()).isEqualTo("// comment\n");
    }

    @Test
    void blockComment_capturedAsTrivia() {
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- ([ \\t\\n]+ / '/*' (!'*/' .)* '*/')+
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        var result = parser.parseCst("/* block */ 42");
        assertThat(result.isSuccess()).isTrue();

        var node = result.unwrap();
        assertThat(node.leadingTrivia()).isNotEmpty();
        // First trivia should be block comment
        assertThat(node.leadingTrivia().getFirst()).isInstanceOf(Trivia.BlockComment.class);
    }

    @Test
    void mixedTrivia_preservedInOrder() {
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- ([ \\t]+ / '//' [^\\n]* '\\n')+
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        var result = parser.parseCst("  // comment\n  42");
        assertThat(result.isSuccess()).isTrue();

        var node = result.unwrap();
        assertThat(node.leadingTrivia()).hasSize(3);
        assertThat(node.leadingTrivia().get(0)).isInstanceOf(Trivia.Whitespace.class);
        assertThat(node.leadingTrivia().get(1)).isInstanceOf(Trivia.LineComment.class);
        assertThat(node.leadingTrivia().get(2)).isInstanceOf(Trivia.Whitespace.class);
    }

    @Test
    void triviaSpan_hasCorrectPositions() {
        // Using choice grammar so each space run is one trivia
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- ([ ]+ / '\\t'+)+
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        var result = parser.parseCst("   42");
        assertThat(result.isSuccess()).isTrue();

        var node = result.unwrap();
        assertThat(node.leadingTrivia()).hasSize(1);

        var trivia = node.leadingTrivia().getFirst();
        assertThat(trivia.span().start().offset()).isEqualTo(0);
        assertThat(trivia.span().end().offset()).isEqualTo(3);
        assertThat(trivia.text()).isEqualTo("   ");
    }

    @Test
    void nestedRules_preserveTrivia() {
        var grammar = """
            Expr <- Term
            Term <- Number
            Number <- < [0-9]+ >
            %whitespace <- ([ ]+)+
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        var result = parser.parseCst("  42");
        assertThat(result.isSuccess()).isTrue();

        var node = result.unwrap();
        // Top-level rule should have the leading trivia
        assertThat(node.leadingTrivia()).hasSize(1);
        assertThat(node.leadingTrivia().getFirst().text()).isEqualTo("  ");
    }

    @Test
    void singleCharWhitespace_multipleTrivia() {
        // With simple char class, each char is separate
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- [ ]+
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        var result = parser.parseCst("   42");
        assertThat(result.isSuccess()).isTrue();

        var node = result.unwrap();
        // Each space is a separate trivia
        assertThat(node.leadingTrivia()).hasSize(3);
        for (var trivia : node.leadingTrivia()) {
            assertThat(trivia.text()).isEqualTo(" ");
        }
    }

    @Test
    void trailingWhitespace_capturedAsTrivia() {
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- ([ ]+)+
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        var result = parser.parseCst("42  ");
        assertThat(result.isSuccess()).isTrue();

        var node = result.unwrap();
        // Trailing whitespace captured
        assertThat(node.trailingTrivia()).hasSize(1);
        assertThat(node.trailingTrivia().getFirst().text()).isEqualTo("  ");
    }

    @Test
    void trailingComment_capturedAsTrivia() {
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- ([ \\t]+ / '//' [^\\n]*)+
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        var result = parser.parseCst("42 // trailing comment");
        assertThat(result.isSuccess()).isTrue();

        var node = result.unwrap();
        assertThat(node.trailingTrivia()).hasSize(2);
        assertThat(node.trailingTrivia().get(0)).isInstanceOf(Trivia.Whitespace.class);
        assertThat(node.trailingTrivia().get(1)).isInstanceOf(Trivia.LineComment.class);
    }

    @Test
    void bothLeadingAndTrailingTrivia_captured() {
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- ([ ]+)+
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        var result = parser.parseCst("  42  ");
        assertThat(result.isSuccess()).isTrue();

        var node = result.unwrap();
        assertThat(node.leadingTrivia()).hasSize(1);
        assertThat(node.leadingTrivia().getFirst().text()).isEqualTo("  ");
        assertThat(node.trailingTrivia()).hasSize(1);
        assertThat(node.trailingTrivia().getFirst().text()).isEqualTo("  ");
    }
}
