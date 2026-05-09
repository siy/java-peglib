package org.pragmatica.peg.generator;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.error.RecoveryStrategy;
import org.pragmatica.peg.parser.ParserConfig;

import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 4 commit 4 (0.5.1) — verify the generated parser embeds and invokes a
 * working {@code TriviaPostPass} when {@code config.triviaPostPass()=true} at
 * generation time.
 *
 * <p>The tests:
 * <ol>
 *   <li>Source emission: flag-ON includes the embedded class and the call;
 *       flag-OFF includes neither (default behavior bit-for-bit unchanged).</li>
 *   <li>Standalone-parser invariant: zero peglib-runtime imports in the
 *       generated source under flag-ON.</li>
 *   <li>Behavioral: compile + run a flag-ON generated parser and confirm
 *       leading/trailing trivia and content classification are correct.</li>
 *   <li>Parity: generated parser under flag-ON agrees with the interpreter
 *       under flag-ON on leading-trivia text + kind for the same input.</li>
 * </ol>
 *
 * <p>Test grammars use a single top-level token-shaped rule (matching
 * {@link org.pragmatica.peg.GeneratedParserTriviaTest}) so behavioral
 * assertions target the post-pass attribution layer specifically — not the
 * pre-existing wrap-with-rule-name single-result collapse.
 */
class GeneratedParserTriviaPostPassTest {

    private static final String SIMPLE_GRAMMAR = """
        Number <- < [0-9]+ >
        %whitespace <- [ \\t\\r\\n]*
        """;

    private static final String COMMENT_GRAMMAR = """
        Number <- < [0-9]+ >
        %whitespace <- ([ \\t]+ / '//' [^\\n]* '\\n')+
        """;

    @Test
    void flagOn_emitsEmbeddedTriviaPostPassClass() {
        var source = generate(SIMPLE_GRAMMAR, "test.tpp.emit", "EmitParser", true);
        assertThat(source).contains("private static final class TriviaPostPass");
        assertThat(source).contains("static CstNode assignTrivia(String input, CstNode cst, int leadingScanFrom)");
        assertThat(source).contains("TriviaPostPass.assignTrivia(input, rootNode, 0);");
    }

    @Test
    void flagOff_doesNotEmitEmbeddedTriviaPostPassClass() {
        var source = generate(SIMPLE_GRAMMAR, "test.tpp.off", "OffParser", false);
        assertThat(source).doesNotContain("private static final class TriviaPostPass");
        assertThat(source).doesNotContain("TriviaPostPass.assignTrivia");
    }

    @Test
    void flagOn_generatedSourceHasZeroPeglibRuntimeImports() {
        var source = generate(SIMPLE_GRAMMAR, "test.tpp.invariant", "InvariantParser", true);
        // Standalone-parser invariant: only pragmatica-lite + java.* imports allowed.
        assertThat(source).doesNotContain("import org.pragmatica.peg.");
    }

    @Test
    void flagOn_parseCapturesLeadingTrivia() throws Exception {
        var input = "  42";
        var parserClass = compile(SIMPLE_GRAMMAR, "test.tpp.leading", "LeadingParser", true);
        var cst = invokeParse(parserClass, input);
        assertThat(collectLeadingTriviaText(cst)).isEqualTo("  ");
    }

    @Test
    void flagOn_parseCapturesTrailingTrivia() throws Exception {
        var input = "42  ";
        var parserClass = compile(SIMPLE_GRAMMAR, "test.tpp.trailing", "TrailingParser", true);
        var cst = invokeParse(parserClass, input);
        assertThat(collectTrailingTriviaText(cst)).isEqualTo("  ");
    }

    @Test
    void flagOn_parseClassifiesLineCommentTrivia() throws Exception {
        var input = "// hello\n42";
        var parserClass = compile(COMMENT_GRAMMAR, "test.tpp.comment", "CommentParser", true);
        var cst = invokeParse(parserClass, input);
        var leading = leadingTriviaList(cst);
        assertThat(leading).hasSize(1);
        // First trivia should be classified as LineComment by content prefix.
        assertThat(leading.getFirst()
                          .getClass()
                          .getSimpleName()).isEqualTo("LineComment");
    }

