package org.pragmatica.peg.examples;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.tree.CstNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSON parser example demonstrating CST parsing of structured data.
 *
 * Features showcased:
 * - Complex grammar with multiple rule types
 * - CST parsing (tree structure)
 * - Recursive structures (nested objects/arrays)
 * - Token boundaries for capturing values
 */
class JsonParserExample {

    // JSON grammar for CST parsing
    static final String JSON_GRAMMAR = """
        Value   <- Object / Array / String / Number / True / False / Null

        Object  <- '{' MemberList? '}'
        MemberList <- Member (',' Member)*
        Member  <- String ':' Value

        Array   <- '[' ValueList? ']'
        ValueList <- Value (',' Value)*

        String  <- '"' < [^"]* > '"'
        Number  <- < '-'? [0-9]+ ('.' [0-9]+)? >

        True    <- 'true'
        False   <- 'false'
        Null    <- 'null'

        %whitespace <- [ \\t\\r\\n]*
        """;

    @Test
    void parseNull() {
        var json = PegParser.fromGrammar(JSON_GRAMMAR).unwrap();
        assertTrue(json.parseCst("null").isSuccess());
    }

    @Test
    void parseBoolean() {
        var json = PegParser.fromGrammar(JSON_GRAMMAR).unwrap();

        assertTrue(json.parseCst("true").isSuccess());
        assertTrue(json.parseCst("false").isSuccess());
    }

    @Test
    void parseNumber() {
        var json = PegParser.fromGrammar(JSON_GRAMMAR).unwrap();

        assertTrue(json.parseCst("42").isSuccess());
        assertTrue(json.parseCst("-17").isSuccess());
        assertTrue(json.parseCst("3.14").isSuccess());
    }

    @Test
    void parseString() {
        var json = PegParser.fromGrammar(JSON_GRAMMAR).unwrap();

        assertTrue(json.parseCst("\"hello\"").isSuccess());
        assertTrue(json.parseCst("\"\"").isSuccess());
    }

    @Test
    void parseEmptyArray() {
        var json = PegParser.fromGrammar(JSON_GRAMMAR).unwrap();
        assertTrue(json.parseCst("[]").isSuccess());
    }

    @Test
    void parseArray() {
        var json = PegParser.fromGrammar(JSON_GRAMMAR).unwrap();
        assertTrue(json.parseCst("[1, 2, 3]").isSuccess());
        assertTrue(json.parseCst("[1, \"two\", true, null]").isSuccess());
    }

    @Test
    void parseEmptyObject() {
        var json = PegParser.fromGrammar(JSON_GRAMMAR).unwrap();
        assertTrue(json.parseCst("{}").isSuccess());
    }

    @Test
    void parseObject() {
        var json = PegParser.fromGrammar(JSON_GRAMMAR).unwrap();
        assertTrue(json.parseCst("{\"name\": \"Alice\"}").isSuccess());
        assertTrue(json.parseCst("{\"a\": 1, \"b\": 2}").isSuccess());
    }

    @Test
    void parseNested() {
        var json = PegParser.fromGrammar(JSON_GRAMMAR).unwrap();
        assertTrue(json.parseCst("{\"arr\": [1, 2], \"obj\": {\"x\": 1}}").isSuccess());
    }

    // Primitive parsing with actions
    @Test
    void parseWithActions_number() {
        var grammar = """
            Number <- < [0-9]+ > { return Long.parseLong($0); }
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();
        assertEquals(42L, parser.parse("42").unwrap());
    }

    @Test
    void parseWithActions_boolean() {
        var grammar = """
            Bool <- True / False
            True  <- 'true'  { return Boolean.TRUE; }
            False <- 'false' { return Boolean.FALSE; }
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();
        assertEquals(true, parser.parse("true").unwrap());
        assertEquals(false, parser.parse("false").unwrap());
    }

}
