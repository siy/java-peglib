package org.pragmatica.peg.perf;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.action.RuleId;
import org.pragmatica.peg.tree.CstNode;
import org.pragmatica.peg.tree.Trivia;

import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step-2 adversarial corpus for the trivia-attribution context-dependency
 * investigation.
 *
 * <p>Each {@code @Nested} class targets one of the seven divergence points
 * catalogued in Step 1. Tests fall into three groups:
 * <ul>
 *   <li><b>Enabled-passing</b> — current behaviour is correct; test pins it
 *       as a regression net for the eventual context-independent rewrite
 *       (Step 3).</li>
 *   <li><b>{@code @Disabled} — surfaces a known bug</b> — the Javadoc on the
 *       test method describes the bug and the corresponding entry in
 *       {@code docs/incremental/TRIVIA-ADVERSARIAL-FINDINGS.md}.</li>
 *   <li><b>Parity</b> — interpreter vs generated parser. Failures here are
 *       always {@code @Disabled} (current generator paths diverge) but the
 *       test method names spell out exactly what the divergence is.</li>
 * </ul>
 *
 * <p>Production code is not modified by Step 2. These tests serve as both bug
 * discovery and regression baseline.
 */
class TriviaAdversarialTest {

    /**
     * Target #1 — generator size-only restore vs interpreter list-snapshot
     * restore. Highest-priority target. The interpreter saves a {@code
     * List.copyOf(pendingLeadingTrivia)} so a branch that drains pending then
     * fails recovers the drained items. The generator emits {@code int
     * savePendingLeading()} / {@code restorePendingLeading(int)} which only
     * truncates back to the recorded size — drained items are not recovered.
     */
    @Nested
    class GeneratorSizeOnlyRestoreAsymmetry {

        /**
         * Grammar: {@code Choice <- ('a' WS 'b' WS 'c' / 'a' WS 'b' WS 'd')}
         * with {@code %whitespace <- [ ]+}.
         *
         * <p>Input {@code "a b c"} succeeds in the first alternative.
         *
         * <p>Critical scenario: input {@code "a b d"} — the first alternative
         * starts; pending whitespace gets drained when {@code 'b'} consumes
         * leading. Then {@code 'c'} fails, the choice rolls back. The
         * interpreter restores the drained items via list-snapshot; the
         * generator keeps them dropped. The test then re-enters via the second
         * alternative which reconsumes whitespace from the input, so total
         * trivia text is identical — but the Trivia object identities (and
         * grouping) for the second alt differ on the two paths.
         *
         * <p>Currently runs against the interpreter only and pins the correct
         * (post Bug-A fix) behaviour. The corresponding generator-parity test
         * is below.
         */
        @Test
        void interpreter_choiceBacktrack_drainsAndRestoresPendingTrivia() {
            var grammar = """
                Start <- Item
                Item <- 'a' 'b' 'd' / 'a' 'b' 'c'
                %whitespace <- [ ]+
                """;
            var parser = PegParser.fromGrammar(grammar).unwrap();

            var result = parser.parseCst("a b c");
            assertThat(result.isSuccess())
                .as("backtracking choice should succeed via second alt")
                .isTrue();
            // Walk the tree — every space chunk in the input must be
            // accounted for in some leading/trailing trivia somewhere.
            var totalTriviaText = collectAllTriviaText(result.unwrap());
            assertThat(totalTriviaText).isEqualTo("  ");
        }

