package org.pragmatica.peg.tree;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.parser.Parser;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step 4 commit 2 (0.5.1) — exercises the splice-offset overload of
 * {@link TriviaPostPass#assignTrivia(String, CstNode, Grammar, int)}.
 *
 * <p>The 4-arg overload constrains the leading-trivia scan window of the
 * root subtree to {@code [leadingScanFrom, root.span.start)} so that
 * partial-reparse subtrees produced by {@code parseRuleAt} carry only the
 * trivia that physically separates them from their previous sibling — not
 * the entire input prefix.
 *
 * <p>Validates four properties:
 * <ol>
 *   <li><b>Delegate parity</b> — {@code leadingScanFrom = 0} produces output
 *       structurally identical to the 3-arg overload.</li>
 *   <li><b>Splice-window leading</b> — non-zero {@code leadingScanFrom}
 *       restricts the root subtree's leading trivia to the splice gap.</li>
 *   <li><b>Empty-gap edge case</b> — {@code leadingScanFrom == span.start}
 *       yields an empty leading trivia list.</li>
 *   <li><b>Out-of-range guard</b> — negative or beyond-span values raise
 *       {@link IllegalArgumentException}.</li>
 * </ol>
 */
class TriviaPostPassSpliceOffsetTest {

    private static final String LIST_GRAMMAR = """
        List <- Item (',' Item)*
        Item <- < [a-z]+ >
        %whitespace <- [ \\t\\n]*
        """;

    // ====================================================================
    // 1. Delegate parity: leadingScanFrom = 0 == 3-arg overload
    // ====================================================================

    @Nested
    @DisplayName("Delegate parity (leadingScanFrom = 0)")
    class DelegateParity {

        @Test
        void zeroOffset_listInput_matchesThreeArgOverload() {
            var input = "alpha, beta, gamma";
            var parser = PegParser.fromGrammar(LIST_GRAMMAR)
                                  .unwrap();
            var grammar = parseGrammar(LIST_GRAMMAR);
            var cst = parser.parseCst(input)
                            .unwrap();

            var threeArg = TriviaPostPass.assignTrivia(input, cst, grammar);
            var fourArg = TriviaPostPass.assignTrivia(input, cst, grammar, 0);

            assertThat(triviaShape(fourArg)).isEqualTo(triviaShape(threeArg));
            assertThat(reconstruct(fourArg)).isEqualTo(input);
            assertThat(reconstruct(threeArg)).isEqualTo(input);
        }

        @Test
        void zeroOffset_withLeadingWhitespace_matchesThreeArgOverload() {
            var input = "   alpha";
            var parser = PegParser.fromGrammar(LIST_GRAMMAR)
                                  .unwrap();
            var grammar = parseGrammar(LIST_GRAMMAR);
            var cst = parser.parseCst(input)
                            .unwrap();

            var threeArg = TriviaPostPass.assignTrivia(input, cst, grammar);
            var fourArg = TriviaPostPass.assignTrivia(input, cst, grammar, 0);

            assertThat(triviaShape(fourArg)).isEqualTo(triviaShape(threeArg));
        }
    }

    // ====================================================================
    // 2. Non-zero splice offset clamps leading trivia window
    // ====================================================================

    @Nested
    @DisplayName("Non-zero splice offset clamps leading trivia")
    class SpliceWindowLeading {

        @Test
        void nonZeroOffset_leadingCoversOnlySpliceGap() {
            // Full input "  alpha , beta  ", parsed by parseRuleAt at offset 9.
            // Item rule auto-skips %whitespace and matches "beta" at offset 10.
            var input = "  alpha , beta  ";
            int spliceOffset = 9;

            var parserOn = parserWithPostPass();
            var partial = parserOn.parseRuleAt(Item.class, input, spliceOffset)
                                  .unwrap();
            var subtree = partial.node();
            var grammar = parseGrammar(LIST_GRAMMAR);

            // Apply the splice-aware overload directly on the engine output
            // (which is already post-passed by the engine with the same
            // offset). Re-applying with the same offset is idempotent on
            // root span coordinates.
            var rebuilt = TriviaPostPass.assignTrivia(input, subtree, grammar, spliceOffset);

            assertThat(rebuilt.span()
                              .startOffset())
            .as("subtree span starts at first matched terminal")
            .isEqualTo(10);

            var leading = triviaText(rebuilt.leadingTrivia());
            assertThat(leading).as("leading window is [%d, %d)", spliceOffset, 10)
                               .isEqualTo(" ");
        }

        @Test
        void nonZeroOffset_leadingDoesNotIncludeInputPrefix() {
            // Same input, but apply the splice-aware overload on a subtree
            // taken from the FULL parse (pretend it's a partial-reparse output).
            var input = "  alpha , beta  ";
            var parser = parserWithPostPass();
            var grammar = parseGrammar(LIST_GRAMMAR);

            // Find the second Item ('beta') in the full parse.
            var fullCst = parser.parseCst(input)
                                .unwrap();
            CstNode betaNode = findItemAt(fullCst, 10);
            assertThat(betaNode).isNotNull();

            // Rebuild with leadingScanFrom = 9 (splice gap before beta).
            var rebuilt = TriviaPostPass.assignTrivia(input, betaNode, grammar, 9);
            assertThat(triviaText(rebuilt.leadingTrivia())).isEqualTo(" ");

            // For comparison, full-document scan attributes nothing — the
            // node is mid-tree so [0, span.start) goes through the entire
            // input prefix. The 3-arg overload would mis-attribute that.
            var fullScan = TriviaPostPass.assignTrivia(input, betaNode, grammar);
            assertThat(triviaText(fullScan.leadingTrivia()))
            .as("full-doc scan over partial-reparse subtree wrongly captures input prefix")
            .isNotEqualTo(triviaText(rebuilt.leadingTrivia()));
        }
    }

    // ====================================================================
    // 3. Edge case: leadingScanFrom == span.start (no leading trivia)
    // ====================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        void leadingScanFromEqualsSpanStart_leadingIsEmpty() {
            var input = "alpha";
            var parser = parserWithPostPass();
            var grammar = parseGrammar(LIST_GRAMMAR);
            var cst = parser.parseCst(input)
                            .unwrap();

            int spanStart = cst.span()
                               .startOffset();
            var rebuilt = TriviaPostPass.assignTrivia(input, cst, grammar, spanStart);

            assertThat(rebuilt.leadingTrivia()).isEmpty();
        }

        @Test
        void negativeLeadingScanFrom_throwsIllegalArgument() {
            var input = "alpha";
            var parser = parserWithPostPass();
            var grammar = parseGrammar(LIST_GRAMMAR);
            var cst = parser.parseCst(input)
                            .unwrap();

            assertThatThrownBy(() -> TriviaPostPass.assignTrivia(input, cst, grammar, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("leadingScanFrom");
        }

        @Test
        void leadingScanFromBeyondSpanStart_throwsIllegalArgument() {
            var input = "  alpha";
            var parser = parserWithPostPass();
            var grammar = parseGrammar(LIST_GRAMMAR);
            var cst = parser.parseCst(input)
                            .unwrap();

            int spanStart = cst.span()
                               .startOffset();
            assertThatThrownBy(() -> TriviaPostPass.assignTrivia(
            input,
            cst,
            grammar,
            spanStart + 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("leadingScanFrom");
        }
    }

    // ====================================================================
    // helpers
    // ====================================================================

    record Item() implements org.pragmatica.peg.action.RuleId {}

    private static Parser parserWithPostPass() {
        var off = org.pragmatica.peg.parser.ParserConfig.parserConfig(
        true,
        org.pragmatica.peg.error.RecoveryStrategy.BASIC,
        true);
        var on = new org.pragmatica.peg.parser.ParserConfig(
        off.packratEnabled(),
        off.recoveryStrategy(),
        off.captureTrivia(),
        off.fastTrackFailure(),
        off.literalFailureCache(),
        off.charClassFailureCache(),
        off.bulkAdvanceLiteral(),
        off.skipWhitespaceFastPath(),
        off.reuseEndLocation(),
        off.choiceDispatch(),
        off.markResetChildren(),
        off.inlineLocations(),
        off.selectivePackrat(),
        off.packratSkipRules(),
        off.mutableParseResult(),
        off.tokenFastPath(),
        true);
        return PegParser.fromGrammar(LIST_GRAMMAR, on)
                        .unwrap();
    }

    private static CstNode findItemAt(CstNode node, int targetStart) {
        if ("Item".equals(node.rule()) && node.span()
                                              .startOffset() == targetStart) {
            return node;
        }
        if (node instanceof CstNode.NonTerminal nt) {
            for (var c : nt.children()) {
                var found = findItemAt(c, targetStart);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Grammar parseGrammar(String text) {
        return GrammarParser.parse(text)
                            .unwrap();
    }

    private static String triviaText(List<Trivia> list) {
        var sb = new StringBuilder();
        for (var t : list) {
            sb.append(t.text());
        }
        return sb.toString();
    }

    private static String reconstruct(CstNode root) {
        var sb = new StringBuilder();
        emitLeading(sb, root);
        emitBody(sb, root);
        emitTrailing(sb, root);
        return sb.toString();
    }

    private static void emitLeading(StringBuilder sb, CstNode node) {
        for (var t : node.leadingTrivia()) {
            sb.append(t.text());
        }
    }

    private static void emitTrailing(StringBuilder sb, CstNode node) {
        for (var t : node.trailingTrivia()) {
            sb.append(t.text());
        }
    }

    private static void emitBody(StringBuilder sb, CstNode node) {
        switch (node) {
            case CstNode.Terminal t -> sb.append(t.text());
            case CstNode.Token tk -> sb.append(tk.text());
            case CstNode.Error e -> sb.append(e.skippedText());
            case CstNode.NonTerminal nt -> {
                for (var c : nt.children()) {
                    emitLeading(sb, c);
                    emitBody(sb, c);
                    if (c instanceof CstNode.NonTerminal) {
                        emitTrailing(sb, c);
                    }
                }
            }
        }
    }

    private static String triviaShape(CstNode node) {
        var sb = new StringBuilder();
        appendShape(sb, node);
        return sb.toString();
    }

    private static void appendShape(StringBuilder sb, CstNode node) {
        sb.append(kind(node))
          .append('@')
          .append(node.span()
                      .startOffset())
          .append('-')
          .append(node.span()
                      .endOffset())
          .append(" L=")
          .append(triviaText(node.leadingTrivia()))
          .append(" T=")
          .append(triviaText(node.trailingTrivia()))
          .append('\n');
        if (node instanceof CstNode.NonTerminal nt) {
            for (var c : nt.children()) {
                appendShape(sb, c);
            }
        }
    }

    private static String kind(CstNode node) {
        return switch (node) {
            case CstNode.NonTerminal _ -> "NT";
            case CstNode.Terminal _ -> "T";
            case CstNode.Token _ -> "Tk";
            case CstNode.Error _ -> "E";
        };
    }
}
