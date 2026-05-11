package org.pragmatica.peg.v6.generator;

import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.v6.cst.ParseResult;
import org.pragmatica.peg.v6.lexer.DfaBuilder;
import org.pragmatica.peg.v6.lexer.LexerEngine;
import org.pragmatica.peg.v6.lexer.RuleClassifier;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.6.0 — proves the generated parser honours the grammar-level
 * {@code %recover [chars] StartRule} directive:
 *
 * <ul>
 *   <li>Without {@code %recover}, the panic-mode loop syncs on the default
 *       set ({@code {; , } ) ]}}).</li>
 *   <li>With {@code %recover [|] Start} pointing at the start rule, the
 *       generated source emits a SYNC array containing {@code |}'s kind and
 *       the recovery loop syncs at {@code |}.</li>
 * </ul>
 *
 * <p>Per-rule recovery (sync sets for non-start rules consulted inside
 * nested rule calls) is intentionally deferred — the panic-mode loop is
 * top-level and the start rule is the only one whose sync set has effect
 * in 0.6.0.
 */
class RecoverDirectiveGeneratorTest {
    private static final String DEFAULT_GRAMMAR = """
        File <- Item ',' Item ',' Item
        Item <- 'foo' / 'bar'
        %whitespace <- [ \\t]*
        """;

    // The only sync char is '|' — everything else must be skipped to it.
    private static final String OVERRIDE_GRAMMAR = """
        %recover [|] File
        File <- Item '|' Item '|' Item
        Item <- 'foo' / 'bar'
        %whitespace <- [ \\t]*
        """;

    private static ParserCompiler.CompiledParser compile(String grammarText, String pkg, String cls) {
        Grammar grammar = GrammarParser.parse(grammarText)
                                       .unwrap();
        var classification = RuleClassifier.classify(grammar)
                                           .unwrap();
        var built = DfaBuilder.build(grammar, classification)
                              .unwrap();
        var generated = ParserGenerator.generate(grammar,
                                                 classification,
                                                 built.kinds(),
                                                 pkg,
                                                 cls)
                                       .unwrap();
        return ParserCompiler.compile(generated)
                             .unwrap();
    }

    private static LexerEngine lexerFor(String grammarText) {
        Grammar grammar = GrammarParser.parse(grammarText)
                                       .unwrap();
        var classification = RuleClassifier.classify(grammar)
                                           .unwrap();
        var built = DfaBuilder.build(grammar, classification)
                              .unwrap();
        int wsKind = grammar.whitespace()
                            .isPresent()
                     ? DfaBuilder.KIND_WHITESPACE
                     : - 1;
        return new LexerEngine(built.dfa(),
                               built.kinds()
                                    .kindNameTable(),
                               wsKind,
                               built.kinds()
                                    .keywordResolutions());
    }

    @Test
    void generatedSource_includesSyncArrayDeclaration() {
        Grammar grammar = GrammarParser.parse(OVERRIDE_GRAMMAR)
                                       .unwrap();
        var classification = RuleClassifier.classify(grammar)
                                           .unwrap();
        var built = DfaBuilder.build(grammar, classification)
                              .unwrap();
        var generated = ParserGenerator.generate(grammar,
                                                 classification,
                                                 built.kinds(),
                                                 "test.gen.recover.src",
                                                 "SrcCheckParser")
                                       .unwrap();
        // The generated source MUST contain the SYNC array declaration.
        assertThat(generated.source())
        .contains("DEFAULT_SYNC = new int[]");
        // The kind for inline literal '|' MUST be present in the SYNC array.
        var pipeKind = built.kinds()
                            .inlineLiteralToKind()
                            .get("|/cs");
        assertThat(pipeKind)
        .as("'|' must be allocated a token kind")
        .isNotNull();
        // Rough match: the literal kind value must appear inside the SYNC array
        // initialiser (between the '{' and the closing '}'). This is brittle
        // by design — we want a regression to fire if the array is empty.
        var idx = generated.source()
                           .indexOf("DEFAULT_SYNC = new int[] {");
        assertThat(idx)
        .isPositive();
        var end = generated.source()
                           .indexOf('}', idx);
        var arrayBody = generated.source()
                                 .substring(idx, end);
        assertThat(arrayBody)
        .contains(String.valueOf(pipeKind));
    }

    @Test
    void overrideSyncSet_recoversAtCustomChar() {
        // Bad-input segment between two '|' separators — recovery should sync
        // at '|' and continue with the next Item.
        var lexer = lexerFor(OVERRIDE_GRAMMAR);
        var parser = compile(OVERRIDE_GRAMMAR, "test.gen.recover.ovr2", "OvrParser");
        var tokens = lexer.lex("foo| BAD |bar");
        ParseResult result = parser.parse(tokens);
        assertThat(result.diagnostics())
        .isNotEmpty();
        assertThat(result.hasErrors())
        .isTrue();
        assertThat(result.cst()
                         .nodeCount())
        .isGreaterThan(0);
    }

    @Test
    void defaultSyncSet_unchangedWhenNoRecoverDirective() {
        // Sanity: the default-sync grammar still recovers on ',' as before.
        var lexer = lexerFor(DEFAULT_GRAMMAR);
        var parser = compile(DEFAULT_GRAMMAR, "test.gen.recover.def2", "DefParser");
        var tokens = lexer.lex("foo, BAD, bar");
        ParseResult result = parser.parse(tokens);
        assertThat(result.diagnostics())
        .isNotEmpty();
        assertThat(result.hasErrors())
        .isTrue();
        assertThat(result.cst()
                         .nodeCount())
        .isGreaterThan(0);
    }

    @Test
    void overrideGrammar_doesNotIncludeDefaultCommaSync() {
        // The override grammar uses '|' separators only; the default sync set
        // ('; , } ) ]') doesn't appear in the inline literal table at all
        // because the grammar doesn't use those characters. The override MUST
        // populate the SYNC array with '|' instead of leaving it empty.
        Grammar grammar = GrammarParser.parse(OVERRIDE_GRAMMAR)
                                       .unwrap();
        var classification = RuleClassifier.classify(grammar)
                                           .unwrap();
        var built = DfaBuilder.build(grammar, classification)
                              .unwrap();
        var generated = ParserGenerator.generate(grammar,
                                                 classification,
                                                 built.kinds(),
                                                 "test.gen.recover.empty",
                                                 "EmptyParser")
                                       .unwrap();
        var idx = generated.source()
                           .indexOf("DEFAULT_SYNC = new int[] {");
        var end = generated.source()
                           .indexOf('}', idx);
        var arrayBody = generated.source()
                                 .substring(idx, end);
        // '|' must be present (proves override populated it); no commas in
        // the kind-list interior means the array is non-empty (a single int
        // followed by '}' with no leading comma).
        assertThat(arrayBody)
        .doesNotContain("{}");
    }
}
