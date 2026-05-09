package org.pragmatica.peg.tree;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.action.RuleId;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step-3 prototype validation: {@link TriviaPostPass} re-derives leading and
 * trailing trivia from {@code (input, span)} coordinates without disturbing
 * production parser code.
 *
 * <p>Verifies four properties:
 * <ol>
 *   <li><b>Round-trip preservation</b> — for each corpus fixture and the
 *       hand-crafted adversarial inputs, the postPass-rebuilt CST
 *       reconstructs to a byte-identical input.</li>
 *   <li><b>Structural divergence catalog</b> — counts and categorises nodes
 *       whose attribution slot differs between the engine and the postPass.
 *       Bug-C' shifts (orphan trivia: leaf-trailing in engine vs
 *       wrapper-trailing in postPass) are the dominant category.</li>
 *   <li><b>Adversarial suite parity</b> — for each enabled
 *       {@code TriviaAdversarialTest} grammar/input, postPass output text
 *       round-trips and trivia-text totals are preserved.</li>
 *   <li><b>parseRuleAt parity</b> — for a sample Java25 input, the postPass
 *       applied to a partial parse at a rule's offset matches the postPass
 *       applied to the corresponding subtree from a full parse, in span
 *       text.</li>
 * </ol>
 */
class TriviaPostPassTest {

    private static final String JAVA_GRAMMAR = loadGrammar();

    private static String loadGrammar() {
        try (var in = TriviaPostPassTest.class.getResourceAsStream("/java25.peg")) {
            if (in == null) throw new IllegalStateException("java25.peg not found on classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load java25.peg", e);
        }
    }

    // ====================================================================
    // 1. Round-trip preservation
    // ====================================================================

    @Nested
    @DisplayName("Round-trip preservation")
    class RoundTrip {

        @Test
        void simpleGrammar_leadingAndTrailing_roundTrips() {
            var grammarText = """
                Number <- < [0-9]+ >
                %whitespace <- [ \\t]+
                """;
            var grammar = parseGrammar(grammarText);
            var parser = PegParser.fromGrammar(grammarText).unwrap();
            var input = "  42  ";
            var cst = parser.parseCst(input).unwrap();
            var rebuilt = TriviaPostPass.assignTrivia(input, cst, grammar);
            assertThat(reconstruct(rebuilt)).isEqualTo(input);
        }

        @Test
        void interElementWhitespace_roundTrips() {
            var grammarText = """
                Sum <- Number '+' Number
                Number <- < [0-9]+ >
                %whitespace <- [ ]+
                """;
            var grammar = parseGrammar(grammarText);
            var parser = PegParser.fromGrammar(grammarText).unwrap();
            var input = " 1 + 2 ";
            var cst = parser.parseCst(input).unwrap();
            var rebuilt = TriviaPostPass.assignTrivia(input, cst, grammar);
            assertThat(reconstruct(rebuilt)).isEqualTo(input);
        }

        @Test
        void commentsInWhitespace_roundTrip() {
            var grammarText = """
                Pair <- Number Number
                Number <- < [0-9]+ >
                Comment <- '/*' (!'*/' .)* '*/'
                %whitespace <- ([ \\t]+ / Comment)+
                """;
            var grammar = parseGrammar(grammarText);
            var parser = PegParser.fromGrammar(grammarText).unwrap();
            var input = "/*x*/ 1 /*y*/ 2";
            var cst = parser.parseCst(input).unwrap();
            var rebuilt = TriviaPostPass.assignTrivia(input, cst, grammar);
            assertThat(reconstruct(rebuilt)).isEqualTo(input);
        }

