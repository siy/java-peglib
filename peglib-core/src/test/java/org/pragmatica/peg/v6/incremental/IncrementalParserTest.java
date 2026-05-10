package org.pragmatica.peg.v6.incremental;

import org.pragmatica.peg.v6.Parser;
import org.pragmatica.peg.v6.PegParser;
import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.cst.ParseResult;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase D.2 — coverage for {@link IncrementalParser}.
 *
 * <p>The wrapper currently does full reparse on every edit, so the contract under
 * test is: after edit(o, l, t), the visible state must equal a fresh {@code parser.parse}
 * of the post-edit input. We verify this for several edit shapes (insert, replace,
 * delete) and a sequence of three edits, plus argument validation.
 */
class IncrementalParserTest {
    private static final String GRAMMAR = """
        File <- Item (',' Item)*
        Item <- 'foo' / 'bar'
        %whitespace <- [ \\t]*
        """;

    /**
     * Phase D.1.2 — secondary grammar where {@code Item} is forced to PARSER
     * classification (it references a tagged inner rule, so it can't be aliased
     * to a token kind). Used by the partial-reparse tests; the simple grammar
     * above keeps {@code Item} as LEXER which means the partial path is never
     * exercised on it (only the full-reparse fallback fires).
     */
    private static final String PARTIAL_GRAMMAR = """
        File <- Item (',' Item)*
        Item <- '(' Word ')'
        Word <- 'foo' / 'bar'
        %whitespace <- [ \\t]*
        """;

    private static Parser parser;
    private static Parser partialParser;

    @BeforeAll
    static void setup() {
        parser = PegParser.fromGrammar(GRAMMAR)
                          .unwrap();
        partialParser = PegParser.fromGrammar(PARTIAL_GRAMMAR)
                                 .unwrap();
    }

    private static void assertCstEquivalent(CstArray actual, CstArray expected) {
        assertThat(actual.input())
        .isEqualTo(expected.input());
        assertThat(actual.nodeCount())
        .isEqualTo(expected.nodeCount());
        assertThat(actual.rootIndex())
        .isEqualTo(expected.rootIndex());
        for (var i = 0; i < expected.nodeCount(); i++ ) {
            assertThat(actual.kindAt(i))
            .as("kindAt(%d)",
                i)
            .isEqualTo(expected.kindAt(i));
            assertThat(actual.parentAt(i))
            .as("parentAt(%d)",
                i)
            .isEqualTo(expected.parentAt(i));
            assertThat(actual.firstChildAt(i))
            .as("firstChildAt(%d)",
                i)
            .isEqualTo(expected.firstChildAt(i));
            assertThat(actual.nextSiblingAt(i))
            .as("nextSiblingAt(%d)",
                i)
            .isEqualTo(expected.nextSiblingAt(i));
            assertThat(actual.firstTokenAt(i))
            .as("firstTokenAt(%d)",
                i)
            .isEqualTo(expected.firstTokenAt(i));
            assertThat(actual.lastTokenAt(i))
            .as("lastTokenAt(%d)",
                i)
            .isEqualTo(expected.lastTokenAt(i));
            assertThat(actual.flagsAt(i))
            .as("flagsAt(%d)",
                i)
            .isEqualTo(expected.flagsAt(i));
        }
    }

    @Test
    void initialState_matchesFreshParse() {
        var ip = new IncrementalParser(parser, "foo, bar");
        assertThat(ip.input())
        .isEqualTo("foo, bar");
        assertCstEquivalent(ip.current(),
                            parser.parse("foo, bar")
                                  .cst());
        assertThat(ip.diagnostics())
        .isEqualTo(parser.parse("foo, bar")
                         .diagnostics());
        assertThat(ip.currentTokens())
        .isSameAs(ip.current()
                    .tokens());
    }

    @Test
    void insertAtEnd_updatesInputAndMatchesFreshParse() {
        var ip = new IncrementalParser(parser, "foo");
        var result = ip.edit(3, 0, ", bar");
        assertThat(ip.input())
        .isEqualTo("foo, bar");
        assertCstEquivalent(result.cst(),
                            parser.parse("foo, bar")
                                  .cst());
        assertCstEquivalent(ip.current(), result.cst());
        assertThat(ip.diagnostics())
        .isEqualTo(result.diagnostics());
    }

