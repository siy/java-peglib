package org.pragmatica.peg.bench;

import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.error.RecoveryStrategy;
import org.pragmatica.peg.generator.ErrorReporting;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.parser.ParserConfig;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Profiling input for commit #10's refinement of the {@code phase1_allStructural_skipPackrat}
 * skip-set. Generates the Java 25 parser with {@code packratEnabled=true} and an empty
 * skip-set, parses the large fixture once, and reports per-rule cache puts derived from the
 * emitted parser's {@code cache} field.
 *
 * <p>The generator emits keys as {@code ((long) ruleId << 32) | position} (see
 * {@code ParserGenerator#cacheKey}). We reflect into the {@code cache} field, decode the high
 * 32 bits as a rule ordinal, and map ordinals to rule names via the same grammar used to
 * generate the parser.
 *
 * <p>Limitation: we can count puts per rule (each entry in the cache is one successful put),
 * but not gets or hits — those are not materialized in cache state. Reporting puts lets us
 * eyeball which rules pay the cache cost at all and shortlist candidates for the skip-set.
 */
public final class PackratStatsProbe {

    private static final String PACKAGE_NAME = "generated.bench.probe";
    private static final String CLASS_NAME = "Java25ProbeParser";
    private static final String GRAMMAR_RESOURCE = "/java25.peg";
    private static final String FIXTURE_RESOURCE = "/perf-corpus/large/FactoryClassGenerator.java.txt";

    private PackratStatsProbe() {}

    /**
     * JBCT boundary: dev-tool CLI entry point. The probe is invoked by hand
     * (or from JMH benchmark scripts) so the {@code throws Exception}
     * signature is intentional — failures should fast-fail the JVM with a
     * stack trace rather than be reflected in a {@code Result} channel.
     * Production code paths in {@link PegParser} and {@link GrammarParser}
     * still surface failures as {@code Result.failure}; this main only
     * adapts those typed results to a probe-tool exit.
     */
    public static void main(String[] args) throws Exception {
        var grammarText = Java25ParseBenchmark.loadResource(GRAMMAR_RESOURCE);
        var fixtureSource = Java25ParseBenchmark.loadResource(FIXTURE_RESOURCE);

        var grammar = GrammarParser.parse(grammarText)
                                   .unwrap();
        var ruleNames = grammar.rules().stream().map(r -> r.name()).toList();

        var config = new ParserConfig(
                true,                       // packratEnabled
                RecoveryStrategy.BASIC,
                true,                       // captureTrivia
                true, true, true, true, true, true,  // phase-1 flags on
                false, false, false,        // structural flags off
                false,                      // selectivePackrat
                Set.of());

        var sourceResult = PegParser.generateCstParser(
                grammarText, PACKAGE_NAME, CLASS_NAME, ErrorReporting.BASIC, config);
        if (sourceResult.isFailure()) {
            throw new IllegalStateException("generateCstParser failed: " + sourceResult);
        }
        var fqcn = PACKAGE_NAME + "." + CLASS_NAME;
        var parserClass = Java25ParseBenchmark.compileAndLoad(sourceResult.unwrap(), fqcn);
        var parseMethod = parserClass.getMethod("parse", String.class);

        var instance = parserClass.getDeclaredConstructor().newInstance();
        long t0 = System.nanoTime();
        var rawResult = parseMethod.invoke(instance, fixtureSource);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        Field cacheField = parserClass.getDeclaredField("cache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var cache = (Map<Long, ?>) cacheField.get(instance);

        int totalPuts = 0;
        var putsPerRuleId = new HashMap<Integer, Integer>();
        if (cache != null) {
            for (Long key : cache.keySet()) {
                int ruleId = (int) (key.longValue() >>> 32);
                putsPerRuleId.merge(ruleId, 1, Integer::sum);
                totalPuts++;
            }
        }

        System.out.println("=== PackratStatsProbe ===");
        System.out.println("parse elapsed: " + elapsedMs + " ms");
        System.out.println("parse result type: " + (rawResult == null ? "null" : rawResult.getClass().getName()));
        System.out.println("cache size (total puts): " + totalPuts);
        System.out.println("distinct rule ordinals in cache: " + putsPerRuleId.size());
        System.out.println();
        System.out.println("ruleName : puts   (gets/hits not instrumented — see class doc)");
        System.out.println("------------------------------------------------------------");

        putsPerRuleId.entrySet()
                     .stream()
                     .sorted(Comparator.<Map.Entry<Integer, Integer>>comparingInt(Map.Entry::getValue).reversed())
                     .forEach(entry -> {
                         int ordinal = entry.getKey();
                         String name = (ordinal >= 0 && ordinal < ruleNames.size())
                                       ? ruleNames.get(ordinal)
                                       : "<ordinal#" + ordinal + ">";
                         System.out.printf("%-40s : %d%n", name, entry.getValue());
                     });
    }
}