        @Test
        void corpusFixtures_allRoundTrip() throws IOException {
            var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
            var grammar = parseGrammar(JAVA_GRAMMAR);

            var corpusRoot = Path.of("src/test/resources/perf-corpus");
            // Limit to format-examples + flow-format-examples to keep runtime
            // bounded with the interpreter; the 1900-LOC large fixture is
            // exercised by RoundTripTest already (interpreter on that file
            // is the slow path) and adds no algorithmic coverage here.
            List<Path> files;
            try (Stream<Path> walk = Files.walk(corpusRoot)) {
                files = walk.filter(Files::isRegularFile)
                            .filter(p -> !p.toString().contains("/large/"))
                            .sorted()
                            .toList();
            }
            assertThat(files).as("corpus fixtures present").isNotEmpty();

            int passed = 0;
            int failed = 0;
            for (var f : files) {
                var input = Files.readString(f, StandardCharsets.UTF_8);
                var parseResult = parser.parseCst(input);
                if (!parseResult.isSuccess()) {
                    // Interpreter parity with generator is exercised
                    // elsewhere; if the interpreter rejects a fixture, skip
                    // it for this test (algorithm coverage is unaffected).
                    continue;
                }
                var rebuilt = TriviaPostPass.assignTrivia(input, parseResult.unwrap(), grammar);
                var reconstructed = reconstruct(rebuilt);
                if (reconstructed.equals(input)) {
                    passed++;
                } else {
                    failed++;
                }
            }
            // Report passes and require zero failures on the format/flow
            // corpus subset.
            assertThat(failed)
                .as("%d corpus fixtures round-tripped, %d failed", passed, failed)
                .isZero();
            assertThat(passed).isGreaterThan(0);
        }
    }

    // ====================================================================
    // 2. Structural divergence catalog
    // ====================================================================

    @Nested
    @DisplayName("Structural divergence catalog (postPass vs current)")
    class DivergenceCatalog {

        record Stats(int totalNodes,
                     int sameAttribution,
                     int leadingTextDiffers,
                     int trailingTextDiffers) {}

