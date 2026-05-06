package org.pragmatica.peg.incremental.bench;

import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.incremental.Edit;
import org.pragmatica.peg.incremental.IncrementalParser;
import org.pragmatica.peg.incremental.Session;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Realistic interactive editing-session benchmark for {@code peglib-incremental}.
 *
 * <p><b>What it measures.</b> A single sequential editing session: 1,000
 * mixed edits applied in order to the 1,900-LOC fixture
 * ({@code FactoryClassGenerator.java.txt}). Each edit's wall-clock latency
 * is recorded individually with {@code System.nanoTime()} and bucketed by
 * edit class. We report median, p95, p99, max, and full-reparse fallback
 * count per class, plus cold-vs-warm split (first 50 vs subsequent edits)
 * and the share of edits that meet the 16 ms frame budget.
 *
 * <p>Two regimes run on the SAME edit sequence (same RNG seed):
 * <ul>
 *   <li><b>Regime B</b> (primary, realistic): cursor moved to the edit
 *       offset before each edit — what real editors do.</li>
 *   <li><b>Regime A</b> (control, pessimistic): cursor stays at offset 0
 *       for the entire session — exposes pivot-overshoot cost.</li>
 * </ul>
 *
 * <p><b>How to run.</b>
 * <pre>{@code
 *   mvn -pl peglib-incremental -Pbench -DskipTests package -q
 *   java -cp peglib-incremental/target/benchmarks.jar \
 *        org.pragmatica.peg.incremental.bench.IncrementalSessionBench
 * }</pre>
 *
 * <p><b>How to interpret the table.</b> All times are in milliseconds.
 * Edit classes:
 * <ul>
 *   <li>{@code single-char} — one-character insert or delete (60% of edits)</li>
 *   <li>{@code word} — short identifier insert or delete (25% of edits)</li>
 *   <li>{@code line} — paste/delete a 20–80 char run (10%)</li>
 *   <li>{@code block} — paste/delete an 80–200 char run (5%)</li>
 * </ul>
 * The {@code Fallbacks} column counts edits that triggered a full reparse
 * (boundary walk reached the root, back-reference rule on the path, or the
 * reparsed subtree failed to validate). The {@code DELTA} section shows
 * the ratio of Regime B / Regime A medians; values &lt; 1 mean
 * cursor-aware editing is faster than the cursor-pinned baseline.
 *
 * <p>The bench is deterministic: the same RNG seed produces the same edit
 * sequence on the same JDK build, so reruns are directly comparable.
 *
 * @since 0.4.4
 */
public final class IncrementalSessionBench {

    private static final String GRAMMAR_RESOURCE = "/java25.peg";
    private static final String FIXTURE_RESOURCE = "/perf-corpus/large/FactoryClassGenerator.java.txt";

    private static final long RNG_SEED = 0xBEEFCAFEL;
    private static final int EDIT_COUNT = 1000;
    private static final int COLD_PREFIX = 50;
    private static final double FRAME_BUDGET_MS = 16.0;

    /** Edit shape, used for per-class bucketing in the report. */
    private enum EditClass { SINGLE_CHAR, WORD, LINE, BLOCK;
        String label() {
            return switch (this) {
                case SINGLE_CHAR -> "single-char";
                case WORD -> "word";
                case LINE -> "line";
                case BLOCK -> "block";
            };
        }
    }

    /** A pre-classified edit, generated up-front so generation cost is excluded from timing. */
    private record ClassifiedEdit(Edit edit, EditClass cls) {}

    /** Per-edit measurement record. {@code -1} latency means the edit was skipped. */
    private record Measurement(
        EditClass cls,
        long latencyNs,
        boolean wasFallback,
        boolean skipped,
        int editOffset,
        int editOldLen,
        int editNewLen,
        String pivotRule,
        int pivotNodeCount) {}

