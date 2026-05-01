package org.pragmatica.peg.error;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.generator.ErrorReporting;

import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.3.6 regression — proof that the {@code %recover '<term>'} per-rule
 * directive is honored by GENERATED parsers (not just the interpreter).
 *
 * <p>0.3.5 wired per-rule overrides into {@link
 * org.pragmatica.peg.parser.PegEngine}; the generator emitted only a
 * static start-rule override constant. This test pins the 0.3.6 fix:
 * the directive on a NON-start rule must shift the recovery landing
 * point in the generated parser exactly the way it does in the
 * interpreter (see {@link RecoverDirectiveProofTest}).
 *
 * <p>Discriminator: {@code ':'} is outside the default recovery
 * char-set {@code (',' ';' '}' ')' ']' '\\n')}. With override absent,
 * recovery skips to EOF; with override present on the inner rule,
 * recovery stops at {@code ':'}.
 */
class GeneratedParserRecoverDirectiveTest {

    @Test
    void generatedParser_honorsPerRuleRecoverOverrideOnNonStartRule() throws Exception {
        // Body (non-start rule) carries %recover ":". Doc only delegates.
        // On input "aQQQ:rest", 'a' matches; Body fails on 'Q'; Doc fails;
        // pos rewinds to 0. Recovery should consult Body's override and
        // skip to ':' at offset 4 — NOT to EOF as the default char-set would.
        var overrideGrammar = """
            Doc <- 'a' Body
            Body <- 'x' 'y' 'z' %recover ":"
            """;
        // Same shape minus %recover — control. Default recovery has no
        // matching char in the input, so it skips to EOF.
        var defaultGrammar = """
            Doc <- 'a' Body
            Body <- 'x' 'y' 'z'
            """;

        var input = "aQQQ:rest";

        var defaultDiag = invokeParseWithDiagnostics(defaultGrammar,
                                                     "test.gen.recover.def",
                                                     "DefaultParser",
                                                     input);
        var overrideDiag = invokeParseWithDiagnostics(overrideGrammar,
                                                      "test.gen.recover.ovr",
                                                      "OverrideParser",
                                                      input);

        // Both must report errors.
        assertThat((Boolean) call(defaultDiag, "hasErrors"))
            .as("default-grammar generated parser must report errors")
            .isTrue();
        assertThat((Boolean) call(overrideDiag, "hasErrors"))
            .as("override-grammar generated parser must report errors")
            .isTrue();

        // Structural discriminator: walk the CST and find the skipped
        // text of the first Error node. The diagnostic record's span is
        // zero-width (anchored at the failure POINT, not the recovery
        // span), so the rendered Rust-style report cannot distinguish
        // override vs default — both render identical lines pointing at
        // the failure column. The Error node embedded in the CST,
        // however, exposes the full skipped region. With %recover, the
        // first Error spans "aQQQ" (recovery stops at ':'); without it,
        // the first Error spans the entire remainder "aQQQ:rest" (no
        // default recovery char in the input).
        var defaultSkipped = firstErrorSkippedText(defaultDiag);
        var overrideSkipped = firstErrorSkippedText(overrideDiag);
        assertThat(defaultSkipped)
            .as("default recovery skips entire remainder (no recovery char in default set)")
            .isEqualTo("aQQQ:rest");
        assertThat(overrideSkipped)
            .as("override recovery stops at ':' override terminator")
            .isEqualTo("aQQQ");
        assertThat(overrideSkipped)
            .as("override and default skipped text MUST differ — "
                + "identical text means %recover was a silent no-op in the generated parser")
            .isNotEqualTo(defaultSkipped);
    }

