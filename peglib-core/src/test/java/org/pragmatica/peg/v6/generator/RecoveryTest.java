package org.pragmatica.peg.v6.generator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.cst.ParseResult;
import org.pragmatica.peg.v6.lexer.DfaBuilder;
import org.pragmatica.peg.v6.lexer.LexerEngine;
import org.pragmatica.peg.v6.lexer.RuleClassifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B.4 — panic-mode recovery in the generated parser.
 *
 * <p>The synthetic grammar uses a fixed-length sequence so that failures bubble
 * up to the start rule instead of being silently swallowed by a ZeroOrMore /
 * Optional / Choice wrapper (PEG's natural exception-eating constructs):
 * <pre>{@code
 *   File <- Item ',' Item ',' Item
 *   Item <- 'foo' / 'bar'
 *   %whitespace <- [ \t]*
 * }</pre>
 *
 * <p>The default sync set includes {@code ','} so each test exercises the
 * recovery loop's expected behaviour: roll back partial CST, advance to the
 * next comma (or EOF), emit Error node, resume parsing.
 */
class RecoveryTest {

    private static final String GRAMMAR = """
        File <- Item ',' Item ',' Item
        Item <- 'foo' / 'bar'
        %whitespace <- [ \\t]*
        """;

    private static LexerEngine lexer;
    private static ParserCompiler.CompiledParser parser;

    @BeforeAll
    static void setup() {
        Grammar grammar = GrammarParser.parse(GRAMMAR).unwrap();
        var classification = RuleClassifier.classify(grammar).unwrap();
        var built = DfaBuilder.build(grammar, classification).unwrap();
        int wsKind = grammar.whitespace().isPresent() ? DfaBuilder.KIND_WHITESPACE : -1;
        lexer = new LexerEngine(built.dfa(), built.kinds().kindNameTable(), wsKind,
            built.kinds().keywordResolutions());
        var generated = ParserGenerator.generate(grammar, classification, built.kinds(),
            "test.gen.parser.recovery", "RecoveryParser").unwrap();
        parser = ParserCompiler.compile(generated).unwrap();
    }

    private static int errorNodeCount(CstArray cst) {
        int count = 0;
        for (int i = 0; i < cst.nodeCount(); i++) {
            if (cst.isError(i)) {
                count++;
            }
        }
        return count;
    }

    @Test
    void cleanInput_noDiagnostics() {
        var tokens = lexer.lex("foo, bar, foo");
        ParseResult result = parser.parse(tokens);

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.isSuccess()).isTrue();
        // Phase B.3.1: rootIndex points at synthetic "_ROOT" wrapper; the start
        // rule "File" is the first child on a clean parse.
        var cst = result.cst();
        assertThat(cst.kindNameAt(cst.rootIndex())).isEqualTo("_ROOT");
        int file = cst.firstChildAt(cst.rootIndex());
        assertThat(file).isNotEqualTo(CstArray.NO_NODE);
        assertThat(cst.kindNameAt(file)).isEqualTo("File");
        assertThat(errorNodeCount(cst)).isZero();
    }

    @Test
    void singleBadTokenBetweenCommas_recoversAndContinues() {
        // 'foo, BAD, bar' — the lexer emits ANY_CHAR tokens for 'BAD'; the second
        // Item fails at 'B'. ParseException propagates to the recovery loop,
        // which walks to the next comma, emits an Error node, and retries from
        // after the comma.
        var tokens = lexer.lex("foo, BAD, bar");
        ParseResult result = parser.parse(tokens);

        assertThat(result.diagnostics()).isNotEmpty();
        assertThat(result.hasErrors()).isTrue();
        assertThat(errorNodeCount(result.cst())).isGreaterThanOrEqualTo(1);
        assertThat(result.cst().nodeCount()).isGreaterThan(0);
    }

    @Test
    void unexpectedTokenWithoutCommaSync_recoversByConsumingToEnd() {
        // 'BAD foo' — no commas at all. The first Item fails at 'B'; recovery
        // can find no sync token, so it emits an Error node spanning to EOF
        // and stops. At least one diagnostic is recorded.
        var tokens = lexer.lex("BAD foo");
        ParseResult result = parser.parse(tokens);

        assertThat(result.diagnostics()).isNotEmpty();
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.cst().nodeCount()).isGreaterThan(0);
    }

    @Test
    void multipleBadSegments_collectMultipleDiagnostics() {
        // 'BAD, BAD, foo' — every Item up to the last one fails. Each recovery
        // iteration walks past one comma, emits an Error node, and retries the
        // start rule. At least two diagnostics accumulate.
        var tokens = lexer.lex("BAD, BAD, foo");
        ParseResult result = parser.parse(tokens);

        assertThat(result.diagnostics()).isNotEmpty();
        assertThat(result.diagnostics().size()).isGreaterThanOrEqualTo(2);
        assertThat(errorNodeCount(result.cst())).isGreaterThanOrEqualTo(2);
    }

    @Test
    void emptyInput_producesSingleDiagnostic() {
        // Grammar requires Item to start so empty input fails. Recovery has no
        // sync token and no forward progress to make, so it synthesises an empty
        // root Error node and records exactly one diagnostic.
        var tokens = lexer.lex("");
        ParseResult result = parser.parse(tokens);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.diagnostics()).hasSize(1);
        assertThat(result.cst().nodeCount()).isGreaterThan(0);
    }
}
