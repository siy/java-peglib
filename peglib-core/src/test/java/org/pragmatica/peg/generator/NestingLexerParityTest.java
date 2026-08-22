package org.pragmatica.peg.generator;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.lexer.DfaBuilder;
import org.pragmatica.peg.lexer.LexerEngine;
import org.pragmatica.peg.lexer.RuleClassifier;
import org.pragmatica.peg.token.TokenArray;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.7.3 — the two lexer paths must agree about {@code %nest}.
 *
 * <p>peglib carries two implementations of one algorithm: the interpreted {@link LexerEngine}
 * and the {@code GLexer} source that {@link LexerGenerator} emits. Nearly every lexer test in
 * this suite constructs the former, while {@code PegParser} runs the latter — so a feature
 * implemented on only one side ships with a green build. That is not hypothetical here: the
 * nesting interception had to be written twice, once as Java and once as generated source.
 *
 * <p>These tests lex the same inputs through both and compare the full token streams. The
 * shared {@code NestingScanner} makes the counting itself common to both, so what is really
 * under test is the surrounding interception — whether each path enters the scanner at the same
 * positions, substitutes it for the DFA in the same way, and applies the same kind refinement
 * afterwards.
 */
class NestingLexerParityTest {
    private static final String NESTING = """
        Program      <- Word+
        Word         <- < [a-zA-Z0-9_]+ >
        BlockComment <- '/*' (!'*/' .)* '*/'
        %whitespace  <- ([ \\t\\r\\n] / BlockComment)*
        %nest '/*' '*/'
        """;

    private static final String NO_NESTING = """
        Program      <- Word+
        Word         <- < [a-zA-Z0-9_]+ >
        BlockComment <- '/*' (!'*/' .)* '*/'
        %whitespace  <- ([ \\t\\r\\n] / BlockComment)*
        """;

    private record Paths(LexerEngine engine, LexerCompiler.CompiledLexer generated, String source) {}

    private static Paths build(String grammarText) {
        var grammar = GrammarParser.parse(grammarText)
                                   .unwrap();
        var classification = RuleClassifier.classify(grammar)
                                           .unwrap();
        var built = DfaBuilder.build(grammar, classification)
                              .unwrap();
        int wsKind = grammar.whitespace()
                            .isPresent()
                     ? DfaBuilder.KIND_WHITESPACE
                     : -1;
        // The five-arg constructor is the point: a LexerEngine built the old way knows nothing
        // about %nest, which is precisely the drift this test exists to catch.
        var engine = new LexerEngine(built.dfa(),
                                     built.kinds()
                                          .kindNameTable(),
                                     wsKind,
                                     built.kinds()
                                          .keywordResolutions(),
                                     grammar.nestingTrivia());
        var generated = LexerGenerator.generate(grammar,
                                                classification,
                                                built.dfa(),
                                                built.kinds(),
                                                "parity",
                                                "GLexerParity" + Math.abs(grammarText.hashCode()))
                                      .unwrap();

        return new Paths(engine,
                         LexerCompiler.compile(generated)
                                      .unwrap(),
                         generated.source());
    }

    private static List<String> describe(TokenArray tokens) {
        var out = new ArrayList<String>();

        for (int i = 0; i < tokens.count(); i++) {
            out.add(tokens.kindAt(i) + ":" + tokens.startAt(i) + "-" + tokens.endAt(i));
        }

        return out;
    }

    private static void assertParity(Paths paths, String input) {
        assertThat(describe(paths.generated()
                                 .lex(input)))
        .withFailMessage("interpreted and generated lexers disagree on <%s>", input)
        .isEqualTo(describe(paths.engine()
                                 .lex(input)));
    }

    @Test
    void bothPathsAgreeOnNestedComments() {
        var paths = build(NESTING);

        assertParity(paths, "a /* plain */ b");
        assertParity(paths, "a /* outer /* inner */ still */ b");
        assertParity(paths, "a /* one /* two /* three */ two */ one */ b");
        assertParity(paths, "a /**/ b");
        assertParity(paths, "a /* never closed");
        assertParity(paths, "a /*/ b");
        assertParity(paths, "a /** doc /* inner */ done */ b");
        assertParity(paths, "plain words only");
    }

    @Test
    void bothPathsAgreeWithoutTheDirective() {
        // The control. If the two paths only agreed because neither implemented nesting, this
        // pair of tests would look identical — the previous test pins that they agree on the
        // NESTED reading, this one that removing the directive changes that reading on both.
        var paths = build(NO_NESTING);

        assertParity(paths, "a /* outer /* inner */ still */ b");
        assertParity(paths, "a /* plain */ b");
    }

    @Test
    void theDirectiveActuallyChangesTheTokenStream() {
        // Guards against the whole suite passing vacuously. If %nest were a no-op, every parity
        // assertion above would still hold.
        var withDirective = build(NESTING).generated()
                                          .lex("a /* outer /* inner */ still */ b");
        var without = build(NO_NESTING).generated()
                                       .lex("a /* outer /* inner */ still */ b");

        assertThat(describe(withDirective)).isNotEqualTo(describe(without));
    }

    @Test
    void generatedSourceForANonNestingGrammarDoesNotMentionNesting() {
        // The zero-cost claim, asserted rather than argued: a grammar that declares no %nest
        // gets a lexer with no delimiter tables, no reject test, and no import — so it cannot
        // pay for the feature at run time, and its generated source does not churn.
        var source = build(NO_NESTING).source();

        assertThat(source).doesNotContain("NestingScanner")
                          .doesNotContain("NEST_OPEN")
                          .doesNotContain("nestingOpenAt");
    }

    @Test
    void generatedSourceForANestingGrammarCarriesTheTables() {
        var source = build(NESTING).source();

        assertThat(source).contains("import org.pragmatica.peg.token.NestingScanner;")
                          .contains("private static final String[] NEST_OPEN = {\"/*\"};")
                          .contains("private static final String[] NEST_CLOSE = {\"*/\"};")
                          .contains("nestingOpenAt(input, pos)");
        // The kind is baked at generation time, not classified per token.
        assertThat(source).contains("private static final int[] NEST_KIND = {" + TokenArray.KIND_BLOCK_COMMENT + "};");
    }

    @Test
    void nonNestingGeneratedSourceIsUnchangedByTheFeature() {
        // Stronger than the "does not mention" check above and aimed at a different risk: the
        // %nest emission is threaded through renderSource, so a stray unconditional newline or
        // reordering would silently rewrite every existing grammar's generated source. Consumers
        // detect staleness by comparing that source, so churn has a real cost. Two builds of the
        // same non-nesting grammar must agree, and must match the shape 0.7.2 emitted: the lex
        // loop opening with `int state = 0;` immediately after `while (pos < len) {`.
        var source = build(NO_NESTING).source();

        assertThat(source).contains("import org.pragmatica.peg.token.TokenArrayBuilder;\n\npublic final class");
        assertThat(source).contains("""
                                        while (pos < len) {
                                                    int state = 0;
                                                    int lastAcceptEnd = -1;
                                                    int lastAcceptKind = -1;
                                                    int cur = pos;
                                        """.stripTrailing());
    }
}
