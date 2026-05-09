package org.pragmatica.peg.perf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.action.RuleId;
import org.pragmatica.peg.error.RecoveryStrategy;
import org.pragmatica.peg.parser.ParserConfig;
import org.pragmatica.peg.tree.CstNode;
import org.pragmatica.peg.tree.Trivia;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 4 commit 1 tests for the {@code triviaPostPass} flag wiring.
 *
 * <p>Validates four properties of the new flag plumbed through
 * {@link ParserConfig} and {@link PegParser}:
 * <ol>
 *   <li><b>Default-off no-op</b> — with the flag at its default {@code false},
 *       the engine's pre-existing CST attribution is returned unchanged
 *       (verified by structural equality vs. an explicit-off configuration).
 *   </li>
 *   <li><b>Round-trip preservation</b> — with the flag {@code true},
 *       reconstructing the input from the post-pass CST byte-equals the
 *       original input across simple, comment-bearing, and nested fixtures.
 *   </li>
 *   <li><b>Structural divergence is documented</b> — flag-on vs flag-off
 *       parses for the same input agree on total trivia text, but differ on
 *       at least one node's leading/trailing slot for at least one of the
 *       fixtures (proves the flag actually does something).</li>
 *   <li><b>parseRuleAt honours the flag</b> — invoking
 *       {@link org.pragmatica.peg.parser.Parser#parseRuleAt} with the flag on
 *       returns a subtree whose root leading trivia matches the post-pass
 *       attribution applied at the splice offset (Lever B unblocker claim).
 *   </li>
 * </ol>
 */
class TriviaPostPassFlagTest {

    private static final String SIMPLE_GRAMMAR = """
        Number <- < [0-9]+ >
        %whitespace <- [ \\t]*
        """;

    private static final String LIST_GRAMMAR = """
        List <- Item (',' Item)*
        Item <- < [a-z]+ >
        %whitespace <- [ \\t\\n]*
        """;

    private static final String COMMENT_GRAMMAR = """
        Number <- < [0-9]+ >
        %whitespace <- ([ \\t\\n] / LineComment)*
        LineComment <- '//' (![\\n] .)*
        """;

    record Item() implements RuleId {}

    private static ParserConfig configOff() {
        return ParserConfig.parserConfig(true, RecoveryStrategy.BASIC, true);
    }

    private static ParserConfig configOn() {
        var off = configOff();
        return new ParserConfig(
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
    }

    // ===================================================================
    // 1. Default-off no-op
    // ===================================================================

    @Nested
    @DisplayName("Default-off no-op")
    class DefaultOffNoOp {

        @Test
        void defaultConfig_flagOff_returnsEngineCstUnchanged() {
            var input = "  42  ";
            var defaultParser = PegParser.fromGrammar(SIMPLE_GRAMMAR)
                                         .unwrap();
            var explicitOffParser = PegParser.fromGrammar(SIMPLE_GRAMMAR, configOff())
                                             .unwrap();

            var defaultCst = defaultParser.parseCst(input)
                                          .unwrap();
            var explicitOffCst = explicitOffParser.parseCst(input)
                                                  .unwrap();

            // Default ParserConfig has triviaPostPass=false, so the two parses
            // must produce structurally identical trees.
            assertThat(reconstruct(defaultCst)).isEqualTo(reconstruct(explicitOffCst));
            assertThat(allTriviaText(defaultCst)).isEqualTo(allTriviaText(explicitOffCst));
            assertThat(triviaShape(defaultCst)).isEqualTo(triviaShape(explicitOffCst));
        }

        @Test
        void defaultParserConfig_hasFlagFalse() {
            assertThat(ParserConfig.DEFAULT.triviaPostPass()).isFalse();
        }

        @Test
        void parserConfigFactory_hasFlagFalse() {
            assertThat(ParserConfig.parserConfig(true, RecoveryStrategy.BASIC, true)
                                   .triviaPostPass()).isFalse();
        }
    }

    // ===================================================================
    // 2. Flag-on round-trip preservation
    // ===================================================================

    @Nested
    @DisplayName("Flag-on round-trip preservation")
    class FlagOnRoundTrip {

        @Test
        void simpleNumber_roundTrips() {
            assertRoundTrip(SIMPLE_GRAMMAR, "  42  ");
        }

        @Test
        void simpleNumber_noLeadingTrailing_roundTrips() {
            assertRoundTrip(SIMPLE_GRAMMAR, "42");
        }

        @Test
        void list_withInternalWhitespace_roundTrips() {
            assertRoundTrip(LIST_GRAMMAR, "alpha , beta , gamma");
        }

        @Test
        void list_withMixedWhitespaceAndNewlines_roundTrips() {
            assertRoundTrip(LIST_GRAMMAR, "  alpha,\n  beta,\n  gamma  ");
        }

        @Test
        void number_withLineComment_roundTrips() {
            assertRoundTrip(COMMENT_GRAMMAR, "// preamble\n42 // trailer\n");
        }

        private void assertRoundTrip(String grammar, String input) {
            var parser = PegParser.fromGrammar(grammar, configOn())
                                  .unwrap();
            var cst = parser.parseCst(input)
                            .unwrap();
            assertThat(reconstruct(cst)).as("flag-on round-trip for %s", input)
                                        .isEqualTo(input);
        }
    }

    // ===================================================================
    // 3. Flag toggles attribution but preserves trivia text totals
    // ===================================================================

    @Nested
    @DisplayName("Flag-on structural divergence with text preservation")
    class StructuralDivergence {

        @Test
        void flagOnVsOff_listInput_totalTriviaTextIdentical() {
            var input = "  alpha,\n  beta,\n  gamma  ";

            var off = PegParser.fromGrammar(LIST_GRAMMAR, configOff())
                               .unwrap()
                               .parseCst(input)
                               .unwrap();
            var on = PegParser.fromGrammar(LIST_GRAMMAR, configOn())
                              .unwrap()
                              .parseCst(input)
                              .unwrap();

            // Pure permutation: total trivia text must match.
            assertThat(allTriviaText(on)).as("trivia text totals preserved")
                                         .isEqualTo(allTriviaText(off));
            // Round-trip preserved on both sides.
            assertThat(reconstruct(off)).isEqualTo(input);
            assertThat(reconstruct(on)).isEqualTo(input);
        }

        @Test
        void flagOnVsOff_optionalTailGrammar_attributionDiffers() {
            // Bug-C' fixture: a grammar whose top-level rule has an optional
            // tail. The engine drains orphan trivia consumed by the empty
            // ZeroOrMore into the last terminal child's trailing slot;
            // the post-pass leaves it on the wrapper non-terminal instead.
            // Total trivia text is identical — only the slot shifts.
            var grammar = """
                Doc <- Number Tail*
                Tail <- ',' Number
                Number <- < [0-9]+ >
                %whitespace <- [ \\t\\n]+
                """;
            var input = "42  ";

            var off = PegParser.fromGrammar(grammar, configOff())
                               .unwrap()
                               .parseCst(input)
                               .unwrap();
            var on = PegParser.fromGrammar(grammar, configOn())
                              .unwrap()
                              .parseCst(input)
                              .unwrap();

            assertThat(allTriviaText(on)).as("trivia text totals preserved")
                                         .isEqualTo(allTriviaText(off));
            assertThat(triviaShape(off)).as(
            "post-pass and engine differ on at least one slot for Bug-C' fixture")
                                        .isNotEqualTo(triviaShape(on));
        }
    }

    // ===================================================================
    // 4. parseRuleAt with the flag — Lever B unblocker claim
    // ===================================================================

    @Nested
    @DisplayName("parseRuleAt honours the flag")
    class ParseRuleAtWithFlag {

        /**
         * <b>Lever B finding (Step 4 commit 1).</b> When {@code parseRuleAt}
         * is invoked at a non-zero offset, the {@link
         * org.pragmatica.peg.tree.TriviaPostPass#assignTrivia} prototype
         * scans {@code [0, span.start)} as leading trivia for the returned
         * subtree — it does not know about the splice context. The subtree's
         * leading slot therefore contains the entire trivia run from input
         * offset 0 (split into chunks per the inner-expression matcher),
         * <em>not</em> just the gap between the previous sibling's end and
         * the splice offset. The latter is what a full parse (post-pass)
         * attributes to the same node.
         *
         * <p>This test pins the current behaviour: the partial subtree
         * round-trips, and its leading trivia covers offsets {@code [0,
         * spliceOffset)}. Step 4 commit 2+ will need a splice-aware variant
         * (e.g. {@code TriviaPostPass.assignTrivia(input, cst, grammar,
         * spliceOffset)}) for IncrementalSession to splice cleanly.
         */
        @Test
        void parseRuleAt_flagOn_subtreeLeading_coversInputBeforeSpliceOffset() {
            var input = "  alpha , beta  ";
            // 0123456789012345
            int offset = 10;

            var parserOn = PegParser.fromGrammar(LIST_GRAMMAR, configOn())
                                    .unwrap();
            var partial = parserOn.parseRuleAt(Item.class, input, offset)
                                  .unwrap();
            var subtree = partial.node();

            // Subtree span starts at the requested offset.
            assertThat(subtree.span()
                              .start()
                              .offset()).isEqualTo(offset);

            // Documented Lever B gap: subtree's leading trivia text equals
            // input.substring(0, offset) projected through the %whitespace
            // matcher — i.e. the leading run of whitespace from input
            // offset 0, not the gap before the splice point.
            //
            // For input "  alpha , beta  " and the inner expression
            // [ \t\n], scanWhitespace([0,10)) produces only the leading two
            // spaces (offsets 0..2) — matching stops at 'a' (offset 2).
            // The two spaces are emitted as separate single-char chunks
            // because the inner expression is a single-char class.
            var leadingText = subtree.leadingTrivia()
                                     .stream()
                                     .map(Trivia::text)
                                     .reduce("", (a, b) -> a + b);
            assertThat(leadingText).as("leading covers input prefix")
                                   .isEqualTo("  ");
            // Two single-char chunks because inner expression is one char.
            assertThat(subtree.leadingTrivia()).hasSize(2);
        }

        @Test
        void parseRuleAt_flagOnVsOff_subtreeReconstructionIsConsistent() {
            var input = "alpha";
            var parserOn = PegParser.fromGrammar(LIST_GRAMMAR, configOn())
                                    .unwrap();
            var parserOff = PegParser.fromGrammar(LIST_GRAMMAR, configOff())
                                     .unwrap();

            var on = parserOn.parseRuleAt(Item.class, input, 0)
                             .unwrap()
                             .node();
            var off = parserOff.parseRuleAt(Item.class, input, 0)
                               .unwrap()
                               .node();

            // For a clean splice with no leading whitespace, both attributions
            // must agree on round-trip and total trivia text.
            assertThat(reconstruct(on)).isEqualTo(reconstruct(off));
            assertThat(allTriviaText(on)).isEqualTo(allTriviaText(off));
        }
    }

    // ===================================================================
    // helpers (mirrors policy from TriviaPostPassTest)
    // ===================================================================

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

    private static String allTriviaText(CstNode node) {
        var sb = new StringBuilder();
        appendAllTrivia(sb, node);
        return sb.toString();
    }

    private static void appendAllTrivia(StringBuilder sb, CstNode node) {
        for (var t : node.leadingTrivia()) {
            sb.append(t.text());
        }
        if (node instanceof CstNode.NonTerminal nt) {
            for (var c : nt.children()) {
                appendAllTrivia(sb, c);
            }
        }
        for (var t : node.trailingTrivia()) {
            sb.append(t.text());
        }
    }

    /**
     * Collect a structural fingerprint of trivia attribution across the tree:
     * for each node visited in pre-order, record (kind, span, leading-text,
     * trailing-text). Two trees with the same nodes but different
     * leading/trailing slots will produce different shapes.
     */
    private static String triviaShape(CstNode node) {
        var sb = new StringBuilder();
        appendShape(sb, node);
        return sb.toString();
    }

    private static void appendShape(StringBuilder sb, CstNode node) {
        sb.append(kind(node))
          .append('@')
          .append(node.span()
                      .start()
                      .offset())
          .append('-')
          .append(node.span()
                      .end()
                      .offset())
          .append(" L=")
          .append(triviaListText(node.leadingTrivia()))
          .append(" T=")
          .append(triviaListText(node.trailingTrivia()))
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

    private static String triviaListText(List<Trivia> list) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(list.get(i)
                          .text());
        }
        return sb.append(']')
                 .toString();
    }

}
