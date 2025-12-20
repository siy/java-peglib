package org.pragmatica.peg.generator;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;

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
}