        @Test
        void corpusFixtures_attributionDivergence_isCatalogued() throws IOException {
            var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
            var grammar = parseGrammar(JAVA_GRAMMAR);
            var corpusRoot = Path.of("src/test/resources/perf-corpus");
            List<Path> files;
            try (Stream<Path> walk = Files.walk(corpusRoot)) {
                files = walk.filter(Files::isRegularFile)
                            .filter(p -> !p.toString().contains("/large/"))
                            .sorted()
                            .toList();
            }
            int totalNodes = 0;
            int leadingTextDiffers = 0;
            int trailingTextDiffers = 0;
            int wrapperTrailingShift = 0; // postPass non-empty / engine empty
            int leafTrailingShift = 0;    // engine non-empty / postPass empty (Bug C' compensation)
            int leadingShorterInPostPass = 0;
            int leadingLongerInPostPass = 0;
            int trailingShorterInPostPass = 0;
            int trailingLongerInPostPass = 0;
            int leadingDiffOnNonTerminal = 0;
            int leadingDiffOnTerminal = 0;
            int leadingDiffOnToken = 0;
            int trailingDiffOnNonTerminal = 0;
            int trailingDiffOnTerminal = 0;
            int trailingDiffOnToken = 0;
            int fixturesProcessed = 0;
            for (var f : files) {
                var input = Files.readString(f, StandardCharsets.UTF_8);
                var parseResult = parser.parseCst(input);
                if (!parseResult.isSuccess()) continue;
                var original = parseResult.unwrap();
                var rebuilt = TriviaPostPass.assignTrivia(input, original, grammar);

                var origNodes = new ArrayList<CstNode>();
                var rebNodes = new ArrayList<CstNode>();
                walk(original, origNodes);
                walk(rebuilt, rebNodes);
                if (origNodes.size() != rebNodes.size()) {
                    // Structure must be preserved by the postPass.
                    throw new AssertionError("node count differs: " + origNodes.size() + " vs " + rebNodes.size()
                                             + " for " + f);
                }
                for (int i = 0; i < origNodes.size(); i++) {
                    var o = origNodes.get(i);
                    var r = rebNodes.get(i);
                    totalNodes++;
                    var oLead = triviaText(o.leadingTrivia());
                    var rLead = triviaText(r.leadingTrivia());
                    var oTrail = triviaText(o.trailingTrivia());
                    var rTrail = triviaText(r.trailingTrivia());
                    if (!oLead.equals(rLead)) {
                        leadingTextDiffers++;
                        if (rLead.length() < oLead.length()) leadingShorterInPostPass++;
                        if (rLead.length() > oLead.length()) leadingLongerInPostPass++;
                        switch (o) {
                            case CstNode.NonTerminal _ -> leadingDiffOnNonTerminal++;
                            case CstNode.Terminal _ -> leadingDiffOnTerminal++;
                            case CstNode.Token _ -> leadingDiffOnToken++;
                            case CstNode.Error _ -> {}
                        }
                    }
                    if (!oTrail.equals(rTrail)) {
                        trailingTextDiffers++;
                        if (rTrail.length() < oTrail.length()) trailingShorterInPostPass++;
                        if (rTrail.length() > oTrail.length()) trailingLongerInPostPass++;
                        boolean origEmpty = oTrail.isEmpty();
                        boolean rebEmpty = rTrail.isEmpty();
                        boolean isWrapper = o instanceof CstNode.NonTerminal;
                        if (origEmpty && !rebEmpty && isWrapper) {
                            wrapperTrailingShift++;
                        } else if (!origEmpty && rebEmpty && !isWrapper) {
                            leafTrailingShift++;
                        }
                        switch (o) {
                            case CstNode.NonTerminal _ -> trailingDiffOnNonTerminal++;
                            case CstNode.Terminal _ -> trailingDiffOnTerminal++;
                            case CstNode.Token _ -> trailingDiffOnToken++;
                            case CstNode.Error _ -> {}
                        }
                    }
                }
                fixturesProcessed++;
            }
            // Print catalog for the design-discussion record.
            System.out.println("[TriviaPostPass divergence catalog]");
            System.out.println("  fixtures processed:        " + fixturesProcessed);
            System.out.println("  total nodes inspected:     " + totalNodes);
            System.out.println("  leading-text differences:  " + leadingTextDiffers);
            System.out.println("    leading shorter in postPass: " + leadingShorterInPostPass);
            System.out.println("    leading longer in postPass:  " + leadingLongerInPostPass);
            System.out.println("    on NonTerminal: " + leadingDiffOnNonTerminal
                               + ", Terminal: " + leadingDiffOnTerminal
                               + ", Token: " + leadingDiffOnToken);
            System.out.println("  trailing-text differences: " + trailingTextDiffers);
            System.out.println("    trailing shorter in postPass: " + trailingShorterInPostPass);
            System.out.println("    trailing longer in postPass:  " + trailingLongerInPostPass);
            System.out.println("    on NonTerminal: " + trailingDiffOnNonTerminal
                               + ", Terminal: " + trailingDiffOnTerminal
                               + ", Token: " + trailingDiffOnToken);
            System.out.println("  wrapper-trailing shifts (postPass attaches, engine empty): " + wrapperTrailingShift);
            System.out.println("  leaf-trailing shifts (engine attaches, postPass empty):    " + leafTrailingShift);

            assertThat(fixturesProcessed).isGreaterThan(0);
            assertThat(totalNodes).isGreaterThan(0);
        }
    }

    // ====================================================================
    // 3. Adversarial suite parity
    // ====================================================================

    @Nested
    @DisplayName("Adversarial parity (postPass on adversarial corpus)")
    class AdversarialParity {

        @Test
        void choiceBacktrack_postPassRoundTrips() {
            assertParityRoundTrip("""
                Start <- Item
                Item <- 'a' 'b' 'd' / 'a' 'b' 'c'
                %whitespace <- [ ]+
                """, "a b c");
        }

        @Test
        void emptyZomTail_postPassRoundTrips() {
            assertParityRoundTrip("""
                Item <- 'a' 'b' 'c' Tail*
                Tail <- 'x'
                %whitespace <- [ ]+
                """, "  a b c  ");
        }