    public static void main(String[] args) throws Exception {
        var grammarText = loadResource(GRAMMAR_RESOURCE);
        var fixtureSource = loadResource(FIXTURE_RESOURCE);
        var grammar = GrammarParser.parse(grammarText).fold(
            cause -> { throw new IllegalStateException("grammar parse failed: " + cause.message()); },
            g -> g);
        var parser = IncrementalParser.create(grammar);

        // Generate the edit plan once. The same plan drives both regimes so
        // the comparison is apples-to-apples.
        var plan = generateEditPlan(fixtureSource, EDIT_COUNT, RNG_SEED);

        // Warm the JIT a bit so first-edit numbers reflect steady-state JIT,
        // not interpreter cost. We do a small throwaway session.
        warmJit(parser, fixtureSource);

        var regimeB = runRegime(parser, fixtureSource, plan, /*moveCursor=*/true);
        var regimeA = runRegime(parser, fixtureSource, plan, /*moveCursor=*/false);

        printReport(fixtureSource, regimeB, regimeA);
    }

    // -------- Regime driver ----------------------------------------------------------------

    private static Measurement[] runRegime(
            IncrementalParser parser, String fixtureSource, List<ClassifiedEdit> plan, boolean moveCursor) {
        var session = parser.initialize(fixtureSource, 0);
        int prevFallbacks = session.stats().fullReparseCount();
        var out = new Measurement[plan.size()];
        for (int i = 0; i < plan.size(); i++) {
            var ce = plan.get(i);
            var edit = ce.edit();
            // Clamp the edit to the current buffer: the plan was generated against
            // the initial text, so by the time we replay against the live session
            // text the offsets and lengths may drift. Skip edits that cannot fit.
            int textLen = session.text().length();
            if (edit.offset() > textLen || edit.offset() + edit.oldLen() > textLen) {
                out[i] = new Measurement(ce.cls(), -1L, false, true,
                    edit.offset(), edit.oldLen(), edit.newText().length(), "", 0);
                continue;
            }
            try {
                long t0 = System.nanoTime();
                Session next;
                if (moveCursor) {
                    next = session.moveCursor(edit.offset()).edit(edit);
                } else {
                    next = session.edit(edit);
                }
                long t1 = System.nanoTime();
                var stats = next.stats();
                int fallbacks = stats.fullReparseCount();
                boolean wasFallback = fallbacks > prevFallbacks;
                prevFallbacks = fallbacks;
                out[i] = new Measurement(ce.cls(), t1 - t0, wasFallback, false,
                    edit.offset(), edit.oldLen(), edit.newText().length(),
                    stats.lastReparsedRule(), stats.lastReparsedNodeCount());
                session = next;
            } catch (RuntimeException ex) {
                out[i] = new Measurement(ce.cls(), -1L, false, true,
                    edit.offset(), edit.oldLen(), edit.newText().length(), "", 0);
            }
        }
        return out;
    }

    private static void warmJit(IncrementalParser parser, String fixtureSource) {
        var s = parser.initialize(fixtureSource, fixtureSource.length() / 2);
        for (int i = 0; i < 20; i++) {
            int off = (i * 37 + 100) % s.text().length();
            try {
                s = s.moveCursor(off).edit(new Edit(off, 0, "x"));
            } catch (RuntimeException ignored) {
                // warm-up best-effort
            }
        }
    }

    // -------- Edit plan generation ---------------------------------------------------------

    /**
     * Generate a deterministic edit plan against the initial fixture text.
     * Edit positions cluster near the previous edit (70% within ±100 chars,
     * 25% within ±2000, 5% uniform). Distribution mirrors
     * {@code IncrementalParityTest.randomEdit} for shape-class proportions.
     */
    private static List<ClassifiedEdit> generateEditPlan(String initialText, int n, long seed) {
        var rng = new Random(seed);
        var plan = new ArrayList<ClassifiedEdit>(n);
        // We don't replay the buffer here — the plan is offset-relative and the
        // runner clamps at replay time. Use the initial length as the reference
        // for clustering and clamping.
        int textLen = initialText.length();
        int prevOffset = textLen / 2;
        for (int i = 0; i < n; i++) {
            int off = clusteredOffset(rng, prevOffset, textLen);
            var ce = drawEdit(rng, off, textLen);
            plan.add(ce);
            prevOffset = off;
        }
        return plan;
    }

