package org.pragmatica.peg.examples;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.generator.ErrorReporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Source generation example demonstrating standalone parser generation.
 *
 * Features showcased:
 * - Generating Java source code from grammar
 * - Self-contained parser (only depends on pragmatica-lite:core)
 * - Can be compiled with javac and used independently
 */
class SourceGenerationExample {

    /**
     * Generate a standalone calculator parser.
     *
     * The generated parser:
     * - Is a single Java file
     * - Only depends on pragmatica-lite:core for Result/Option
     * - Has packrat memoization built-in
     * - Can be compiled and used without peglib
     */
    @Test
    void generateCalculatorParser() {
        var grammar = """
            Expr   <- Term (('+' / '-') Term)*
            Term   <- Factor (('*' / '/') Factor)*
            Factor <- '(' Expr ')' / Number
            Number <- < [0-9]+ >
            %whitespace <- [ \\t]*
            """;

        var result = PegParser.generateParser(
            grammar,
            "com.example.calc",
            "Calculator"
        );

        assertTrue(result.isSuccess());

        var source = result.unwrap();

        // Verify package declaration
        assertTrue(source.contains("package com.example.calc;"));

        // Verify class declaration
        assertTrue(source.contains("public final class Calculator"));

        // Verify only pragmatica-lite imports
        assertTrue(source.contains("import org.pragmatica.lang.Result;"));
        assertFalse(source.contains("import org.pragmatica.peg."));

        // Verify parse method
        assertTrue(source.contains("public Result<Object> parse(String input)"));

        // Verify rule methods generated
        assertTrue(source.contains("parse_Expr()"));
        assertTrue(source.contains("parse_Term()"));
        assertTrue(source.contains("parse_Factor()"));
        assertTrue(source.contains("parse_Number()"));

        // Verify packrat cache
        assertTrue(source.contains("cache"));
        assertTrue(source.contains("cacheKey"));

        // Print generated source for inspection
        System.out.println("=== Generated Calculator Parser ===");
        System.out.println(source);
    }

    /**
     * Generate a JSON parser.
     */
    @Test
    void generateJsonParser() {
        var grammar = """
            Value   <- Object / Array / String / Number / True / False / Null
            Object  <- '{' (Pair (',' Pair)*)? '}'
            Pair    <- String ':' Value
            Array   <- '[' (Value (',' Value)*)? ']'
            String  <- '"' [^"]* '"'
            Number  <- '-'? [0-9]+ ('.' [0-9]+)?
            True    <- 'true'
            False   <- 'false'
            Null    <- 'null'
            %whitespace <- [ \\t\\r\\n]*
            """;

        var result = PegParser.generateParser(
            grammar,
            "com.example.json",
            "JsonParser"
        );

        assertTrue(result.isSuccess());

        var source = result.unwrap();

        assertTrue(source.contains("public final class JsonParser"));
        assertTrue(source.contains("parse_Value"));
        assertTrue(source.contains("parse_Object"));
        assertTrue(source.contains("parse_Array"));
        assertTrue(source.contains("parse_String"));
        assertTrue(source.contains("parse_Number"));

        // Print snippet
        System.out.println("=== Generated JSON Parser (first 100 lines) ===");
        var lines = source.split("\n");
        for (int i = 0; i < Math.min(100, lines.length); i++) {
            System.out.println(lines[i]);
        }
        System.out.println("... (" + lines.length + " total lines)");
    }

    /**
     * Generate parser with actions.
     * Note: Actions are included but require runtime compilation to execute.
     * For truly standalone parsing with semantic values, use runtime mode.
     */
    @Test
    void generateParserWithActions() {
        var grammar = """
            Number <- < [0-9]+ > { return Integer.parseInt($0); }
            """;

        var result = PegParser.generateParser(
            grammar,
            "com.example",
            "NumberParser"
        );

        assertTrue(result.isSuccess());

        var source = result.unwrap();

        // Action code is embedded
        assertTrue(source.contains("Integer.parseInt"));
    }

