package org.pragmatica.peg.generator;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.PegParser;

import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for generated parser ADVANCED error reporting mode.
 * These tests compile and run the generated parser at runtime
 * to verify parseWithDiagnostics() functionality.
 */
class GeneratedParserDiagnosticsTest {

    @Test
    void advancedMode_generatesParseWithDiagnosticsMethod() {
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- [ ]*
            """;

        var result = PegParser.generateCstParser(grammar, "test.diag", "DiagParser", ErrorReporting.ADVANCED);

        assertThat(result.isSuccess()).isTrue();
        var source = result.unwrap();

        assertThat(source).contains("public ParseResultWithDiagnostics parseWithDiagnostics(String input)");
        assertThat(source).contains("record ParseResultWithDiagnostics");
        assertThat(source).contains("enum Severity");
        assertThat(source).contains("record Diagnostic");
    }

    @Test
    void advancedMode_parseWithDiagnostics_successReturnsEmptyDiagnostics() throws Exception {
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- [ ]*
            """;

        var sourceResult = PegParser.generateCstParser(grammar, "test.diag.success", "SuccessParser", ErrorReporting.ADVANCED);
        assertThat(sourceResult.isSuccess()).isTrue();

        var parserClass = compileAndLoad(sourceResult.unwrap(), "test.diag.success.SuccessParser");
        var parser = parserClass.getDeclaredConstructor().newInstance();
        var parseWithDiagMethod = parserClass.getMethod("parseWithDiagnostics", String.class);

        var result = parseWithDiagMethod.invoke(parser, "42");

        // Check isSuccess
        var isSuccessMethod = result.getClass().getMethod("isSuccess");
        assertThat((Boolean) isSuccessMethod.invoke(result)).isTrue();

        // Check diagnostics is empty
        var diagnosticsMethod = result.getClass().getMethod("diagnostics");
        @SuppressWarnings("unchecked")
        var diagnostics = (List<?>) diagnosticsMethod.invoke(result);
        assertThat(diagnostics).isEmpty();

        // Check hasNode
        var hasNodeMethod = result.getClass().getMethod("hasNode");
        assertThat((Boolean) hasNodeMethod.invoke(result)).isTrue();
    }

    @Test
    void advancedMode_parseWithDiagnostics_failureReturnsDiagnostics() throws Exception {
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- [ ]*
            """;

        var sourceResult = PegParser.generateCstParser(grammar, "test.diag.failure", "FailureParser", ErrorReporting.ADVANCED);
        assertThat(sourceResult.isSuccess()).isTrue();

        var parserClass = compileAndLoad(sourceResult.unwrap(), "test.diag.failure.FailureParser");
        var parser = parserClass.getDeclaredConstructor().newInstance();
        var parseWithDiagMethod = parserClass.getMethod("parseWithDiagnostics", String.class);

        var result = parseWithDiagMethod.invoke(parser, "abc");  // Invalid input

        // Check isSuccess is false
        var isSuccessMethod = result.getClass().getMethod("isSuccess");
        assertThat((Boolean) isSuccessMethod.invoke(result)).isFalse();

        // Check hasErrors
        var hasErrorsMethod = result.getClass().getMethod("hasErrors");
        assertThat((Boolean) hasErrorsMethod.invoke(result)).isTrue();
    }

    @Test
    void advancedMode_parseWithDiagnostics_formatsRustStyleOutput() throws Exception {
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- [ ]*
            """;

        var sourceResult = PegParser.generateCstParser(grammar, "test.diag.format", "FormatParser", ErrorReporting.ADVANCED);
        assertThat(sourceResult.isSuccess()).isTrue();

        var parserClass = compileAndLoad(sourceResult.unwrap(), "test.diag.format.FormatParser");
        var parser = parserClass.getDeclaredConstructor().newInstance();
        var parseWithDiagMethod = parserClass.getMethod("parseWithDiagnostics", String.class);

        var result = parseWithDiagMethod.invoke(parser, "abc");  // Invalid input

        // Check formatDiagnostics produces Rust-style output
        var formatMethod = result.getClass().getMethod("formatDiagnostics", String.class);
        var formatted = (String) formatMethod.invoke(result, "input.txt");

        assertThat(formatted).contains("error:");
        assertThat(formatted).contains("input.txt");
        assertThat(formatted).contains("-->");  // Rust-style location marker
    }

    @Test
    void advancedMode_diagnosticRecordHasAllFields() {
        var grammar = """
            Number <- < [0-9]+ >
            """;

        var result = PegParser.generateCstParser(grammar, "test.diag.fields", "FieldsParser", ErrorReporting.ADVANCED);

        assertThat(result.isSuccess()).isTrue();
        var source = result.unwrap();

        // Diagnostic record should have all required fields
        assertThat(source).contains("Severity severity");
        assertThat(source).contains("String message");
        assertThat(source).contains("SourceSpan span");
    }

    @Test
    void basicMode_doesNotGenerateDiagnosticsFeatures() {
        var grammar = """
            Number <- < [0-9]+ >
            """;

        var result = PegParser.generateCstParser(grammar, "test.basic", "BasicParser", ErrorReporting.BASIC);

        assertThat(result.isSuccess()).isTrue();
        var source = result.unwrap();

        // BASIC mode should NOT have diagnostics features
        assertThat(source).doesNotContain("parseWithDiagnostics");
        assertThat(source).doesNotContain("ParseResultWithDiagnostics");
        assertThat(source).doesNotContain("enum Severity");
    }

    // Helper to compile and load generated parser
    private Class<?> compileAndLoad(String source, String className) throws Exception {
        var tempDir = Files.createTempDirectory("peglib-diag-test");
        var packagePath = className.substring(0, className.lastIndexOf('.')).replace('.', '/');
        var simpleClassName = className.substring(className.lastIndexOf('.') + 1);

        var packageDir = tempDir.resolve(packagePath);
        Files.createDirectories(packageDir);

        var sourceFile = packageDir.resolve(simpleClassName + ".java");
        Files.writeString(sourceFile, source);

        // Compile
        var compiler = ToolProvider.getSystemJavaCompiler();
        var result = compiler.run(null, null, null,
            "-d", tempDir.toString(),
            "-cp", System.getProperty("java.class.path"),
            sourceFile.toString()
        );

        if (result != 0) {
            throw new RuntimeException("Compilation failed for " + className);
        }

        // Load
        var classLoader = new URLClassLoader(new URL[]{tempDir.toUri().toURL()});
        return classLoader.loadClass(className);
    }
}