        /**
         * Bug A reproducer (now fixed in interpreter, latent in generator).
         *
         * <p>Grammar exercises the case where a failed alternative drains
         * pending trivia AFTER the snapshot point. With size-only restore the
         * drained items are gone for good even though the size is restored.
         *
         * <p>Disabled because it surfaces the latent generator bug. To make
         * it run as a verification: cross-compare interpreter output against
         * generated parser output on the same input — the trivia-text totals
         * differ.
         *
         * <p>See {@code docs/incremental/TRIVIA-ADVERSARIAL-FINDINGS.md}
         * §"Bug 1 — generator size-only restore" for the minimum reproducer.
         */
        @Test
        void generator_choiceBacktrackAfterDrain_parityProbe() throws Exception {
            // Probe: black-box compare interpreter vs generated parser on a
            // backtracking-choice grammar where the failed branch drains
            // pending. PASSES — parity holds because the second alternative's
            // skipWhitespace re-runs at the same input offset and rebuilds
            // pending equivalently. The size-only restore IS structurally
            // weaker than list-snapshot (Step-1 catalogue, target #1) but
            // the weakness is not observable through CST text/trivia content
            // for this construction. Test pinned as a regression baseline:
            // if rewrite changes generator save/restore semantics, this must
            // continue to hold. Bug 1 is therefore reclassified as
            // robust-by-luck (latent) rather than definite/manifest. See
            // TRIVIA-ADVERSARIAL-FINDINGS.md.
            var grammar = """
                Start <- Item
                Item <- 'a' 'b' 'd' / 'a' 'b' 'c'
                %whitespace <- [ ]+
                """;
            var input = "a b c";

            var interp = PegParser.fromGrammar(grammar).unwrap()
                                  .parseCst(input).unwrap();
            var interpText = collectAllTriviaText(interp);

            var src = PegParser.generateCstParser(grammar, "test.adv.szbug", "SzBugParser").unwrap();
            var cls = compileAndLoad(src, "test.adv.szbug.SzBugParser");
            var node = invokeParse(cls, input);
            var genText = collectAllTriviaTextReflective(node);

            assertThat(genText).isEqualTo(interpText);
        }

        /**
         * Stronger adversarial probe: backtrack from a deeply-drained choice
         * alternative into a second alternative that consumes the SAME input
         * but via different rule structure (so the rule-wrapping differs and
         * trivia attachment may diverge if pending state carries over).
         * Pinned passing — confirms parity holds for this shape too.
         */
        @Test
        void generator_choiceBacktrackDifferentStructure_parityProbe() throws Exception {
            // Triple first so the long form is tried; if input is "x y" we
            // backtrack to Pair after Triple drains pending and then fails
            // on the missing 'z'.
            var grammar = """
                Start <- Triple / Pair
                Pair <- 'x' 'y'
                Triple <- 'x' 'y' 'z'
                %whitespace <- [ ]+
                """;
            var input = "x y";

            var interp = PegParser.fromGrammar(grammar).unwrap()
                                  .parseCst(input).unwrap();
            var interpText = collectAllTriviaText(interp);

            var src = PegParser.generateCstParser(grammar, "test.adv.szbug2", "SzBug2Parser").unwrap();
            var cls = compileAndLoad(src, "test.adv.szbug2.SzBug2Parser");
            var node = invokeParse(cls, input);
            var genText = collectAllTriviaTextReflective(node);

            assertThat(genText).isEqualTo(interpText);
        }
    }

    /**
     * Target #2 — Bug C' (rule-exit trailing attribution) boundary cases.
     * Bug C' attaches orphan trivia consumed before a zero-width tail element
     * (empty ZoM/Optional) to the last child's trailingTrivia. Current corpus
     * exercises the simple enum-trailing-comma case; here we hit nested ZoM
     * and trivia-at-end-of-rule-body.
     */
    @Nested
    class BugCprimeNestedBoundary {

        /**
         * Grammar: rule body ends with a zero-or-more whose body is itself
         * empty for this input, after multiple sibling literals. Input has
         * trailing whitespace before EOF that must attach to last child's
         * trailing — not vanish.
         */
        @Test
        void emptyZomTail_attributesOrphanWsToLastChildTrailing() {
            var grammar = """
                Item <- 'a' 'b' 'c' Tail*
                Tail <- 'x'
                %whitespace <- [ ]+
                """;
            var parser = PegParser.fromGrammar(grammar).unwrap();

            // "  a b c  " — trailing 2 spaces with empty Tail*.
            var result = parser.parseCst("  a b c  ");
            assertThat(result.isSuccess()).isTrue();
            var node = result.unwrap();

            // Trivia text must round-trip: 2 leading + 1+1 inter-word + 2 trailing = 6 spaces.
            assertThat(collectAllTriviaText(node)).isEqualTo("      ");
        }