    /**
     * Generate a CST parser source for {@code grammar} with ADVANCED
     * reporting, compile it, instantiate it, and invoke
     * {@code parseWithDiagnostics(input)}. Returns the result reflectively.
     */
    private Object invokeParseWithDiagnostics(String grammar,
                                              String packageName,
                                              String className,
                                              String input) throws Exception {
        var sourceResult = PegParser.generateCstParser(grammar,
                                                      packageName,
                                                      className,
                                                      ErrorReporting.ADVANCED);
        assertThat(sourceResult.isSuccess())
            .as("generator must produce source for grammar:\n%s", grammar)
            .isTrue();
        var fqn = packageName + "." + className;
        var parserClass = compileAndLoad(sourceResult.unwrap(), fqn);
        var parser = parserClass.getDeclaredConstructor()
                                .newInstance();
        var method = parserClass.getMethod("parseWithDiagnostics", String.class);
        return method.invoke(parser, input);
    }

    /**
     * Extract the {@code skippedText()} of the first {@code CstNode.Error}
     * found by walking the produced CST in document order. Returns an
     * empty string if no Error node is produced (e.g. when there's no
     * root node at all).
     */
    private static String firstErrorSkippedText(Object diagnosticsResult) throws Exception {
        var nodeOpt = call(diagnosticsResult, "node");
        // Option<CstNode> — call isPresent / unwrap reflectively.
        var isPresent = (Boolean) nodeOpt.getClass()
                                         .getMethod("isPresent")
                                         .invoke(nodeOpt);
        if (!isPresent) {
            return "";
        }
        var rootNode = nodeOpt.getClass()
                              .getMethod("unwrap")
                              .invoke(nodeOpt);
        return findFirstErrorSkipped(rootNode);
    }

    private static String findFirstErrorSkipped(Object node) throws Exception {
        if (node == null) {
            return "";
        }
        var className = node.getClass()
                            .getSimpleName();
        // CstNode.Error record exposes skippedText() accessor.
        if (className.equals("Error")) {
            try {
                return (String) node.getClass()
                                    .getMethod("skippedText")
                                    .invoke(node);
            }catch (NoSuchMethodException ignored) {
                return "";
            }
        }
        // Try children() accessor — present on NonTerminal.
        try {
            var children = (List<?>) node.getClass()
                                         .getMethod("children")
                                         .invoke(node);
            for (var child : children) {
                var found = findFirstErrorSkipped(child);
                if (!found.isEmpty()) {
                    return found;
                }
            }
        }catch (NoSuchMethodException ignored) {
            // Terminal/Token — no descend.
        }
        return "";
    }

    private static Object call(Object target, String method) throws Exception {
        return target.getClass()
                     .getMethod(method)
                     .invoke(target);
    }

    private static Object callWithString(Object target, String method, String arg) throws Exception {
        Method m = target.getClass()
                         .getMethod(method, String.class);
        return m.invoke(target, arg);
    }

    /**
     * Compile {@code source} (a generated CST parser) into a temporary
     * directory and load the {@code className} via a fresh ClassLoader
     * that inherits the test classpath.
     */
    private Class<?> compileAndLoad(String source, String className) throws Exception {
        var tempDir = Files.createTempDirectory("peglib-recover-test");
        var lastDot = className.lastIndexOf('.');
        var packagePath = className.substring(0, lastDot)
                                   .replace('.', '/');
        var simpleName = className.substring(lastDot + 1);
        var packageDir = tempDir.resolve(packagePath);
        Files.createDirectories(packageDir);
        var sourceFile = packageDir.resolve(simpleName + ".java");
        Files.writeString(sourceFile, source);
        var compiler = ToolProvider.getSystemJavaCompiler();
        var rc = compiler.run(null, null, null,
                              "-d", tempDir.toString(),
                              "-cp", System.getProperty("java.class.path"),
                              sourceFile.toString());
        if (rc != 0) {
            throw new RuntimeException("Compilation failed for " + className
                                       + " — see compiler output above");
        }
        var classLoader = new URLClassLoader(new URL[]{tempDir.toUri()
                                                              .toURL()});
        return classLoader.loadClass(className);
    }
}
