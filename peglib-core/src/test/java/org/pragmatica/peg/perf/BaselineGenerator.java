package org.pragmatica.peg.perf;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Walks {@code src/test/resources/perf-corpus/}, parses each file with the cached generated
 * Java 25 parser, and writes baseline artifacts under
 * {@code src/test/resources/perf-corpus-baseline/}:
 *
 * <ul>
 *   <li>{@code <relative>.hash} — 16-char lowercase hex CST hash</li>
 *   <li>{@code <relative>.ruleHits.txt} — lines of {@code ruleName\tcount}, sorted asc</li>
 *   <li>{@code ruleCoverage.txt} — aggregate across all files, with coverage header</li>
 * </ul>
 *
 * <p>Run via {@code main(...)} or via {@link BaselineGeneratorRunner} in the test suite.
 */
public final class BaselineGenerator {

    private static final Path CORPUS_ROOT = Path.of("src/test/resources/perf-corpus");
    private static final Path BASELINE_ROOT = Path.of("src/test/resources/perf-corpus-baseline");
    private static final Pattern RULE_DEF = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*<-", Pattern.MULTILINE);

    private BaselineGenerator() {}

    public static void main(String[] args) throws IOException {
        var report = run();
        System.out.println(report);
    }

    public static String run() throws IOException {
        GeneratedJava25Parser.ensureLoaded();
        Files.createDirectories(BASELINE_ROOT);

        var files = collectCorpusFiles();
        var aggregate = new TreeMap<String, long[]>();  // ruleName -> [totalCount, fileCount]
        int okFiles = 0;
        int failedFiles = 0;
        var problems = new StringBuilder();

        for (var file : files) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Object cst;
            try {
                cst = GeneratedJava25Parser.parseToCst(source);
            } catch (RuntimeException ex) {
                failedFiles++;
                problems.append("PARSE FAILED: ").append(file).append(" -> ").append(ex.getMessage()).append('\n');
                continue;
            }

            String hash = CstHash.of(cst);
            var ruleHits = new TreeMap<String, Long>();
            countRuleHits(cst, ruleHits);

            Path relative = CORPUS_ROOT.relativize(file);
            Path hashFile = BASELINE_ROOT.resolve(relative + ".hash");
            Path hitsFile = BASELINE_ROOT.resolve(relative + ".ruleHits.txt");
            Files.createDirectories(hashFile.getParent());
            Files.writeString(hashFile, hash + "\n", StandardCharsets.UTF_8);

            var hitsOut = new StringBuilder();
            for (var e : ruleHits.entrySet()) {
                hitsOut.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            }
            Files.writeString(hitsFile, hitsOut.toString(), StandardCharsets.UTF_8);

            for (var e : ruleHits.entrySet()) {
                var entry = aggregate.computeIfAbsent(e.getKey(), _ -> new long[]{0L, 0L});
                entry[0] += e.getValue();
                entry[1] += 1;
            }
            okFiles++;
        }

        int grammarRuleCount = countGrammarRules();
        int rulesHit = aggregate.size();
        double coverage = grammarRuleCount == 0 ? 0.0 : 100.0 * rulesHit / grammarRuleCount;

        var coverageOut = new StringBuilder();
        coverageOut.append(String.format(
            "# total rules hit: %d / total rules in grammar: %d (coverage: %.1f%%)%n",
            rulesHit, grammarRuleCount, coverage));
        for (var e : aggregate.entrySet()) {
            coverageOut.append(e.getKey())
                       .append('\t').append(e.getValue()[0])
                       .append('\t').append(e.getValue()[1])
                       .append('\n');
        }
        Files.writeString(BASELINE_ROOT.resolve("ruleCoverage.txt"), coverageOut.toString(), StandardCharsets.UTF_8);

        var unfired = listUnfiredRules(aggregate.keySet());

        var report = new StringBuilder();
        report.append("Corpus files processed: ").append(okFiles).append('\n');
        report.append("Corpus files failed:    ").append(failedFiles).append('\n');
        report.append(String.format("Rule coverage: %d / %d (%.1f%%)%n", rulesHit, grammarRuleCount, coverage));
        if (!unfired.isEmpty()) {
            report.append("Unfired rules (").append(unfired.size()).append("): ")
                  .append(String.join(", ", unfired)).append('\n');
        }
        if (problems.length() > 0) {
            report.append("Problems:\n").append(problems);
        }
        return report.toString();
    }

    public static List<Path> collectCorpusFiles() throws IOException {
        if (!Files.isDirectory(CORPUS_ROOT)) {
            throw new IllegalStateException("Corpus root does not exist: " + CORPUS_ROOT.toAbsolutePath());
        }
        try (Stream<Path> walk = Files.walk(CORPUS_ROOT)) {
            return walk.filter(Files::isRegularFile)
                       .sorted()
                       .toList();
        }
    }

    private static int countGrammarRules() {
        var grammar = GeneratedJava25Parser.loadGrammar();
        var matcher = RULE_DEF.matcher(grammar);
        var names = new java.util.HashSet<String>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names.size();
    }

    private static List<String> listUnfiredRules(java.util.Set<String> firedRules) {
        var grammar = GeneratedJava25Parser.loadGrammar();
        var matcher = RULE_DEF.matcher(grammar);
        var all = new java.util.TreeSet<String>();
        while (matcher.find()) {
            all.add(matcher.group(1));
        }
        all.removeAll(firedRules);
        return new java.util.ArrayList<>(all);
    }

    /** Walks a CST (via reflection) and increments counters by rule name. */
    public static void countRuleHits(Object node, Map<String, Long> counts) {
        if (node == null) return;
        String simple = node.getClass().getSimpleName();
        String ruleName = resolveRuleName(node, simple);
        counts.merge(ruleName, 1L, Long::sum);
        if ("NonTerminal".equals(simple)) {
            var children = (List<?>) invoke(node, "children");
            for (var child : children) {
                countRuleHits(child, counts);
            }
        }
    }

    private static String resolveRuleName(Object node, String simple) {
        if ("Error".equals(simple)) {
            return "<error>";
        }
        var r = invoke(node, "rule");
        if (r == null) return "<error>";
        if (r instanceof String s) return s;
        var nameMethod = findMethod(r.getClass(), "name");
        try {
            return (String) nameMethod.invoke(r);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to resolve rule name", e);
        }
    }

    private static Object invoke(Object target, String methodName) {
        var method = findMethod(target.getClass(), methodName);
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to invoke " + methodName + "() on " + target.getClass(), e);
        }
    }

    private static Method findMethod(Class<?> cls, String name) {
        try {
            var m = cls.getMethod(name);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("No method " + name + "() on " + cls, e);
        }
    }
}