        @Test
        void doublyNestedEmptyZom_postPassRoundTrips() {
            assertParityRoundTrip("""
                Outer <- 'a' Wrapper*
                Wrapper <- 'b' Tail*
                Tail <- 'c'
                %whitespace <- [ ]+
                """, "a   ");
        }

        @Test
        void commentBeforeEmptyZom_postPassRoundTrips() {
            assertParityRoundTrip("""
                Item <- 'a' Tail*
                Tail <- 'x'
                %whitespace <- ([ ]+ / '//' [^\\n]* '\\n')+
                """, "a // comment\n");
        }

        @Test
        void emptyOptionalTail_postPassRoundTrips() {
            assertParityRoundTrip("""
                Item <- 'a' Tail?
                Tail <- 'x'
                %whitespace <- [ ]+
                """, "a   ");
        }

        @Test
        void andPredicate_internalWs_postPassRoundTrips() {
            assertParityRoundTrip("""
                Item <- &(Pair) Pair
                Pair <- 'a' 'b'
                %whitespace <- [ ]+
                """, " a b ");
        }

        @Test
        void notPredicate_failingBody_postPassRoundTrips() {
            assertParityRoundTrip("""
                Item <- !('z') 'a' 'b'
                %whitespace <- [ ]+
                """, " a b");
        }

        @Test
        void packratPredicateSeed_postPassRoundTrips() {
            assertParityRoundTrip("""
                Item <- &Number Number
                Number <- < [0-9]+ >
                %whitespace <- [ ]+
                """, "   99");
        }

        @Test
        void whitespaceWithSubrule_postPassRoundTrips() {
            assertParityRoundTrip("""
                Pair <- Number Number
                Number <- < [0-9]+ >
                Comment <- '/*' (!'*/' .)* '*/'
                %whitespace <- ([ \\t]+ / Comment)+
                """, "/*x*/ 1 /*y*/ 2");
        }

        private void assertParityRoundTrip(String grammarText, String input) {
            var grammar = parseGrammar(grammarText);
            var parser = PegParser.fromGrammar(grammarText).unwrap();
            var cst = parser.parseCst(input).unwrap();
            var rebuilt = TriviaPostPass.assignTrivia(input, cst, grammar);
            assertThat(reconstruct(rebuilt))
                .as("postPass must round-trip input '%s'", input)
                .isEqualTo(input);
            // Trivia-text totals must be preserved (the policy shift only moves
            // a chunk between slots — text content is unchanged).
            assertThat(collectAllTriviaText(rebuilt))
                .as("postPass trivia text total must equal engine total")
                .isEqualTo(collectAllTriviaText(cst));
        }
    }

    // ====================================================================
    // 4. parseRuleAt parity
    // ====================================================================

    @Nested
    @DisplayName("parseRuleAt parity under postPass")
    class ParseRuleAtParity {

        record Value() implements RuleId {}
        record Pair() implements RuleId {}

        @Test
        void valueAtVariousOffsets_postPassSpanText_matchesFullParse() {
            var grammarText = """
                Value <- Pair / Number
                Pair <- '(' Value ',' Value ')'
                Number <- < [0-9]+ >
                %whitespace <- [ ]*
                """;
            var grammar = parseGrammar(grammarText);
            var parser = PegParser.fromGrammar(grammarText).unwrap();
            var rng = new Random(0xC0FFEEL);

            int iterations = 50;
            int checked = 0;
            for (int i = 0; i < iterations; i++) {
                var input = randomValue(rng, 3);
                var fullResult = parser.parseCst(input);
                if (!fullResult.isSuccess()) continue;
                var fullRebuilt = TriviaPostPass.assignTrivia(input, fullResult.unwrap(), grammar);

                var nodes = new ArrayList<CstNode>();
                walk(fullRebuilt, nodes);
                for (var n : nodes) {
                    Class<? extends RuleId> ruleClass = switch (n.rule()) {
                        case "Value" -> Value.class;
                        case "Pair" -> Pair.class;
                        default -> null;
                    };
                    if (ruleClass == null) continue;
                    int startOff = n.span().startOffset();
                    var partial = parser.parseRuleAt(ruleClass, input, startOff);
                    if (!partial.isSuccess()) continue;
                    var partialNode = partial.unwrap().node();
                    var partialRebuilt = TriviaPostPass.assignTrivia(input, partialNode, grammar);

                    var fullSpanText = input.substring(n.span().startOffset(), n.span().endOffset());
                    var partialSpanText = input.substring(
                        partialRebuilt.span().startOffset(),
                        partialRebuilt.span().endOffset());
                    assertThat(partialSpanText)
                        .as("postPass-applied parseRuleAt span text for rule %s at offset %d in '%s'",
                            n.rule(), startOff, input)
                        .isEqualTo(fullSpanText);
                    checked++;
                }
            }
            assertThat(checked).as("at least some nodes exercised").isGreaterThan(0);
        }