    /**
     * Demonstrate that generated parser has all PEG operators.
     */
    @Test
    void generateParserWithAllOperators() {
        var grammar = """
            # All PEG operators
            Root       <- Sequence Choice ZeroMore OneMore Optional Rep

            Sequence   <- 'a' 'b' 'c'
            Choice     <- 'x' / 'y' / 'z'
            ZeroMore   <- 'd'*
            OneMore    <- 'e'+
            Optional   <- 'f'?
            Rep        <- 'g'{2,4}

            # Predicates
            AndPred    <- &'h' 'h'
            NotPred    <- !'i' .

            # Token and capture
            Token      <- < [a-z]+ >
            Capture    <- $name< [A-Z]+ > $name

            %whitespace <- [ ]*
            """;

        var result = PegParser.generateParser(
            grammar,
            "com.example.all",
            "AllOperatorsParser"
        );

        assertTrue(result.isSuccess());

        var source = result.unwrap();

        // Verify all operators are handled
        assertTrue(source.contains("parse_Sequence"));
        assertTrue(source.contains("parse_Choice"));
        assertTrue(source.contains("parse_ZeroMore"));
        assertTrue(source.contains("parse_OneMore"));
        assertTrue(source.contains("parse_Optional"));
        assertTrue(source.contains("parse_Rep"));
        assertTrue(source.contains("parse_AndPred"));
        assertTrue(source.contains("parse_NotPred"));
        assertTrue(source.contains("parse_Token"));
        assertTrue(source.contains("parse_Capture"));

        // Verify helper methods
        assertTrue(source.contains("matchLiteral"));
        assertTrue(source.contains("matchCharClass"));
        assertTrue(source.contains("matchAny"));
        assertTrue(source.contains("skipWhitespace"));
    }

    // === CST Parser Generation Tests ===

    /**
     * Generate a CST parser that preserves tree structure and trivia.
     */
    @Test
    void generateCstCalculatorParser() {
        var grammar = """
            Expr   <- Term (('+' / '-') Term)*
            Term   <- Factor (('*' / '/') Factor)*
            Factor <- '(' Expr ')' / Number
            Number <- < [0-9]+ >
            %whitespace <- [ \\t]*
            """;

        var result = PegParser.generateCstParser(
            grammar,
            "com.example.calc",
            "CstCalculator"
        );

        assertTrue(result.isSuccess(), () -> "Generation failed: " + result);

        var source = result.unwrap();

        // Verify package declaration
        assertTrue(source.contains("package com.example.calc;"));

        // Verify class declaration
        assertTrue(source.contains("public final class CstCalculator"));

        // Verify CST types are included
        assertTrue(source.contains("public sealed interface CstNode"));
        assertTrue(source.contains("record Terminal"));
        assertTrue(source.contains("record NonTerminal"));
        assertTrue(source.contains("record Token"));
        assertTrue(source.contains("public record SourceSpan"));
        assertTrue(source.contains("public record SourceLocation"));
        assertTrue(source.contains("public sealed interface Trivia"));

        // Verify parse returns CstNode
        assertTrue(source.contains("public Result<CstNode> parse(String input)"));

        // Verify trivia collection
        assertTrue(source.contains("skipWhitespace()"));
        assertTrue(source.contains("List<Trivia>"));

        // Verify only pragmatica-lite imports
        assertTrue(source.contains("import org.pragmatica.lang.Result;"));
        assertFalse(source.contains("import org.pragmatica.peg."));

        // Print stats
        var lines = source.split("\n");
        System.out.println("=== Generated CST Calculator Parser ===");
        System.out.println("Total lines: " + lines.length);
    }

    /**
     * Generate a CST JSON parser with trivia support.
     */
    @Test
    void generateCstJsonParser() {
        var grammar = """
            Value   <- Object / Array / String / Number / True / False / Null
            Object  <- '{' (Pair (',' Pair)*)? '}'
            Pair    <- String ':' Value
            Array   <- '[' (Value (',' Value)*)? ']'
            String  <- '"' [^"]* '"'
            Number  <- '-'? [0-9]+ ('.' [0-9]+)?
            True    <- 'true'
            False   <- 'false'
            Null    <- 'null'
            %whitespace <- [ \\t\\r\\n]*
            """;

        var result = PegParser.generateCstParser(
            grammar,
            "com.example.json",
            "CstJsonParser"
        );

        assertTrue(result.isSuccess(), () -> "Generation failed: " + result);

        var source = result.unwrap();

        assertTrue(source.contains("public final class CstJsonParser"));
        assertTrue(source.contains("parse_Value"));
        assertTrue(source.contains("CstNode"));
        assertTrue(source.contains("Trivia"));

        // Print snippet
        var lines = source.split("\n");
        System.out.println("=== Generated CST JSON Parser ===");
        System.out.println("Total lines: " + lines.length);
    }