        /**
         * Doubly-nested empty ZoM at rule end. Inner Tail* and outer Wrapper*
         * are both empty. Whitespace consumed before the first Tail iteration
         * attempt must surface somewhere in the tree.
         */
        @Test
        void doublyNestedEmptyZom_noWhitespaceLost() {
            var grammar = """
                Outer <- 'a' Wrapper*
                Wrapper <- 'b' Tail*
                Tail <- 'c'
                %whitespace <- [ ]+
                """;
            var parser = PegParser.fromGrammar(grammar).unwrap();
            var input = "a   ";  // 3 trailing spaces, both ZoMs empty
            var result = parser.parseCst(input);
            assertThat(result.isSuccess()).isTrue();
            assertThat(collectAllTriviaText(result.unwrap())).isEqualTo("   ");
        }

        /**
         * Comments mixed with whitespace at empty-ZoM site. Verifies that
         * trivia type classification (LineComment/BlockComment/Whitespace)
         * survives the orphan-attach path.
         */
        @Test
        void commentBeforeEmptyZom_survivesAsTrivia() {
            var grammar = """
                Item <- 'a' Tail*
                Tail <- 'x'
                %whitespace <- ([ ]+ / '//' [^\\n]* '\\n')+
                """;
            var parser = PegParser.fromGrammar(grammar).unwrap();
            var input = "a // comment\n";
            var result = parser.parseCst(input);
            assertThat(result.isSuccess()).isTrue();
            // The comment must appear somewhere in trivia.
            var totalText = collectAllTriviaText(result.unwrap());
            assertThat(totalText).contains("// comment");
        }

        /**
         * Optional tail (rather than ZoM) at rule end with whitespace before.
         * Optional that didn't match should still give the consumed
         * whitespace a home.
         */
        @Test
        void emptyOptionalTail_attributesOrphanWs() {
            var grammar = """
                Item <- 'a' Tail?
                Tail <- 'x'
                %whitespace <- [ ]+
                """;
            var parser = PegParser.fromGrammar(grammar).unwrap();
            var input = "a   ";
            var result = parser.parseCst(input);
            assertThat(result.isSuccess()).isTrue();
            assertThat(collectAllTriviaText(result.unwrap())).isEqualTo("   ");
        }
    }

    /**
     * Target #3 — parseRuleAt context-loss.
     * parseRuleAt initializes a fresh ParsingContext with empty pending. So
     * the wrapper's leadingTrivia is whatever skipWhitespace produces from
     * the offset onwards — narrower than what the same rule sees during a
     * full parse, where carriedLeading from prior siblings flows in.
     */
    @Nested
    class ParseRuleAtContextLoss {

        record Item() implements RuleId {}
        record Outer() implements RuleId {}

        /**
         * Full parse of {@code "  42  "} attributes the leading 2 spaces to
         * the {@code Item} wrapper. parseRuleAt(Item, "  42  ", 0) does too —
         * but parseRuleAt(Item, "  42  ", 2) sees offset 2 (already past the
         * leading spaces) and returns Item with empty leadingTrivia. The
         * test pins this as the documented behaviour.
         */
        @Test
        void parseRuleAt_atOffsetPastLeading_returnsEmptyLeading() {
            var grammar = """
                Item <- < [0-9]+ >
                %whitespace <- [ ]+
                """;
            var parser = PegParser.fromGrammar(grammar).unwrap();

            var partial = parser.parseRuleAt(Item.class, "  42  ", 2);
            assertThat(partial.isSuccess()).isTrue();
            var node = partial.unwrap().node();
            // Item invoked at offset 2 — leading is empty (no whitespace at
            // positions 2..2, "42" starts here). Documented context-loss.
            assertThat(node.leadingTrivia()).isEmpty();
        }