    private static int clusteredOffset(Random rng, int prev, int textLen) {
        int roll = rng.nextInt(100);
        int radius;
        if (roll < 70) {
            radius = 100;
        } else if (roll < 95) {
            radius = 2000;
        } else {
            return rng.nextInt(textLen + 1);
        }
        int low = Math.max(0, prev - radius);
        int high = Math.min(textLen, prev + radius);
        if (high <= low) {
            return low;
        }
        return low + rng.nextInt(high - low + 1);
    }

    private static ClassifiedEdit drawEdit(Random rng, int off, int textLen) {
        int roll = rng.nextInt(100);
        if (roll < 40) {
            // 40% single-char insert
            int safeOff = Math.min(off, textLen);
            return new ClassifiedEdit(new Edit(safeOff, 0, String.valueOf(randomAlnum(rng))), EditClass.SINGLE_CHAR);
        }
        if (roll < 60) {
            // 20% single-char delete
            int safeOff = clampOffsetForDelete(off, textLen, 1);
            return new ClassifiedEdit(new Edit(safeOff, 1, ""), EditClass.SINGLE_CHAR);
        }
        if (roll < 75) {
            // 15% word insert
            int safeOff = Math.min(off, textLen);
            return new ClassifiedEdit(new Edit(safeOff, 0, randomWord(rng)), EditClass.WORD);
        }
        if (roll < 85) {
            // 10% word delete
            int len = 1 + rng.nextInt(6);
            int safeOff = clampOffsetForDelete(off, textLen, len);
            int safeLen = Math.min(len, Math.max(0, textLen - safeOff));
            return new ClassifiedEdit(new Edit(safeOff, safeLen, ""), EditClass.WORD);
        }
        if (roll < 95) {
            // 10% line paste / line delete
            if (rng.nextBoolean()) {
                int len = 20 + rng.nextInt(60);
                int safeOff = clampOffsetForDelete(off, textLen, len);
                int safeLen = Math.min(len, Math.max(0, textLen - safeOff));
                return new ClassifiedEdit(new Edit(safeOff, safeLen, ""), EditClass.LINE);
            }
            int safeOff = Math.min(off, textLen);
            return new ClassifiedEdit(new Edit(safeOff, 0, randomLine(rng)), EditClass.LINE);
        }
        // 5% block paste / delete
        int len = 80 + rng.nextInt(120);
        if (rng.nextBoolean()) {
            int safeOff = clampOffsetForDelete(off, textLen, len);
            int safeLen = Math.min(len, Math.max(0, textLen - safeOff));
            return new ClassifiedEdit(new Edit(safeOff, safeLen, ""), EditClass.BLOCK);
        }
        var block = new StringBuilder();
        int lines = 2 + rng.nextInt(4);
        for (int i = 0; i < lines; i++) {
            block.append(randomLine(rng)).append('\n');
        }
        int safeOff = Math.min(off, textLen);
        return new ClassifiedEdit(new Edit(safeOff, 0, block.toString()), EditClass.BLOCK);
    }

    private static int clampOffsetForDelete(int off, int textLen, int oldLen) {
        if (textLen <= 0) {
            return 0;
        }
        return Math.min(off, Math.max(0, textLen - oldLen));
    }

    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_ ";

    private static char randomAlnum(Random rng) {
        return ALNUM.charAt(rng.nextInt(ALNUM.length()));
    }

    private static final String[] WORDS = {
        "class", "interface", "enum", "void", "int", "String", "var", "public",
        "private", "static", "final", "return", "new", "true", "false", "null",
        "if", "else", "while", "for", "foo", "bar", "baz", "x", "y", "z",
        "value", "name", "list", "map"
    };

    private static String randomWord(Random rng) {
        return WORDS[rng.nextInt(WORDS.length)];
    }

    private static String randomLine(Random rng) {
        return "var " + randomWord(rng) + " = " + randomWord(rng) + "; ";
    }

    // -------- Reporting --------------------------------------------------------------------