    /**
     * Usage example showing how to use generated parser.
     *
     * After generating source:
     * 1. Write to file: Files.writeString(Path.of("Calculator.java"), source)
     * 2. Compile: javac -cp pragmatica-lite.jar Calculator.java
     * 3. Use: var parser = new Calculator(); parser.parse("1+2")
     */
    @Test
    void usageExample() {
        // This test documents the workflow
        var grammar = "Number <- [0-9]+";

        // Step 1: Generate source
        var result = PegParser.generateParser(grammar, "com.myapp", "MyParser");
        assertTrue(result.isSuccess());

        // Step 2: Write to file (commented out for test)
        // var source = result.unwrap();
        // Files.writeString(Path.of("src/main/java/com/myapp/MyParser.java"), source);

        // Step 3: Compile with Maven/Gradle (classpath must include pragmatica-lite:core)
        // mvn compile

        // Step 4: Use generated parser
        // var parser = new MyParser();
        // var parseResult = parser.parse("123");

        System.out.println("=== Generated Parser Usage ===");
        System.out.println("""
            // 1. Generate and save source
            var source = PegParser.generateParser(grammar, "com.myapp", "MyParser").unwrap();
            Files.writeString(Path.of("MyParser.java"), source);

            // 2. Compile (add to your build)
            // The generated class only needs pragmatica-lite:core on classpath

            // 3. Use the parser
            var parser = new MyParser();
            Result<Object> result = parser.parse("your input here");

            result.fold(
                error -> System.err.println("Parse failed: " + error),
                value -> System.out.println("Parsed: " + value)
            );
            """);
    }

    // === JBCT Compliance Tests ===

    /**
     * Verify generated parsers use typed Cause instead of RuntimeException.
     * This is a JBCT requirement: no business exceptions, use Cause types.
     */
    @Test
    void generatedParser_usesCauseNotException() {
        var grammar = "Number <- [0-9]+";

        var result = PegParser.generateParser(grammar, "com.example", "TestParser");
        assertTrue(result.isSuccess());

        var source = result.unwrap();

        // Should not use RuntimeException
        assertFalse(source.contains("new RuntimeException"), "Generated code should not use RuntimeException");

        // Should define ParseError implementing Cause
        assertTrue(source.contains("record ParseError"), "Should have ParseError record");
        assertTrue(source.contains("implements Cause"), "ParseError should implement Cause");

        // Should import Cause
        assertTrue(source.contains("import org.pragmatica.lang.Cause;"), "Should import Cause");
    }

    /**
     * Verify CST parser uses typed Cause instead of RuntimeException.
     */
    @Test
    void generatedCstParser_usesCauseNotException() {
        var grammar = "Number <- [0-9]+";

        var result = PegParser.generateCstParser(grammar, "com.example", "TestParser");
        assertTrue(result.isSuccess());

        var source = result.unwrap();

        // Should not use RuntimeException
        assertFalse(source.contains("new RuntimeException"), "Generated CST code should not use RuntimeException");

        // Should define ParseError implementing Cause
        assertTrue(source.contains("record ParseError"), "Should have ParseError record");
        assertTrue(source.contains("implements Cause"), "ParseError should implement Cause");
    }

    // === Advanced Error Reporting Tests ===

