package org.pragmatica.peg.generator;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;

import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class ParserGeneratorTest {

    @Test
    void generate_simpleLiteral_producesValidJava() {
        var result = PegParser.generateParser(
            "Root <- 'hello'",
            "com.example.parser",
            "HelloParser"
        );

        assertTrue(result.isSuccess());
        var source = result.unwrap();

        assertTrue(source.contains("package com.example.parser;"));
        assertTrue(source.contains("public final class HelloParser"));
        assertTrue(source.contains("parse_Root"));
        assertTrue(source.contains("matchLiteral(\"hello\""));
    }

    @Test
    void generate_withWhitespace_includesSkipMethod() {
        var result = PegParser.generateParser("""
            Number <- [0-9]+
            %whitespace <- [ \\t]*
            """,
            "com.example",
            "NumberParser"
        );

        assertTrue(result.isSuccess());
        var source = result.unwrap();

        assertTrue(source.contains("skipWhitespace()"));
        assertTrue(source.contains("matchCharClass"));
    }

    @Test
    void generate_withAction_includesActionCode() {
        var result = PegParser.generateParser("""
            Number <- < [0-9]+ > { return Integer.parseInt($0); }
            """,
            "com.example",
            "IntParser"
        );

        assertTrue(result.isSuccess());
        var source = result.unwrap();

        assertTrue(source.contains("Integer.parseInt"));
    }

    @Test
    void generate_calculator_producesValidCode() {
        var result = PegParser.generateParser("""
            Expr   <- Term ('+' Term)*
            Term   <- Factor ('*' Factor)*
            Factor <- Number
            Number <- < [0-9]+ >
            %whitespace <- [ ]*
            """,
            "com.example.calc",
            "Calculator"
        );

        assertTrue(result.isSuccess());
        var source = result.unwrap();

        assertTrue(source.contains("parse_Expr"));
        assertTrue(source.contains("parse_Term"));
        assertTrue(source.contains("parse_Factor"));
        assertTrue(source.contains("parse_Number"));
    }

    @Test
    void generate_withReference_generatesRecursiveCall() {
        var result = PegParser.generateParser("""
            A <- B
            B <- 'x'
            """,
            "com.test",
            "RefParser"
        );

        assertTrue(result.isSuccess());
        var source = result.unwrap();

        assertTrue(source.contains("parse_B()"));
    }

    @Test
    void generate_withChoice_generatesAlternatives() {
        var result = PegParser.generateParser(
            "Choice <- 'a' / 'b' / 'c'",
            "com.test",
            "ChoiceParser"
        );

        assertTrue(result.isSuccess());
        var source = result.unwrap();

        assertTrue(source.contains("choiceStart"));
        assertTrue(source.contains("alt0_0"));
        assertTrue(source.contains("alt0_1"));
        assertTrue(source.contains("alt0_2"));
    }

    @Test
    void generate_withQuantifiers_generatesLoops() {
        var result = PegParser.generateParser("""
            ZeroOrMore <- 'a'*
            OneOrMore  <- 'b'+
            Optional   <- 'c'?
            Repetition <- 'd'{2,4}
            """,
            "com.test",
            "QuantParser"
        );

        assertTrue(result.isSuccess());
        var source = result.unwrap();

        assertTrue(source.contains("parse_ZeroOrMore"));
        assertTrue(source.contains("parse_OneOrMore"));
        assertTrue(source.contains("parse_Optional"));
        assertTrue(source.contains("parse_Repetition"));
        assertTrue(source.contains("repCount"));
    }

    @Test
    void generate_onlyDependsOnPragmatikaLite() {
        var result = PegParser.generateParser(
            "Root <- 'test'",
            "com.example",
            "TestParser"
        );

        assertTrue(result.isSuccess());
        var source = result.unwrap();

        // Should import pragmatica-lite types
        assertTrue(source.contains("import org.pragmatica.lang.Option;"));
        assertTrue(source.contains("import org.pragmatica.lang.Result;"));

        // Should NOT import peglib types
        assertFalse(source.contains("import org.pragmatica.peg."));
    }

    // === Advanced Error Reporting Tests ===

    @Test
    void generateCst_basicMode_doesNotIncludeDiagnostics() {
        var result = PegParser.generateCstParser(
            "Root <- 'hello'",
            "com.example",
            "BasicParser",
            ErrorReporting.BASIC
        );

        assertTrue(result.isSuccess());
        var source = result.unwrap();

        // Should NOT include advanced diagnostic types
        assertFalse(source.contains("enum Severity"));
        assertFalse(source.contains("record Diagnostic"));
        assertFalse(source.contains("record DiagnosticLabel"));
        assertFalse(source.contains("parseWithDiagnostics"));
        assertFalse(source.contains("ParseResultWithDiagnostics"));
    }

    @Test
    void generateCst_advancedMode_includesDiagnosticTypes() {
        var result = PegParser.generateCstParser(
            "Root <- 'hello'",
            "com.example",
            "AdvancedParser",
            ErrorReporting.ADVANCED
        );

        assertTrue(result.isSuccess());
        var source = result.unwrap();

        // Should include advanced diagnostic types
        assertThat(source).contains("enum Severity");
        assertThat(source).contains("ERROR(\"error\")");
        assertThat(source).contains("WARNING(\"warning\")");
        assertThat(source).contains("record DiagnosticLabel");
        assertThat(source).contains("record Diagnostic");
        assertThat(source).contains("record ParseResultWithDiagnostics");
    }

    @Test
    void generateCst_advancedMode_includesParseWithDiagnosticsMethod() {
        var result = PegParser.generateCstParser(
            "Root <- 'hello'",
            "com.example",
            "AdvancedParser",
            ErrorReporting.ADVANCED
        );

        assertTrue(result.isSuccess());
        var source = result.unwrap();

        // Should include parseWithDiagnostics method
        assertThat(source).contains("public ParseResultWithDiagnostics parseWithDiagnostics(String input)");
        assertThat(source).contains("ParseResultWithDiagnostics.success");
        assertThat(source).contains("ParseResultWithDiagnostics.withErrors");
    }

    @Test
    void generateCst_advancedMode_includesRustStyleFormatting() {
        var result = PegParser.generateCstParser(
            "Root <- 'hello'",
            "com.example",
            "AdvancedParser",
            ErrorReporting.ADVANCED
        );

        assertTrue(result.isSuccess());
        var source = result.unwrap();

        // Should include Rust-style formatting method
        assertThat(source).contains("public String format(String source, String filename)");
        assertThat(source).contains("formatDiagnostics");
        assertThat(source).contains("formatUnderlines");
        assertThat(source).contains("getLabelsOnLine");
    }

    @Test
    void generateCst_advancedMode_includesErrorRecoveryHelpers() {
        var result = PegParser.generateCstParser(
            "Root <- 'hello'",
            "com.example",
            "AdvancedParser",
            ErrorReporting.ADVANCED
        );

        assertTrue(result.isSuccess());
        var source = result.unwrap();

        // Should include error recovery helpers
        assertThat(source).contains("skipToRecoveryPoint");
        assertThat(source).contains("trackFailure");
        assertThat(source).contains("addDiagnostic");
        assertThat(source).contains("furthestFailure");
    }

    @Test
    void generateCst_advancedMode_includesErrorNodeType() {
        var result = PegParser.generateCstParser(
            "Root <- 'hello'",
            "com.example",
            "AdvancedParser",
            ErrorReporting.ADVANCED
        );

        assertTrue(result.isSuccess());
        var source = result.unwrap();

        // Should include Error node type for CST
        assertThat(source).contains("record Error(SourceSpan span, String skippedText");
    }

    @Test
    void generateCst_advancedMode_diagnosticHasHelperMethods() {
        var result = PegParser.generateCstParser(
            "Root <- 'hello'",
            "com.example",
            "AdvancedParser",
            ErrorReporting.ADVANCED
        );

        assertTrue(result.isSuccess());
        var source = result.unwrap();

        // Should include Diagnostic helper methods
        assertThat(source).contains("Diagnostic withLabel(String labelMessage)");
        assertThat(source).contains("Diagnostic withSecondaryLabel(SourceSpan labelSpan, String labelMessage)");
        assertThat(source).contains("Diagnostic withNote(String note)");
        assertThat(source).contains("Diagnostic withHelp(String help)");
    }

    @Test
    void generateCst_defaultMode_isBasic() {
        var result = PegParser.generateCstParser(
            "Root <- 'hello'",
            "com.example",
            "DefaultParser"
        );

        assertTrue(result.isSuccess());
        var source = result.unwrap();

        // Default should be BASIC - no advanced features
        assertFalse(source.contains("parseWithDiagnostics"));
        assertFalse(source.contains("enum Severity"));
    }

    @Test
    void generateCst_advancedMode_includesErrorCaseInSwitch() {
        var result = PegParser.generateCstParser(
            "Root <- 'hello'",
            "com.example",
            "AdvancedParser",
            ErrorReporting.ADVANCED
        );

        assertTrue(result.isSuccess());
        var source = result.unwrap();

        // CstNode.Error should be defined
        assertThat(source).contains("record Error(SourceSpan span, String skippedText, String expected,");

        // attachTrailingTrivia switch should handle Error case
        assertThat(source).contains("case CstNode.Error err -> new CstNode.Error(");
        assertThat(source).contains("err.expected()");
    }

    @Test
    void generateCst_basicMode_noErrorCaseInSwitch() {
        var result = PegParser.generateCstParser(
            "Root <- 'hello'",
            "com.example",
            "BasicParser",
            ErrorReporting.BASIC
        );

        assertTrue(result.isSuccess());
        var source = result.unwrap();

        // CstNode.Error should NOT be defined in BASIC mode
        assertFalse(source.contains("record Error(SourceSpan span, String skippedText"));

        // No Error case in switch
        assertFalse(source.contains("case CstNode.Error"));
    }

    @Test
    void generatedAstParser_errorReportsAtFurthestPosition() throws Exception {
        // Grammar that requires 'abc' followed by letters - no backtracking possible
        var result = PegParser.generateParser("""
            Root <- 'start' [a-z]+
            %whitespace <- [ ]*
            """,
            "test.furthest",
            "FurthestAstParser"
        );

        assertTrue(result.isSuccess());
        var source = result.unwrap();

        // Compile and run
        var parser = compileAndInstantiate(source, "test.furthest.FurthestAstParser");
        var parseMethod = parser.getClass().getMethod("parse", String.class);

        // Parse valid input - should succeed
        var validResult = parseMethod.invoke(parser, "start abc");
        assertThat(validResult.toString()).contains("Success");

        // Parse input with error after 'start' - number instead of letters
        // "start 123" - error is at position 6 (the '1'), not at 1:1
        var errorResult = parseMethod.invoke(parser, "start 123");
        var errorString = errorResult.toString();
        assertThat(errorString).contains("Failure");
        // Should report error at column 7 (after "start "), not at column 1
        assertThat(errorString).doesNotContain("column=1,");
        assertThat(errorString).contains("column=7");
    }

    @Test
    void generatedCstParser_errorReportsAtFurthestPosition() throws Exception {
        var result = PegParser.generateCstParser("""
            Root <- 'start' [a-z]+
            %whitespace <- [ ]*
            """,
            "test.furthest.cst",
            "FurthestCstParser",
            ErrorReporting.BASIC
        );

        assertTrue(result.isSuccess());
        var source = result.unwrap();

        // Compile and run
        var parser = compileAndInstantiate(source, "test.furthest.cst.FurthestCstParser");
        var parseMethod = parser.getClass().getMethod("parse", String.class);

        // Parse input with error after 'start'
        var errorResult = parseMethod.invoke(parser, "start 123");
        var errorString = errorResult.toString();
        assertThat(errorString).contains("Failure");
        // Should report error at furthest position, not at 1:1
        assertThat(errorString).doesNotContain("at 1:1");
        assertThat(errorString).contains("1:7");
    }

    @Test
    void generatedCstParser_advanced_errorReportsAtFurthestPosition() throws Exception {
        var result = PegParser.generateCstParser("""
            Root <- 'start' [a-z]+
            %whitespace <- [ ]*
            """,
            "test.furthest.adv",
            "FurthestAdvParser",
            ErrorReporting.ADVANCED
        );

        assertTrue(result.isSuccess());
        var source = result.unwrap();

        // Compile and run
        var parser = compileAndInstantiate(source, "test.furthest.adv.FurthestAdvParser");
        var parseWithDiagMethod = parser.getClass().getMethod("parseWithDiagnostics", String.class);

        // Parse input with error after 'start'
        var diagResult = parseWithDiagMethod.invoke(parser, "start 123");

        // Check diagnostics
        var formatMethod = diagResult.getClass().getMethod("formatDiagnostics", String.class);
        var formatted = (String) formatMethod.invoke(diagResult, "test.txt");

        // Should report error at furthest position (line 1, column 7)
        assertThat(formatted).contains("1:7");
        assertThat(formatted).doesNotContain("1:1");
    }

    // Helper to compile and instantiate a generated parser
    private Object compileAndInstantiate(String source, String className) throws Exception {
        var tempDir = Files.createTempDirectory("peglib-test");
        var packagePath = className.substring(0, className.lastIndexOf('.')).replace('.', '/');
        var simpleClassName = className.substring(className.lastIndexOf('.') + 1);

        var packageDir = tempDir.resolve(packagePath);
        Files.createDirectories(packageDir);

        var sourceFile = packageDir.resolve(simpleClassName + ".java");
        Files.writeString(sourceFile, source);

        // Compile with error capture
        var compiler = ToolProvider.getSystemJavaCompiler();
        var errStream = new java.io.ByteArrayOutputStream();
        var result = compiler.run(null, null, errStream,
            "-d", tempDir.toString(),
            "-cp", System.getProperty("java.class.path"),
            sourceFile.toString()
        );

        if (result != 0) {
            System.err.println("=== Generated source ===");
            System.err.println(source);
            System.err.println("=== Compilation errors ===");
            System.err.println(errStream);
            throw new RuntimeException("Compilation failed for " + className + ": " + errStream);
        }

        // Load and instantiate
        var classLoader = new URLClassLoader(new URL[]{tempDir.toUri().toURL()});
        var parserClass = classLoader.loadClass(className);
        return parserClass.getDeclaredConstructor().newInstance();
    }
}
