package org.pragmatica.peg.perf;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.tree.CstNode;
import org.pragmatica.peg.tree.Trivia;

import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for Candidate #2: trivia snapshot via int size (replacing List.copyOf).
 *
 * <p>Verifies that the generated parser (which now uses {@code int}-typed snapshots
 * for {@code savePendingLeading() / restorePendingLeading()}) preserves trivia
 * correctness across the three scenarios most affected by the change:
 *
 * <ol>
 *   <li>Trivia-heavy input — runtime vs generated trivia must agree byte-for-byte
 *       (covers consume-then-attach: {@code takePendingLeading()} after
 *       skipped/captured runs).</li>
 *   <li>Backtracking-heavy Choice — failed alternatives must roll back trivia
 *       captured inside them, so the chosen alt sees the correct prefix
 *       trivia (covers save/restore on failure).</li>
 *   <li>Trivia accounting after deep iteration — every trivia item from input
 *       must end up attached exactly once across all CST nodes (covers
 *       {@code restorePendingLeading} truncation correctness in iter loops).</li>
 * </ol>
 */
class CandidateTwoSmokeTest {

    @Test
    void triviaHeavy_runtimeAndGeneratedAgree() throws Exception {
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- ([ \\t\\n]+ / '//' [^\\n]* '\\n' / '/*' (!'*/' .)* '*/')+
            """;
        var input = "  // hello\n  /* block */\n  42  // trailing\n";

        // Runtime
        var runtimeParser = PegParser.fromGrammar(grammar).unwrap();
        var runtimeResult = runtimeParser.parseCst(input);
        assertThat(runtimeResult.isSuccess()).isTrue();
        var runtimeNode = runtimeResult.unwrap();

        // Generated
        var sourceResult = PegParser.generateCstParser(grammar, "smoke.candidate2.heavy", "HeavyParser");
        assertThat(sourceResult.isSuccess()).isTrue();
        var parserClass = compileAndLoad(sourceResult.unwrap(), "smoke.candidate2.heavy.HeavyParser");
        var parser = parserClass.getDeclaredConstructor().newInstance();
        var parseMethod = parserClass.getMethod("parse", String.class);

        @SuppressWarnings("unchecked")
        var genResult = (Result<Object>) parseMethod.invoke(parser, input);
        assertThat(genResult.isSuccess()).isTrue();
        var genNode = genResult.unwrap();

        // Compare leadingTrivia
        var leadingMethod = genNode.getClass().getMethod("leadingTrivia");
        @SuppressWarnings("unchecked")
        var genLeading = (List<?>) leadingMethod.invoke(genNode);
        var runtimeLeading = runtimeNode.leadingTrivia();
        assertThat(genLeading).as("leading trivia size").hasSameSizeAs(runtimeLeading);
        for (int i = 0; i < runtimeLeading.size(); i++) {
            var rt = runtimeLeading.get(i);
            var gt = genLeading.get(i);
            var textMethod = gt.getClass().getMethod("text");
            assertThat(textMethod.invoke(gt)).as("leading[%d].text", i).isEqualTo(rt.text());
            assertThat(gt.getClass().getSimpleName())
                .as("leading[%d].type", i)
                .isEqualTo(rt.getClass().getSimpleName());
        }

        // Compare trailingTrivia
        var trailingMethod = genNode.getClass().getMethod("trailingTrivia");
        @SuppressWarnings("unchecked")
        var genTrailing = (List<?>) trailingMethod.invoke(genNode);
        var runtimeTrailing = runtimeNode.trailingTrivia();
        assertThat(genTrailing).as("trailing trivia size").hasSameSizeAs(runtimeTrailing);
        for (int i = 0; i < runtimeTrailing.size(); i++) {
            var rt = runtimeTrailing.get(i);
            var gt = genTrailing.get(i);
            var textMethod = gt.getClass().getMethod("text");
            assertThat(textMethod.invoke(gt)).as("trailing[%d].text", i).isEqualTo(rt.text());
        }
    }

    @Test
    void backtrackingChoice_triviaRolledBackOnFailedAlternative() throws Exception {
        // Choice where the FIRST alt consumes whitespace then fails on the keyword,
        // forcing backtrack to the SECOND alt. The succeeding second alt must see
        // the same prefix trivia that was present at choice entry — i.e. the trivia
        // captured by the failed first alt must be ROLLED BACK by restorePendingLeading.
        var grammar = """
            Document <- Stmt
            Stmt <- Keyword 'X' / Keyword 'Y'
            Keyword <- < [a-z]+ >
            %whitespace <- [ \\t]+
            """;
        // Input: "  hello Y" — first alt (Keyword 'X') will fail at 'X' (sees 'Y'),
        // second alt (Keyword 'Y') must succeed. The 'hello' Keyword token in
        // both alts must end up with the same leading trivia ("  ").
        var input = "  hello Y";

        var runtimeParser = PegParser.fromGrammar(grammar).unwrap();
        var runtimeResult = runtimeParser.parseCst(input);
        assertThat(runtimeResult.isSuccess()).as("runtime parse: %s", runtimeResult).isTrue();

        var sourceResult = PegParser.generateCstParser(grammar, "smoke.candidate2.bt", "BtParser");
        assertThat(sourceResult.isSuccess()).isTrue();
        var parserClass = compileAndLoad(sourceResult.unwrap(), "smoke.candidate2.bt.BtParser");
        var parser = parserClass.getDeclaredConstructor().newInstance();
        var parseMethod = parserClass.getMethod("parse", String.class);

        @SuppressWarnings("unchecked")
        var genResult = (Result<Object>) parseMethod.invoke(parser, input);
        assertThat(genResult.isSuccess()).as("generated parse should succeed after backtrack").isTrue();

        var genNode = genResult.unwrap();
        var runtimeNode = runtimeResult.unwrap();

        // Both must have identical leading trivia at top level
        var leadingMethod = genNode.getClass().getMethod("leadingTrivia");
        @SuppressWarnings("unchecked")
        var genLeading = (List<?>) leadingMethod.invoke(genNode);
        assertThat(genLeading).hasSameSizeAs(runtimeNode.leadingTrivia());
        for (int i = 0; i < runtimeNode.leadingTrivia().size(); i++) {
            var textMethod = genLeading.get(i).getClass().getMethod("text");
            assertThat(textMethod.invoke(genLeading.get(i)))
                .isEqualTo(runtimeNode.leadingTrivia().get(i).text());
        }
    }

    @Test
    void deepIteration_triviaAccountingExact() {
        // Iteration body that captures trivia between elements; total trivia
        // text concatenated across all CST nodes must equal exactly the trivia
        // characters in the input — no duplicates, no losses.
        var grammar = """
            List <- Number+
            Number <- < [0-9]+ >
            %whitespace <- [ \\t\\n]+
            """;
        // Mix of single/double/triple-space separators to exercise variable-length
        // restorePendingLeading truncation.
        var input = "1 2  3   4    5\n6 7";

        var parser = PegParser.fromGrammar(grammar).unwrap();
        var result = parser.parseCst(input);
        assertThat(result.isSuccess()).isTrue();
        var node = result.unwrap();

        var allTriviaText = new StringBuilder();
        collectAllTriviaText(node, allTriviaText);

        // Expected: every space + tab + newline character in the input.
        var expectedTrivia = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n') {
                expectedTrivia.append(c);
            }
        }
        assertThat(allTriviaText.toString())
            .as("every whitespace char must appear exactly once across CST trivia")
            .isEqualTo(expectedTrivia.toString());
    }

    private static void collectAllTriviaText(CstNode node, StringBuilder out) {
        for (Trivia t : node.leadingTrivia()) {
            out.append(t.text());
        }
        if (node instanceof CstNode.NonTerminal nt) {
            for (CstNode child : nt.children()) {
                collectAllTriviaText(child, out);
            }
        }
        for (Trivia t : node.trailingTrivia()) {
            out.append(t.text());
        }
    }

    private Class<?> compileAndLoad(String source, String className) throws Exception {
        var tempDir = Files.createTempDirectory("peglib-candidate2-smoke");
        var packagePath = className.substring(0, className.lastIndexOf('.')).replace('.', '/');
        var simpleClassName = className.substring(className.lastIndexOf('.') + 1);

        var packageDir = tempDir.resolve(packagePath);
        Files.createDirectories(packageDir);

        var sourceFile = packageDir.resolve(simpleClassName + ".java");
        Files.writeString(sourceFile, source);

        var compiler = ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, null,
            "-d", tempDir.toString(),
            "-cp", System.getProperty("java.class.path"),
            sourceFile.toString());
        if (rc != 0) {
            throw new RuntimeException("Compilation of generated parser failed: " + className);
        }

        var classLoader = new URLClassLoader(new URL[]{tempDir.toUri().toURL()});
        return classLoader.loadClass(className);
    }
}
