package org.pragmatica.peg.generator;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;

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
        assertTrue(source.contains("alt0"));
        assertTrue(source.contains("alt1"));
        assertTrue(source.contains("alt2"));
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
}