        /**
         * Now the more subtle case: parseRuleAt at offset 0 should reproduce
         * what a full parse sees for the same rule — both contexts start with
         * empty pending and skipWhitespace runs from position 0.
         */
        @Test
        void parseRuleAt_atOffsetZero_matchesFullParseLeading() {
            var grammar = """
                Item <- < [0-9]+ >
                %whitespace <- [ ]+
                """;
            var parser = PegParser.fromGrammar(grammar).unwrap();

            var fullCst = parser.parseCst("  42  ").unwrap();
            var partial = parser.parseRuleAt(Item.class, "  42  ", 0).unwrap();

            // Top-level Item wrapper from full parse:
            var fullLeadingText = trailingTextOf(fullCst.leadingTrivia());
            var partialLeadingText = trailingTextOf(partial.node().leadingTrivia());
            assertThat(partialLeadingText).isEqualTo(fullLeadingText);
        }

        /**
         * Surfaces the context-loss for a NESTED rule. {@code Item} appears
         * inside a parent context with leading whitespace carried from the
         * parent. parseRuleAt invoked at the same offset as the full parse's
         * Item span sees fresh pending, so its leading is whatever
         * skipWhitespace produces locally.
         *
         * <p>This test exercises the documented context-loss: the Item's
         * leading from a full parse may differ from the leading from
         * parseRuleAt at the same offset.
         */
        @Test
        void parseRuleAt_nestedRule_pinsContextLossDifference() {
            var grammar = """
                Outer <- 'X' Item
                Item <- < [0-9]+ >
                %whitespace <- [ ]+
                """;
            var parser = PegParser.fromGrammar(grammar).unwrap();

            // Find any Item node in the full parse.
            var full = parser.parseCst("X  42").unwrap();
            CstNode fullItem = findRule(full, "Item");
            assertThat(fullItem).as("full-parse Item must exist").isNotNull();

            int itemStart = fullItem.span().start().offset();
            var partial = parser.parseRuleAt(Item.class, "X  42", itemStart).unwrap();

            // parseRuleAt enters Item with empty pending — its wrapper sees
            // local skipWhitespace from itemStart only. If itemStart already
            // sits at the digits, leading is empty; if itemStart points at
            // whitespace, leading captures it. Either way, the test
            // documents what happens — current Step-1 catalogue says the
            // wrapper.leadingTrivia is narrower than the full parse here
            // because the full parse threaded carriedLeading through Outer.
            // We assert structural property: Item subtree text matches.
            String fullText = "X  42".substring(
                fullItem.span().start().offset(),
                fullItem.span().end().offset()
            );
            String partialText = "X  42".substring(
                partial.node().span().start().offset(),
                partial.node().span().end().offset()
            );
            assertThat(partialText).isEqualTo(fullText);
        }
    }

    /**
     * Target #4 — cache hit vs miss path divergence.
     * Bug B fix: cache-hit drains pending + reskips ws + reattaches. Test
     * that the same rule called from two different sites with different
     * carried-leading produces leadingTrivia consistent with each call's
     * context.
     */
    @Nested
    class PackratCacheHitMissParity {

        /**
         * Grammar invokes Number from two sites with different prior-sibling
         * trivia. The packrat cache may seed on the first call (e.g. via a
         * predicate's lookahead) and serve the second call from cache. Both
         * resulting Number nodes should have leading trivia consistent with
         * their own call site.
         */
        @Test
        void sameRuleTwoCallSites_eachLeadingMatchesItsContext() {
            var grammar = """
                Pair <- A B
                A <- &Number Number
                B <- ',' Number
                Number <- < [0-9]+ >
                %whitespace <- [ ]+
                """;
            var parser = PegParser.fromGrammar(grammar).unwrap();
            // "  42  ,  7" — Number is called at A (lookahead first, then real)
            // and at B (after comma).
            var input = "  42 , 7";
            var result = parser.parseCst(input);
            assertThat(result.isSuccess()).isTrue();

            // Total trivia text must round-trip the input's whitespace.
            // Input has 4 spaces total (2 leading + 1 around comma left + 1 right).
            var triviaText = collectAllTriviaText(result.unwrap());
            assertThat(triviaText.replace(" ", "").length()).isZero();
            assertThat(triviaText.length()).isEqualTo(4);
        }