    private static void printReport(String fixtureSource, Measurement[] regimeB, Measurement[] regimeA) {
        System.out.println("=== IncrementalSessionBench ===");
        int loc = countLines(fixtureSource);
        System.out.printf("Fixture: large/FactoryClassGenerator.java.txt (%d LOC, %d chars)%n",
            loc, fixtureSource.length());
        System.out.printf("Edits: %d (RNG seed 0x%X)%n", EDIT_COUNT, RNG_SEED);
        System.out.println();

        System.out.println("REGIME B (cursor-moved-to-edit)");
        printRegime(regimeB);
        System.out.println();
        System.out.println("REGIME A (cursor-stays-at-zero, pessimistic control)");
        printRegime(regimeA);
        System.out.println();
        System.out.println("DELTA (B vs A on the same edit sequence)");
        printDelta(regimeB, regimeA);
        System.out.println();
        System.out.println("TOP 10 OUTLIERS (Regime B, by latency desc)");
        printOutliers(regimeB);
        System.out.println();
        System.out.println("TOP 10 OUTLIERS (Regime A, by latency desc)");
        printOutliers(regimeA);
    }

    private static void printOutliers(Measurement[] ms) {
        var nonSkipped = new ArrayList<Measurement>();
        for (var m : ms) {
            if (!m.skipped() && m.latencyNs() >= 0) {
                nonSkipped.add(m);
            }
        }
        nonSkipped.sort((a, b) -> Long.compare(b.latencyNs(), a.latencyNs()));
        System.out.printf("  %-5s %-9s %-12s %-9s %-7s %-7s %-22s %-9s %s%n",
            "rank", "latency", "class", "offset", "oldLen", "newLen", "pivotRule", "pivotNodes", "fallback");
        int n = Math.min(10, nonSkipped.size());
        for (int i = 0; i < n; i++) {
            var m = nonSkipped.get(i);
            String pivot = m.pivotRule() == null || m.pivotRule().isEmpty() ? "-" : m.pivotRule();
            if (pivot.length() > 22) {
                pivot = pivot.substring(0, 21) + "…";
            }
            System.out.printf("  %-5d %-9s %-12s %-9d %-7d %-7d %-22s %-9d %s%n",
                i + 1,
                fmtMs(m.latencyNs()),
                m.cls().label(),
                m.editOffset(),
                m.editOldLen(),
                m.editNewLen(),
                pivot,
                m.pivotNodeCount(),
                m.wasFallback());
        }
    }

    private static void printRegime(Measurement[] ms) {
        System.out.printf("  %-15s %-7s %-8s %-8s %-8s %-8s %s%n",
            "Class", "Count", "Median", "p95", "p99", "Max", "Fallbacks");
        for (var cls : EditClass.values()) {
            printClassRow(cls.label(), filterByClass(ms, cls));
        }
        printClassRow("ALL", filterAll(ms));

        // Cold vs warm split (across all classes, all non-skipped edits)
        long[] cold = sliceLatencies(ms, 0, COLD_PREFIX);
        long[] warm = sliceLatencies(ms, COLD_PREFIX, ms.length);
        long underBudget = countUnderBudget(ms);
        long total = countNonSkipped(ms);
        double underPct = total == 0 ? 0.0 : (100.0 * underBudget / total);

        System.out.println();
        System.out.printf("  Cold (first %d edits) median: %s%n", COLD_PREFIX, fmtMs(median(cold)));
        System.out.printf("  Warm (edits %d-%d) median:  %s%n", COLD_PREFIX + 1, ms.length, fmtMs(median(warm)));
        System.out.printf("  %% under %.0f ms (frame budget): %.1f%%%n", FRAME_BUDGET_MS, underPct);
    }

    private static void printClassRow(String label, Measurement[] ms) {
        long[] latencies = sliceLatencies(ms, 0, ms.length);
        long fallbacks = countFallbacks(ms);
        long count = latencies.length;
        if (count == 0) {
            System.out.printf("  %-15s %-7d %-8s %-8s %-8s %-8s %d%n",
                label, 0, "-", "-", "-", "-", fallbacks);
            return;
        }
        System.out.printf("  %-15s %-7d %-8s %-8s %-8s %-8s %d%n",
            label,
            count,
            fmtMs(median(latencies)),
            fmtMs(percentile(latencies, 95)),
            fmtMs(percentile(latencies, 99)),
            fmtMs(max(latencies)),
            fallbacks);
    }