    @Test
    void insertAtStart_updatesInputAndMatchesFreshParse() {
        var ip = new IncrementalParser(parser, "bar");
        var result = ip.edit(0, 0, "foo, ");
        assertThat(ip.input())
        .isEqualTo("foo, bar");
        assertCstEquivalent(result.cst(),
                            parser.parse("foo, bar")
                                  .cst());
    }

    @Test
    void replaceMiddle_updatesInputAndMatchesFreshParse() {
        var ip = new IncrementalParser(parser, "foo, bar");
        var result = ip.edit(5, 3, "foo");
        assertThat(ip.input())
        .isEqualTo("foo, foo");
        assertCstEquivalent(result.cst(),
                            parser.parse("foo, foo")
                                  .cst());
    }

    @Test
    void delete_shortensInputAndMatchesFreshParse() {
        var ip = new IncrementalParser(parser, "foo, bar, foo");
        var result = ip.edit(3, 5, "");
        assertThat(ip.input())
        .isEqualTo("foo, foo");
        assertCstEquivalent(result.cst(),
                            parser.parse("foo, foo")
                                  .cst());
    }

    @Test
    void sequentialEdits_finalStateMatchesFullReparseOfCumulativeInput() {
        var ip = new IncrementalParser(parser, "foo");
        ip.edit(3, 0, ", bar");
        ip.edit(8, 0, ", foo");
        var result = ip.edit(0, 3, "bar");
        assertThat(ip.input())
        .isEqualTo("bar, bar, foo");
        var fresh = parser.parse("bar, bar, foo");
        assertCstEquivalent(result.cst(), fresh.cst());
        assertCstEquivalent(ip.current(), fresh.cst());
        assertThat(ip.diagnostics())
        .isEqualTo(fresh.diagnostics());
    }

    @Test
    void editIntroducesError_diagnosticsMatchFreshParse() {
        var ip = new IncrementalParser(parser, "foo, bar");
        var result = ip.edit(5, 3, "@@@");
        assertThat(ip.input())
        .isEqualTo("foo, @@@");
        var fresh = parser.parse("foo, @@@");
        assertThat(result.diagnostics())
        .isEqualTo(fresh.diagnostics());
        assertThat(ip.diagnostics())
        .isEqualTo(fresh.diagnostics());
    }

    @Test
    void noOpEdit_stateRemainsConsistent() {
        var ip = new IncrementalParser(parser, "foo, bar");
        var result = ip.edit(4, 0, "");
        assertThat(ip.input())
        .isEqualTo("foo, bar");
        assertCstEquivalent(result.cst(),
                            parser.parse("foo, bar")
                                  .cst());
    }

    @Test
    void parserAccessor_returnsConstructedParser() {
        var ip = new IncrementalParser(parser, "foo");
        assertThat(ip.parser())
        .isSameAs(parser);
    }

    @Test
    void editAfterFailedInitialParse_stillWorks() {
        var ip = new IncrementalParser(parser, "@@@");
        assertThat(ip.diagnostics())
        .isNotEmpty();
        var result = ip.edit(0, 3, "foo");
        assertThat(ip.input())
        .isEqualTo("foo");
        var fresh = parser.parse("foo");
        assertCstEquivalent(result.cst(), fresh.cst());
        assertThat(ip.diagnostics())
        .isEqualTo(fresh.diagnostics());
    }

    @Test
    void parseResult_returnedFromEdit_isSameAsCurrent() {
        var ip = new IncrementalParser(parser, "foo");
        ParseResult result = ip.edit(3, 0, ", bar");
        assertThat(result.cst())
        .isSameAs(ip.current());
        assertThat(result.cst()
                         .tokens())
        .isSameAs(ip.currentTokens());
    }

    @Test
    void defaultCheckpointRules_areExposed() {
        var ip = new IncrementalParser(parser, "foo");
        assertThat(ip.checkpointRules())
        .isEqualTo(IncrementalParser.DEFAULT_CHECKPOINT_RULES);
    }