        /**
         * Predicate-driven cache seeding then real call. The {@code &Number}
         * lookahead seeds the packrat cache for Number at the post-leading
         * position. The subsequent real Number call is a cache hit — and
         * Bug B fix has it reattach leading by re-running skipWhitespace.
         * Verifies the reattached leading is correct when the predicate
         * itself did not advance position past the leading.
         */
        @Test
        void predicateSeedsCache_realCallHitReattachesLeading() {
            var grammar = """
                Item <- &Number Number
                Number <- < [0-9]+ >
                %whitespace <- [ ]+
                """;
            var parser = PegParser.fromGrammar(grammar).unwrap();
            var result = parser.parseCst("   99");
            assertThat(result.isSuccess()).isTrue();
            // 3 leading spaces must show up somewhere.
            assertThat(collectAllTriviaText(result.unwrap())).isEqualTo("   ");
        }
    }

    /**
     * Target #5 — predicate combinator state symmetry.
     * And/Not save+restore both pos and pending. A predicate whose body
     * consumes whitespace internally should leave outer state byte-identical
     * to before the predicate.
     */
    @Nested
    class PredicateStateSymmetry {

        /**
         * The And predicate's body internally consumes whitespace. After the
         * predicate succeeds, the next sibling parses starting from the same
         * position with the same pending state. Verifies trivia round-trips.
         */
        @Test
        void andPredicate_internalWsConsumption_outerStateRestored() {
            var grammar = """
                Item <- &(Pair) Pair
                Pair <- 'a' 'b'
                %whitespace <- [ ]+
                """;
            var parser = PegParser.fromGrammar(grammar).unwrap();
            // " a b " — predicate would internally skip leading and
            // inter-element ws. After predicate, the real Pair call reparses
            // from the same offset.
            var result = parser.parseCst(" a b ");
            assertThat(result.isSuccess()).isTrue();
            // 3 spaces total in the input.
            assertThat(collectAllTriviaText(result.unwrap())).isEqualTo("   ");
        }

        /**
         * The Not predicate fails internally (its body consumes ws then
         * literal mismatch). Outer state must be restored.
         */
        @Test
        void notPredicate_failingBody_outerStateRestored() {
            var grammar = """
                Item <- !('z') 'a' 'b'
                %whitespace <- [ ]+
                """;
            var parser = PegParser.fromGrammar(grammar).unwrap();
            var result = parser.parseCst(" a b");
            assertThat(result.isSuccess()).isTrue();
            // 2 spaces total (1 leading + 1 inter-word).
            assertThat(collectAllTriviaText(result.unwrap())).isEqualTo("  ");
        }
    }

    /**
     * Target #6 — Optional CutFailure asymmetry.
     * In {@link org.pragmatica.peg.parser.PegEngine#parseOptionalWithMode}
     * (line 1936): on CutFailure the code returns the failure WITHOUT
     * restoring the entry pending snapshot. Regular failure DOES restore
     * (line 1941). Predates Step 1 docs.
     */
    @Nested
    class OptionalCutFailurePending {

        /**
         * Optional containing a Cut that fails. Outer parse fails because
         * the cut prevents recovery. Pending state asymmetry can only be
         * observed via cross-implementation comparison or instrumentation.
         *
         * <p>Disabled because it surfaces a latent bug rather than asserting
         * correct behaviour — there is no straightforward black-box assertion
         * that distinguishes "snapshot restored" from "snapshot not restored"
         * when the parse subsequently fails. Documented as Bug 2 in the
         * findings doc; minimum reproducer captured here.
         */
        @Test
        @Disabled("Bug 2 (latent): parseOptionalWithMode line 1936 returns CutFailure without restoring entryPendingSnapshot. No black-box surface manifestation found yet — observability requires instrumented context. See TRIVIA-ADVERSARIAL-FINDINGS.md.")
        void optional_withInternalCutFailure_pendingSnapshotNotRestored() {
            var grammar = """
                Item <- Pre Tail?
                Pre <- < 'a' >
                Tail <- 'b' ^ 'c'
                %whitespace <- [ ]+
                """;
            var parser = PegParser.fromGrammar(grammar).unwrap();
            // "a b X" — Tail matches 'b', cut commits, then 'c' fails.
            // Optional should NOT swallow the cut; outer parse should fail.
            var result = parser.parseCst("a b X");
            assertThat(result.isFailure()).isTrue();
        }
    }