    /**
     * Generate CST parser with ADVANCED error reporting (Rust-style diagnostics).
     *
     * When ErrorReporting.ADVANCED is used, the generated parser includes:
     * - Severity enum (ERROR, WARNING, INFO, HINT)
     * - Diagnostic record with Rust-style formatting
     * - DiagnosticLabel for primary/secondary labels
     * - ParseResultWithDiagnostics for collecting multiple errors
     * - parseWithDiagnostics() method
     * - Error recovery helpers
     */
    @Test
    void generateCstParser_withAdvancedErrorReporting() {
        var grammar = """
            Expr   <- Term (('+' / '-') Term)*
            Term   <- Factor (('*' / '/') Factor)*
            Factor <- '(' Expr ')' / Number
            Number <- < [0-9]+ >
            %whitespace <- [ \\t]*
            """;

        var result = PegParser.generateCstParser(
            grammar,
            "com.example.calc",
            "AdvancedCalculator",
            ErrorReporting.ADVANCED
        );

        assertTrue(result.isSuccess(), () -> "Generation failed: " + result);

        var source = result.unwrap();

        // Verify Severity enum
        assertThat(source).contains("public enum Severity");
        assertThat(source).contains("ERROR(\"error\")");
        assertThat(source).contains("WARNING(\"warning\")");
        assertThat(source).contains("INFO(\"info\")");
        assertThat(source).contains("HINT(\"hint\")");

        // Verify Diagnostic record with Rust-style formatting
        assertThat(source).contains("public record Diagnostic");
        assertThat(source).contains("public String format(String source, String filename)");
        assertThat(source).contains("formatSimple()");

        // Verify DiagnosticLabel for labeling source spans
        assertThat(source).contains("public record DiagnosticLabel");
        assertThat(source).contains("DiagnosticLabel.primary");
        assertThat(source).contains("DiagnosticLabel.secondary");

        // Verify ParseResultWithDiagnostics
        assertThat(source).contains("public record ParseResultWithDiagnostics");
        assertThat(source).contains("formatDiagnostics");
        assertThat(source).contains("hasErrors()");
        assertThat(source).contains("errorCount()");

        // Verify parseWithDiagnostics method
        assertThat(source).contains("public ParseResultWithDiagnostics parseWithDiagnostics(String input)");

        // Verify Error node type for CST
        assertThat(source).contains("record Error(SourceSpan span, String skippedText");

        // Verify error recovery helpers
        assertThat(source).contains("skipToRecoveryPoint");
        assertThat(source).contains("trackFailure");
        assertThat(source).contains("addDiagnostic");

        // Print stats
        var lines = source.split("\n");
        System.out.println("=== Generated CST Calculator with ADVANCED Error Reporting ===");
        System.out.println("Total lines: " + lines.length);
        System.out.println("(BASIC mode would be ~200 lines smaller)");
    }

    /**
     * Demonstrate the difference between BASIC and ADVANCED modes.
     */
    @Test
    void compareBasicVsAdvancedErrorReporting() {
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- [ ]*
            """;

        var basicResult = PegParser.generateCstParser(grammar, "com.example", "BasicParser", ErrorReporting.BASIC);
        var advancedResult = PegParser.generateCstParser(grammar, "com.example", "AdvancedParser", ErrorReporting.ADVANCED);

        assertTrue(basicResult.isSuccess());
        assertTrue(advancedResult.isSuccess());

        var basicSource = basicResult.unwrap();
        var advancedSource = advancedResult.unwrap();

        // BASIC mode should NOT have advanced features
        assertFalse(basicSource.contains("enum Severity"));
        assertFalse(basicSource.contains("parseWithDiagnostics"));
        assertFalse(basicSource.contains("ParseResultWithDiagnostics"));

        // ADVANCED mode should have all features
        assertTrue(advancedSource.contains("enum Severity"));
        assertTrue(advancedSource.contains("parseWithDiagnostics"));
        assertTrue(advancedSource.contains("ParseResultWithDiagnostics"));

        // Both should have basic parse method
        assertTrue(basicSource.contains("public Result<CstNode> parse(String input)"));
        assertTrue(advancedSource.contains("public Result<CstNode> parse(String input)"));

        var basicLines = basicSource.split("\n").length;
        var advancedLines = advancedSource.split("\n").length;

        System.out.println("=== BASIC vs ADVANCED Error Reporting ===");
        System.out.println("BASIC mode:    " + basicLines + " lines");
        System.out.println("ADVANCED mode: " + advancedLines + " lines");
        System.out.println("Difference:    " + (advancedLines - basicLines) + " lines");
    }
}