    @Test
    void customCheckpointRules_areStored() {
        var custom = java.util.Set.of("Item", "File");
        var ip = new IncrementalParser(parser, "foo", custom);
        assertThat(ip.checkpointRules())
        .isEqualTo(custom);
    }

    @Test
    void manyEdits_finalStateMatchesFullReparse() {
        // Stress-test windowed splice across a sequence of varied edits.
        var ip = new IncrementalParser(parser, "foo");
        ip.edit(3, 0, ", bar");
        // foo, bar
        ip.edit(0, 3, "bar");
        // bar, bar
        ip.edit(8, 0, ", foo, bar");
        // bar, bar, foo, bar
        ip.edit(5, 3, "foo");
        // bar, foo, foo, bar
        var result = ip.edit(0, 3, "foo");
        // foo, foo, foo, bar
        var expected = "foo, foo, foo, bar";
        var fresh = parser.parse(expected);
        assertThat(ip.input())
        .isEqualTo(expected);
        assertCstEquivalent(result.cst(), fresh.cst());
        assertCstEquivalent(ip.current(), fresh.cst());
        assertThat(ip.diagnostics())
        .isEqualTo(fresh.diagnostics());
    }

    // === Phase D.1.2 — partial-reparse path tests ===
    @Test
    void editInsideItemCheckpoint_takesPartialReparsePath() {
        // Use "Item" as checkpoint so the partial path fires for edits inside an Item.
        // PARTIAL_GRAMMAR's Item is "(Word)"; we edit the Word inside the second one.
        var ip = new IncrementalParser(partialParser, "(foo), (bar)", java.util.Set.of("Item"));
        var partialBefore = ip.partialReparseCount();
        var fullBefore = ip.fullReparseCount();
        // Replace "bar" with "foo" inside the second Item.
        var result = ip.edit(8, 3, "foo");
        assertThat(ip.partialReparseCount())
        .isGreaterThan(partialBefore);
        assertThat(ip.fullReparseCount())
        .isEqualTo(fullBefore);
        assertThat(ip.input())
        .isEqualTo("(foo), (foo)");
        // The CST after partial reparse must equal a full fresh reparse.
        assertCstEquivalent(result.cst(),
                            partialParser.parse("(foo), (foo)")
                                         .cst());
        assertThat(ip.diagnostics())
        .isEqualTo(partialParser.parse("(foo), (foo)")
                                .diagnostics());
    }

    @Test
    void editOutsideAnyCheckpoint_fallsBackToFullReparse() {
        // No rule in the grammar matches DEFAULT_CHECKPOINT_RULES, so every edit
        // goes through the full-reparse fallback.
        var ip = new IncrementalParser(parser, "foo, bar");
        var partialBefore = ip.partialReparseCount();
        var fullBefore = ip.fullReparseCount();
        ip.edit(5, 3, "foo");
        assertThat(ip.partialReparseCount())
        .isEqualTo(partialBefore);
        assertThat(ip.fullReparseCount())
        .isGreaterThan(fullBefore);
    }

    @Test
    void multipleEditsInsideItem_usePartialPathRepeatedly() {
        var ip = new IncrementalParser(partialParser, "(foo), (bar), (foo)", java.util.Set.of("Item"));
        ip.edit(8, 3, "foo");
        // (foo), (foo), (foo)
        ip.edit(15, 3, "bar");
        // (foo), (foo), (bar)
        var result = ip.edit(1, 3, "bar");
        // (bar), (foo), (bar)
        assertThat(ip.partialReparseCount())
        .isGreaterThanOrEqualTo(2);
        var fresh = partialParser.parse("(bar), (foo), (bar)");
        assertCstEquivalent(result.cst(), fresh.cst());
    }

    @Test
    void partialReparse_preservesUnaffectedSiblings() {
        // After a partial reparse inside the second Item, the first and third Items
        // should still be present in the spliced CST with the same kind names and
        // text.
        var ip = new IncrementalParser(partialParser, "(foo), (bar), (foo)", java.util.Set.of("Item"));
        ip.edit(8, 3, "foo");
        var cst = ip.current();
        var root = cst.rootIndex();
        // root is _ROOT; its first child is File.
        var file = cst.firstChildAt(root);
        var items = new java.util.ArrayList<String>();
        for (var c = cst.firstChildAt(file); c != CstArray.NO_NODE; c = cst.nextSiblingAt(c)) {
            if (cst.kindNameAt(c)
                   .equals("Item")) {
                items.add(cst.textAt(c)
                             .toString());
            }
        }
        assertThat(items)
        .containsExactly("(foo)",
                         "(foo)",
                         "(foo)");
    }