    private static void printDelta(Measurement[] regimeB, Measurement[] regimeA) {
        System.out.printf("  %-15s %-12s %-12s %s%n", "Class", "B median", "A median", "B/A ratio");
        for (var cls : EditClass.values()) {
            printDeltaRow(cls.label(), filterByClass(regimeB, cls), filterByClass(regimeA, cls));
        }
        printDeltaRow("ALL", filterAll(regimeB), filterAll(regimeA));
    }

    private static void printDeltaRow(String label, Measurement[] b, Measurement[] a) {
        long[] bl = sliceLatencies(b, 0, b.length);
        long[] al = sliceLatencies(a, 0, a.length);
        if (bl.length == 0 || al.length == 0) {
            System.out.printf("  %-15s %-12s %-12s %s%n", label, "-", "-", "-");
            return;
        }
        double bMs = ns2ms(median(bl));
        double aMs = ns2ms(median(al));
        String ratio = aMs <= 0 ? "-" : String.format("%.2fx", bMs / aMs);
        System.out.printf("  %-15s %-12s %-12s %s%n",
            label, fmtMs(median(bl)), fmtMs(median(al)), ratio);
    }

    // -------- Helpers ----------------------------------------------------------------------

    private static Measurement[] filterByClass(Measurement[] ms, EditClass cls) {
        var out = new ArrayList<Measurement>();
        for (var m : ms) {
            if (m.cls() == cls) {
                out.add(m);
            }
        }
        return out.toArray(new Measurement[0]);
    }

    private static Measurement[] filterAll(Measurement[] ms) {
        return ms;
    }

    private static long[] sliceLatencies(Measurement[] ms, int from, int toExclusive) {
        int hi = Math.min(toExclusive, ms.length);
        var tmp = new long[Math.max(0, hi - from)];
        int n = 0;
        for (int i = from; i < hi; i++) {
            if (!ms[i].skipped() && ms[i].latencyNs() >= 0) {
                tmp[n++] = ms[i].latencyNs();
            }
        }
        return Arrays.copyOf(tmp, n);
    }

    private static long countNonSkipped(Measurement[] ms) {
        long c = 0;
        for (var m : ms) {
            if (!m.skipped()) {
                c++;
            }
        }
        return c;
    }

    private static long countFallbacks(Measurement[] ms) {
        long c = 0;
        for (var m : ms) {
            if (!m.skipped() && m.wasFallback()) {
                c++;
            }
        }
        return c;
    }

    private static long countUnderBudget(Measurement[] ms) {
        long budgetNs = (long) (FRAME_BUDGET_MS * 1_000_000.0);
        long c = 0;
        for (var m : ms) {
            if (!m.skipped() && m.latencyNs() >= 0 && m.latencyNs() < budgetNs) {
                c++;
            }
        }
        return c;
    }

    private static long median(long[] sortedOrUnsorted) {
        return percentile(sortedOrUnsorted, 50);
    }

    private static long percentile(long[] values, int pct) {
        if (values.length == 0) {
            return 0L;
        }
        var sorted = values.clone();
        Arrays.sort(sorted);
        int idx = (int) Math.ceil((pct / 100.0) * sorted.length) - 1;
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= sorted.length) {
            idx = sorted.length - 1;
        }
        return sorted[idx];
    }

    private static long max(long[] values) {
        long m = 0;
        for (long v : values) {
            if (v > m) {
                m = v;
            }
        }
        return m;
    }

    private static double ns2ms(long ns) {
        return ns / 1_000_000.0;
    }

    private static String fmtMs(long ns) {
        return String.format("%.1f", ns2ms(ns));
    }

    private static int countLines(String s) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    private static String loadResource(String resourcePath) throws Exception {
        try (var in = IncrementalSessionBench.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Resource not found on classpath: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
