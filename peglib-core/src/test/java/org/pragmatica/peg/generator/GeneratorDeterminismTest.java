package org.pragmatica.peg.generator;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.lexer.DfaBuilder;
import org.pragmatica.peg.lexer.RuleClassifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.7.3 — generated source must be reproducible.
 *
 * <p>Two runs of the SAME commit against the SAME grammar used to emit different
 * {@code GLexer.java}. Measured before the fix: five fresh JVMs produced four distinct
 * SHA-256 hashes of the generated source for {@code java25.peg}, and every one of the 108
 * differing lines was a {@code RESOLVERS} {@code .put(...)} line.
 *
 * <p>The cause was {@link java.util.Map#copyOf}, whose iteration order is deliberately
 * randomised per JVM run. {@code DfaBuilder} built each of these maps as a
 * {@code LinkedHashMap} — the order-preserving intent was there — and then handed it to
 * {@code Map.copyOf}, which discarded it. {@code renderResolvers} iterates the result.
 *
 * <p>It matters because peglib asks consumers to detect stale generated sources by comparing
 * content: output that changes on its own defeats that, including the generator stamping added
 * in 0.7.2.
 *
 * <h2>Why these tests do not simply generate twice and compare</h2>
 *
 * <p>Because that test PASSES against the bug. The randomisation seed is chosen once per JVM,
 * so two calls inside one test JVM see the same "random" order and agree with each other. A
 * cross-JVM comparison would catch it but cannot be written as a unit test.
 *
 * <p>So these assert the mechanism instead: emitted order equals the order the grammar declares
 * its keywords in. That is a property of the map, holds in any JVM, and is false the moment a
 * randomising copy is reintroduced anywhere on the path.
 */
class GeneratorDeterminismTest {
    /**
     * Keywords are declared in an order that is neither alphabetical nor {@code String} hash
     * order, so matching it is evidence of insertion order specifically rather than of some
     * other stable-but-accidental arrangement.
     */
    private static final List<String> DECLARED = List.of("zeta",
                                                         "alpha",
                                                         "omicron",
                                                         "beta",
                                                         "upsilon",
                                                         "gamma",
                                                         "psi",
                                                         "delta",
                                                         "kappa",
                                                         "epsilon");

    private static final String GRAMMAR = """
        Doc     <- Word+
        Word    <- !Keyword < [a-z]+ >
        Keyword <- 'zeta' / 'alpha' / 'omicron' / 'beta' / 'upsilon' / 'gamma' / 'psi' / 'delta' / 'kappa' / 'epsilon'
        %whitespace <- [ \\t\\r\\n]*
        """;

    private static final Pattern PUT = Pattern.compile("\\.put\\(\"([a-z]+)\"");

    private static String generate() {
        var grammar = GrammarParser.parse(GRAMMAR)
                                   .unwrap();
        var classification = RuleClassifier.classify(grammar)
                                           .unwrap();
        var built = DfaBuilder.build(grammar, classification)
                              .unwrap();

        return LexerGenerator.generate(grammar,
                                       classification,
                                       built.dfa(),
                                       built.kinds(),
                                       "det",
                                       "GLexerDet")
                             .unwrap()
                             .source();
    }

    private static List<String> emittedOrder(String source) {
        var matcher = PUT.matcher(source);
        var found = new ArrayList<String>();

        while (matcher.find()) {
            found.add(matcher.group(1));
        }

        return found;
    }

    @Test
    void resolverEntriesAreEmittedInGrammarDeclarationOrder() {
        // The load-bearing assertion. Under Map.copyOf this order is a per-JVM permutation of
        // DECLARED; the chance of a random permutation of ten elements matching declaration
        // order is 1 in 3,628,800, so this does not pass by luck.
        assertThat(emittedOrder(generate())).containsExactlyElementsOf(DECLARED);
    }

    @Test
    void keywordResolutionMapItselfPreservesInsertionOrder() {
        // Asserted one level below the generator, so the property survives someone rewriting
        // renderResolvers. This is the map whose iteration order the emitted source is made of.
        var grammar = GrammarParser.parse(GRAMMAR)
                                   .unwrap();
        var classification = RuleClassifier.classify(grammar)
                                           .unwrap();
        var kinds = DfaBuilder.build(grammar, classification)
                              .unwrap()
                              .kinds();

        assertThat(kinds.keywordResolutions()).hasSize(1);

        var resolution = kinds.keywordResolutions()
                              .values()
                              .iterator()
                              .next();

        assertThat(resolution.textToKind()
                             .keySet()).containsExactlyElementsOf(DECLARED);
    }

    @Test
    void repeatedGenerationInThisJvmAgrees() {
        // Weak on its own — it passes against the bug, which is exactly why the two tests above
        // exist. Kept because it is the only check that covers sources of run-to-run variation
        // OTHER than map ordering: a timestamp, a hash code, an identity string.
        assertThat(generate()).isEqualTo(generate());
    }
}
