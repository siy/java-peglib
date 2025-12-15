package org.pragmatica.peg.examples;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;

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
}