    @Test
    void flagOn_generatedAndInterpreterAgreeOnLeadingTrivia() throws Exception {
        var input = "  // first\n  42";
        var parserClass = compile(COMMENT_GRAMMAR, "test.tpp.parity", "ParityParser", true);
        var generatedCst = invokeParse(parserClass, input);

        var interpreterParser = PegParser.builder(COMMENT_GRAMMAR)
                                         .triviaPostPass(true)
                                         .build()
                                         .unwrap();
        var interpreterCst = interpreterParser.parseCst(input)
                                              .unwrap();

        var genLeading = leadingTriviaList(generatedCst);
        assertThat(genLeading).hasSameSizeAs(interpreterCst.leadingTrivia());
        for (int i = 0; i < interpreterCst.leadingTrivia()
                                          .size(); i++) {
            var rt = interpreterCst.leadingTrivia()
                                   .get(i);
            var gt = genLeading.get(i);
            assertThat(gt.getClass()
                         .getSimpleName()).isEqualTo(rt.getClass()
                                                       .getSimpleName());
            assertThat((String) gt.getClass()
                                  .getMethod("text")
                                  .invoke(gt)).isEqualTo(rt.text());
        }
    }

    @Test
    void flagOff_defaultBehaviorParsesAndCapturesTrivia() throws Exception {
        // Validate flag-OFF generation still parses and captures trivia (the
        // pre-existing engine path; no post-pass invoked).
        var input = "  42  ";
        var parserClass = compile(SIMPLE_GRAMMAR, "test.tpp.unchanged", "UnchangedParser", false);
        var cst = invokeParse(parserClass, input);
        assertThat(collectLeadingTriviaText(cst)).isEqualTo("  ");
        assertThat(collectTrailingTriviaText(cst)).isEqualTo("  ");
    }

    // === Generator helpers ===
    private String generate(String grammar, String pkg, String cls, boolean triviaPostPass) {
        var base = ParserConfig.parserConfig(true, RecoveryStrategy.BASIC, true);
        var config = new ParserConfig(
            base.packratEnabled(),
            base.recoveryStrategy(),
            base.captureTrivia(),
            base.fastTrackFailure(),
            base.literalFailureCache(),
            base.charClassFailureCache(),
            base.bulkAdvanceLiteral(),
            base.skipWhitespaceFastPath(),
            base.reuseEndLocation(),
            base.choiceDispatch(),
            base.markResetChildren(),
            base.inlineLocations(),
            base.selectivePackrat(),
            Set.of(),
            base.mutableParseResult(),
            base.tokenFastPath(),
            triviaPostPass);
        return PegParser.generateCstParser(grammar, pkg, cls, ErrorReporting.BASIC, config)
                        .unwrap();
    }

    private Class<?> compile(String grammar, String pkg, String cls, boolean triviaPostPass) throws Exception {
        var source = generate(grammar, pkg, cls, triviaPostPass);
        var fqcn = pkg + "." + cls;
        var tempDir = Files.createTempDirectory("peglib-tpp-test");
        var packageDir = tempDir.resolve(pkg.replace('.', '/'));
        Files.createDirectories(packageDir);
        var sourceFile = packageDir.resolve(cls + ".java");
        Files.writeString(sourceFile, source);
        var compiler = ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, null,
                              "-d", tempDir.toString(),
                              "-cp", System.getProperty("java.class.path"),
                              sourceFile.toString());
        if (rc != 0) {
            throw new RuntimeException("Compilation failed for " + fqcn);
        }
        var loader = new URLClassLoader(new URL[]{tempDir.toUri()
                                                         .toURL()});
        return loader.loadClass(fqcn);
    }

    private Object invokeParse(Class<?> parserClass, String input) throws Exception {
        var parser = parserClass.getDeclaredConstructor()
                                .newInstance();
        var parseMethod = findParseMethod(parserClass);
        var result = parseMethod.invoke(parser, input);
        var unwrap = result.getClass()
                           .getMethod("unwrap");
        return unwrap.invoke(result);
    }

    private Method findParseMethod(Class<?> parserClass) {
        for (var m : parserClass.getMethods()) {
            if ("parse".equals(m.getName()) && m.getParameterCount() == 1
                && m.getParameterTypes()[0] == String.class) {
                return m;
            }
        }
        throw new IllegalStateException("No parse(String) method on " + parserClass);
    }

    private List<Object> leadingTriviaList(Object cst) throws Exception {
        @SuppressWarnings("unchecked")
        var list = (List<Object>) cst.getClass()
                                     .getMethod("leadingTrivia")
                                     .invoke(cst);
        return list;
    }

    private String collectLeadingTriviaText(Object cst) throws Exception {
        var sb = new StringBuilder();
        for (var t : leadingTriviaList(cst)) {
            sb.append((String) t.getClass()
                                .getMethod("text")
                                .invoke(t));
        }
        return sb.toString();
    }

    private String collectTrailingTriviaText(Object cst) throws Exception {
        @SuppressWarnings("unchecked")
        var list = (List<Object>) cst.getClass()
                                     .getMethod("trailingTrivia")
                                     .invoke(cst);
        var sb = new StringBuilder();
        for (var t : list) {
            sb.append((String) t.getClass()
                                .getMethod("text")
                                .invoke(t));
        }
        return sb.toString();
    }
}