    /**
     * Target #7 — %whitespace purity invariant (undocumented).
     * Bug B's correctness relies on %whitespace being a pure function of
     * (input, pos). If %whitespace uses an inner sub-rule that mutates
     * packrat cache state, the cache-hit reattach path can produce different
     * trivia than the original cache-miss path.
     */
    @Nested
    class WhitespacePurityInvariant {

        /**
         * %whitespace reuses a sub-rule that itself goes through the parser
         * machinery. The first time we drive a rule, %whitespace runs and
         * may seed cache entries. On a subsequent cache hit for the OUTER
         * rule, %whitespace runs again (Bug B reattach). Both runs must
         * produce the same Trivia list. This test pins the current
         * behaviour; it passes if %whitespace is in fact pure.
         */
        @Test
        void whitespaceWithSubrule_cacheHitReattachIsConsistent() {
            // Comment is a sub-rule used by %whitespace. Grammar invokes
            // Number twice so the second call is a cache hit.
            var grammar = """
                Pair <- Number Number
                Number <- < [0-9]+ >
                Comment <- '/*' (!'*/' .)* '*/'
                %whitespace <- ([ \\t]+ / Comment)+
                """;
            var parser = PegParser.fromGrammar(grammar).unwrap();
            var result = parser.parseCst("/*x*/ 1 /*y*/ 2");
            assertThat(result.isSuccess()).isTrue();
            // Verify all comments and spaces accounted for in trivia.
            var text = collectAllTriviaText(result.unwrap());
            assertThat(text).contains("/*x*/");
            assertThat(text).contains("/*y*/");
        }
    }

    /**
     * Property-based fuzz test (single, focused).
     *
     * <p>For ~100 random small JSON-like inputs: full-parse the input, then
     * walk every CstNode in the tree, take its rule + start offset, invoke
     * parseRuleAt for that rule at that offset, and compare the inner-text
     * (the matched span text) of the returned subtree against the
     * corresponding subtree in the full parse. Wrappers' leadingTrivia is
     * explicitly NOT compared because Target #3 documents that as a known
     * context-loss.
     *
     * <p>If structural inequality is found, that's a NEW bug not covered by
     * Target #3. The test asserts the negation: parseRuleAt subtree text
     * equals full-parse subtree text.
     */
    @Nested
    class ParseRuleAtFuzz {

        record Value() implements RuleId {}
        record Pair() implements RuleId {}

        @Test
        void fuzz_parseRuleAt_subtreeTextEqualsFullParse() {
            var grammar = """
                Value <- Pair / Number
                Pair <- '(' Value ',' Value ')'
                Number <- < [0-9]+ >
                %whitespace <- [ ]*
                """;
            var parser = PegParser.fromGrammar(grammar).unwrap();
            var rng = new Random(0xC0FFEEL);

            int iterations = 100;
            int checked = 0;
            for (int i = 0; i < iterations; i++ ) {
                var input = randomValue(rng, 3);
                var fullResult = parser.parseCst(input);
                if (!fullResult.isSuccess()) continue;
                var full = fullResult.unwrap();

                // Walk all nodes; pick those whose rule is Value or Pair.
                var nodes = new ArrayList<CstNode>();
                walk(full, nodes);
                for (var n : nodes) {
                    Class< ? extends RuleId> ruleClass = switch (n.rule()) {
                        case "Value" -> Value.class;
                        case "Pair" -> Pair.class;
                        default -> null;
                    };
                    if (ruleClass == null) continue;

                    var startOff = n.span().start().offset();
                    var partial = parser.parseRuleAt(ruleClass, input, startOff);
                    if (!partial.isSuccess()) continue;

                    // Compare matched text of the body, not wrapper leading.
                    var fullSpanText = input.substring(
                        n.span().start().offset(),
                        n.span().end().offset()
                    );
                    var partialNode = partial.unwrap().node();
                    var partialSpanText = input.substring(
                        partialNode.span().start().offset(),
                        partialNode.span().end().offset()
                    );
                    assertThat(partialSpanText)
                        .as("parseRuleAt span text for rule %s at offset %d in input '%s'", n.rule(), startOff, input)
                        .isEqualTo(fullSpanText);
                    checked++ ;
                }
            }
            assertThat(checked).as("at least some nodes exercised").isGreaterThan(0);
        }