    @Test
    void partialReparse_smallEditOnLargeInput_finishesQuickly() {
        // Build a large input: 1000 Items separated by ", ".
        var sb = new StringBuilder();
        for (int i = 0; i < 1000; i++ ) {
            if (i > 0) sb.append(", ");
            sb.append((i % 2 == 0)
                      ? "(foo)"
                      : "(bar)");
        }
        var input = sb.toString();
        var ip = new IncrementalParser(partialParser, input, java.util.Set.of("Item"));
        // Warm up.
        for (int i = 0; i < 3; i++ ) {
            ip.edit(1, 3, (i % 2 == 0)
                         ? "bar"
                         : "foo");
        }
        var partialBefore = ip.partialReparseCount();
        var t0 = System.nanoTime();
        ip.edit(1, 3, "foo");
        var elapsedMs = (System.nanoTime() - t0) / 1_000_000.0;
        assertThat(ip.partialReparseCount())
        .isGreaterThan(partialBefore);
        // Generous gate (CI may be slow): partial reparse should be quick.
        assertThat(elapsedMs)
        .as("small edit should complete in <100ms, was %.3fms", elapsedMs)
        .isLessThan(100.0);
        // For the report, print the actual time.
        System.out.println("[D.1.2] small edit on 1000-item input: " + elapsedMs + " ms");
    }

    // === snapshot / restore ===
    @Test
    void snapshot_capturesCurrentReferences() {
        var ip = new IncrementalParser(parser, "foo, bar");
        var snap = ip.snapshot();
        assertThat(snap.input())
        .isEqualTo(ip.input());
        assertThat(snap.tokens())
        .isSameAs(ip.currentTokens());
        assertThat(snap.cst())
        .isSameAs(ip.current());
        assertThat(snap.diagnostics())
        .isSameAs(ip.diagnostics());
    }

    @Test
    void restore_afterEdit_rollsBackState() {
        var ip = new IncrementalParser(parser, "foo, bar");
        var snap = ip.snapshot();
        var initialInput = ip.input();
        var initialCst = ip.current();
        var initialTokens = ip.currentTokens();
        var initialDiagnostics = ip.diagnostics();
        ip.edit(5, 3, "foo");
        assertThat(ip.input())
        .isEqualTo("foo, foo");
        // Roll back.
        ip.restore(snap);
        assertThat(ip.input())
        .isEqualTo(initialInput);
        assertThat(ip.current())
        .isSameAs(initialCst);
        assertThat(ip.currentTokens())
        .isSameAs(initialTokens);
        assertThat(ip.diagnostics())
        .isSameAs(initialDiagnostics);
        // After restore, the same edit must produce the same post-edit state.
        var afterRestoreEdit = ip.edit(5, 3, "foo");
        var fresh = parser.parse("foo, foo");
        assertCstEquivalent(afterRestoreEdit.cst(), fresh.cst());
    }

    @Test
    void partialAndFullPaths_produceIdenticalResults() {
        // Same edit applied via partial path (with Item checkpoint) and full
        // path (no checkpoint). The CST and diagnostics must be byte-for-byte
        // identical.
        var partial = new IncrementalParser(partialParser, "(foo), (bar)", java.util.Set.of("Item"));
        var full = new IncrementalParser(partialParser, "(foo), (bar)");
        partial.edit(8, 3, "foo");
        full.edit(8, 3, "foo");
        // Confirm we actually exercised both paths.
        assertThat(partial.partialReparseCount())
        .isGreaterThan(0);
        assertThat(full.fullReparseCount())
        .isGreaterThan(0);
        assertCstEquivalent(partial.current(), full.current());
        assertThat(partial.diagnostics())
        .isEqualTo(full.diagnostics());
    }
}