        @Test
        void parseRuleAt_atOffsetZero_leadingTriviaText_matchesFullParse() {
            var grammarText = """
                Value <- Pair / Number
                Pair <- '(' Value ',' Value ')'
                Number <- < [0-9]+ >
                %whitespace <- [ ]*
                """;
            var grammar = parseGrammar(grammarText);
            var parser = PegParser.fromGrammar(grammarText).unwrap();
            var input = "  42";
            var full = parser.parseCst(input).unwrap();
            var fullRebuilt = TriviaPostPass.assignTrivia(input, full, grammar);
            var partial = parser.parseRuleAt(Value.class, input, 0).unwrap();
            var partialRebuilt = TriviaPostPass.assignTrivia(input, partial.node(), grammar);

            // Under the context-independent attribution policy, both leading
            // computations are deterministic in (input, span). At offset 0
            // the leading is whatever scanWhitespace produces over [0, span.start).
            assertThat(triviaText(partialRebuilt.leadingTrivia()))
                .isEqualTo(triviaText(fullRebuilt.leadingTrivia()));
        }

        private String randomValue(Random rng, int depth) {
            if (depth <= 0 || rng.nextInt(3) == 0) {
                return String.valueOf(rng.nextInt(1000));
            }
            return "(" + randomValue(rng, depth - 1) + ", " + randomValue(rng, depth - 1) + ")";
        }
    }

    // ====================================================================
    // Helpers
    // ====================================================================

    private static Grammar parseGrammar(String text) {
        return GrammarParser.parse(text).unwrap();
    }

    /**
     * Reconstruct source text from a CST under the post-pass policy: each
     * node's leading trivia is emitted before its own content; non-terminal
     * trailing trivia is emitted after the last child's content; terminal
     * trailing is empty in the rebuilt tree. This mirrors the post-pass
     * attribution policy exactly so byte-equality validates the algorithm.
     */
    private static String reconstruct(CstNode root) {
        var sb = new StringBuilder();
        emitLeading(sb, root);
        emitBody(sb, root);
        emitTrailing(sb, root);
        return sb.toString();
    }

    private static void emitLeading(StringBuilder sb, CstNode node) {
        for (var t : node.leadingTrivia()) sb.append(t.text());
    }

    private static void emitTrailing(StringBuilder sb, CstNode node) {
        for (var t : node.trailingTrivia()) sb.append(t.text());
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

    private static String collectAllTriviaText(CstNode node) {
        var sb = new StringBuilder();
        for (var t : node.leadingTrivia()) sb.append(t.text());
        if (node instanceof CstNode.NonTerminal nt) {
            for (var c : nt.children()) sb.append(collectAllTriviaText(c));
        }
        for (var t : node.trailingTrivia()) sb.append(t.text());
        return sb.toString();
    }

    private static String triviaText(List<Trivia> list) {
        var sb = new StringBuilder();
        for (var t : list) sb.append(t.text());
        return sb.toString();
    }

    private static void walk(CstNode node, List<CstNode> out) {
        out.add(node);
        if (node instanceof CstNode.NonTerminal nt) {
            for (var c : nt.children()) walk(c, out);
        }
    }
}