        private String randomValue(Random rng, int depth) {
            if (depth <= 0 || rng.nextInt(3) == 0) {
                return String.valueOf(rng.nextInt(1000));
            }
            return "(" + randomValue(rng, depth - 1) + ", " + randomValue(rng, depth - 1) + ")";
        }
    }

    // === Helpers ===

    private static void walk(CstNode node, List<CstNode> out) {
        out.add(node);
        if (node instanceof CstNode.NonTerminal nt) {
            for (var c : nt.children()) walk(c, out);
        }
    }

    private static CstNode firstChild(CstNode node) {
        if (node instanceof CstNode.NonTerminal nt && !nt.children().isEmpty()) {
            return nt.children().get(0);
        }
        return null;
    }

    private static CstNode findRule(CstNode node, String ruleName) {
        if (ruleName.equals(node.rule())) return node;
        if (node instanceof CstNode.NonTerminal nt) {
            for (var c : nt.children()) {
                var found = findRule(c, ruleName);
                if (found != null) return found;
            }
        }
        return null;
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

    private static String trailingTextOf(List<Trivia> list) {
        var sb = new StringBuilder();
        for (var t : list) sb.append(t.text());
        return sb.toString();
    }

    private static Class<?> compileAndLoad(String source, String className) throws Exception {
        Path tempDir = Files.createTempDirectory("peglib-adv-test");
        var packagePath = className.substring(0, className.lastIndexOf('.')).replace('.', '/');
        var simple = className.substring(className.lastIndexOf('.') + 1);
        var pkgDir = tempDir.resolve(packagePath);
        Files.createDirectories(pkgDir);
        var srcFile = pkgDir.resolve(simple + ".java");
        Files.writeString(srcFile, source);
        var compiler = ToolProvider.getSystemJavaCompiler();
        var rc = compiler.run(null, null, null,
            "-d", tempDir.toString(),
            "-cp", System.getProperty("java.class.path"),
            srcFile.toString());
        if (rc != 0) {
            throw new RuntimeException("compile failed: " + className);
        }
        var cl = new URLClassLoader(new URL[]{tempDir.toUri().toURL()});
        return cl.loadClass(className);
    }

    @SuppressWarnings("unchecked")
    private static Object invokeParse(Class<?> cls, String input) throws Exception {
        var instance = cls.getDeclaredConstructor().newInstance();
        var m = cls.getMethod("parse", String.class);
        var result = (Result<Object>) m.invoke(instance, input);
        return result.unwrap();
    }

    private static String collectAllTriviaTextReflective(Object node) throws Exception {
        var sb = new StringBuilder();
        var leadingMethod = node.getClass().getMethod("leadingTrivia");
        var trailingMethod = node.getClass().getMethod("trailingTrivia");
        @SuppressWarnings("unchecked")
        var leading = (List<Object>) leadingMethod.invoke(node);
        for (var t : leading) {
            sb.append((String) t.getClass().getMethod("text").invoke(t));
        }
        if (node.getClass().getSimpleName().equals("NonTerminal")) {
            @SuppressWarnings("unchecked")
            var children = (List<Object>) node.getClass().getMethod("children").invoke(node);
            for (var c : children) sb.append(collectAllTriviaTextReflective(c));
        }
        @SuppressWarnings("unchecked")
        var trailing = (List<Object>) trailingMethod.invoke(node);
        for (var t : trailing) {
            sb.append((String) t.getClass().getMethod("text").invoke(t));
        }
        return sb.toString();
    }
}
