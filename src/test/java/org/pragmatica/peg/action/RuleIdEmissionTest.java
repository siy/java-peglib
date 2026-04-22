package org.pragmatica.peg.action;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.PegParser;

import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link org.pragmatica.peg.generator.ParserGenerator} emits:
 * <ul>
 *   <li>a nested {@code sealed interface RuleId extends
 *       org.pragmatica.peg.action.RuleId}</li>
 *   <li>a parameter-less record per grammar rule, with class names matching
 *       the sanitized rule name (so {@code Class.getSimpleName()} lines up with
 *       {@link Actions} dispatch)</li>
 *   <li>a {@code withAction} entry point on the AST-path parser that accepts a
 *       {@code Class<? extends RuleId>} + {@code Function<SemanticValues,
 *       Object>}</li>
 * </ul>
 *
 * <p>These are the shape guarantees the 0.3.0 {@code parseRuleAt} signature
 * will rely on.
 */
class RuleIdEmissionTest {

    @Test
    void generatedCstParser_emitsSealedRuleIdExtendingLibraryBase() {
        var grammar = """
            Number <- < [0-9]+ >
            Sum    <- Number '+' Number
            %whitespace <- [ ]*
            """;
        var source = PegParser.generateCstParser(grammar, "emit.cst", "CstParser").unwrap();

        assertThat(source).contains("public sealed interface RuleId extends org.pragmatica.peg.action.RuleId");
        assertThat(source).contains("record Number() implements RuleId");
        assertThat(source).contains("record Sum() implements RuleId");
    }

    @Test
    void generatedAstParser_emitsRuleIdAndWithActionApi() {
        var grammar = """
            Number <- < [0-9]+ >
            Sum    <- Number '+' Number
            %whitespace <- [ ]*
            """;
        var source = PegParser.generateParser(grammar, "emit.ast", "AstParser").unwrap();

        assertThat(source).contains("public sealed interface RuleId extends org.pragmatica.peg.action.RuleId");
        assertThat(source).contains("record Number() implements RuleId");
        assertThat(source).contains("record Sum() implements RuleId");
        assertThat(source).contains("public AstParser withAction(Class<? extends RuleId> ruleIdClass");
        assertThat(source).contains("public static final class SemanticValues");
    }

    @Test
    void ruleIdRecords_areParameterless_forForwardCompatWithParseRuleAt() {
        var grammar = """
            Foo <- 'x'
            Bar <- 'y'
            """;
        var source = PegParser.generateParser(grammar, "emit.plain", "PlainParser").unwrap();

        // Marker records must be parameter-less — identity by class, not by fields.
        assertThat(source).contains("record Foo() implements RuleId");
        assertThat(source).contains("record Bar() implements RuleId");
        // No field-bearing variant should appear.
        assertThat(source).doesNotContain("record Foo(String");
        assertThat(source).doesNotContain("record Bar(String");
    }

    @Test
    void generatedAstParser_lambdaOverridesInlineAction() throws Exception {
        var grammar = """
            Number <- < [0-9]+ > { return Integer.parseInt($0); }
            """;
        var source = PegParser.generateParser(grammar, "emit.lambda", "LambdaParser").unwrap();

        var parserClass = compileAndLoad(source, "emit.lambda.LambdaParser");
        var parser = parserClass.getDeclaredConstructor().newInstance();

        // Resolve nested RuleId.Number class and SemanticValues.
        var ruleIdIface = Class.forName("emit.lambda.LambdaParser$RuleId", true, parserClass.getClassLoader());
        var numberClass = Class.forName("emit.lambda.LambdaParser$RuleId$Number", true, parserClass.getClassLoader());
        var semanticValuesClass = Class.forName("emit.lambda.LambdaParser$SemanticValues", true, parserClass.getClassLoader());
        assertThat(ruleIdIface.isAssignableFrom(numberClass)).isTrue();
        // Generated RuleId must extend library's RuleId — enforces API shape for parseRuleAt.
        assertThat(RuleId.class.isAssignableFrom(ruleIdIface)).isTrue();

        // Attach a lambda that returns matched-int-times-10 so it is distinguishable from the inline action (sv.toInt()).
        var withAction = parserClass.getMethod("withAction", Class.class, Function.class);
        Function<Object, Object> lambda = sv -> {
            try {
                int n = (int) semanticValuesClass.getMethod("toInt").invoke(sv);
                return n * 10;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        withAction.invoke(parser, numberClass, lambda);

        @SuppressWarnings("unchecked")
        var result = (Result<Object>) parserClass.getMethod("parse", String.class).invoke(parser, "42");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.unwrap()).isEqualTo(420);
    }

    @Test
    void generatedAstParser_withoutLambda_usesInlineAction() throws Exception {
        var grammar = """
            Number <- < [0-9]+ > { return Integer.parseInt($0); }
            """;
        var source = PegParser.generateParser(grammar, "emit.inline", "InlineParser").unwrap();

        var parserClass = compileAndLoad(source, "emit.inline.InlineParser");
        var parser = parserClass.getDeclaredConstructor().newInstance();

        @SuppressWarnings("unchecked")
        var result = (Result<Object>) parserClass.getMethod("parse", String.class).invoke(parser, "42");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.unwrap()).isEqualTo(42);
    }

    private Class<?> compileAndLoad(String source, String className) throws Exception {
        var tempDir = Files.createTempDirectory("peglib-ruleid-test");
        var packagePath = className.substring(0, className.lastIndexOf('.')).replace('.', '/');
        var simpleClassName = className.substring(className.lastIndexOf('.') + 1);

        var packageDir = tempDir.resolve(packagePath);
        Files.createDirectories(packageDir);

        var sourceFile = packageDir.resolve(simpleClassName + ".java");
        Files.writeString(sourceFile, source);

        var compiler = ToolProvider.getSystemJavaCompiler();
        var result = compiler.run(null, null, null,
            "-d", tempDir.toString(),
            "-cp", System.getProperty("java.class.path"),
            sourceFile.toString()
        );

        if (result != 0) {
            throw new RuntimeException("Compilation failed for " + className);
        }

        var classLoader = new URLClassLoader(new URL[]{tempDir.toUri().toURL()}, getClass().getClassLoader());
        return classLoader.loadClass(className);
    }
}
