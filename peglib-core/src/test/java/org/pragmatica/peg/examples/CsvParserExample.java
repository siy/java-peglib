package org.pragmatica.peg.examples;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CSV parser example demonstrating data format parsing.
 *
 * Features showcased:
 * - Line-oriented parsing
 * - Building records (List of String)
 * - No automatic whitespace skipping
 */
class CsvParserExample {

    // Simple CSV grammar (unquoted fields only)
    static final String SIMPLE_GRAMMAR = """
        Row   <- Field (',' Field)* {
            var fields = new java.util.ArrayList<String>();
            for (int i = 0; i < sv.size(); i++) {
                fields.add((String) sv.get(i));
            }
            return fields;
        }

        Field <- < [^,\\n]* > { return $0; }
        """;

    @Test
    void parseSingleField() {
        var csv = PegParser.fromGrammar(SIMPLE_GRAMMAR).unwrap();

        var result = csv.parse("hello").unwrap();
        var row = (List<?>) result;

        assertEquals(1, row.size());
        assertEquals("hello", row.get(0));
    }

    @Test
    void parseMultipleFields() {
        var csv = PegParser.fromGrammar(SIMPLE_GRAMMAR).unwrap();

        var result = csv.parse("a,b,c").unwrap();
        var row = (List<?>) result;

        assertEquals(3, row.size());
        assertEquals("a", row.get(0));
        assertEquals("b", row.get(1));
        assertEquals("c", row.get(2));
    }

    @Test
    void parseEmptyFields() {
        var csv = PegParser.fromGrammar(SIMPLE_GRAMMAR).unwrap();

        var result = csv.parse("a,,c").unwrap();
        var row = (List<?>) result;

        assertEquals(3, row.size());
        assertEquals("a", row.get(0));
        assertEquals("", row.get(1));
        assertEquals("c", row.get(2));
    }

    @Test
    void parseWithSpaces() {
        var csv = PegParser.fromGrammar(SIMPLE_GRAMMAR).unwrap();

        // Spaces are preserved (not skipped)
        var result = csv.parse(" a , b , c ").unwrap();
        var row = (List<?>) result;

        assertEquals(3, row.size());
        assertEquals(" a ", row.get(0));
        assertEquals(" b ", row.get(1));
        assertEquals(" c ", row.get(2));
    }

    @Test
    void parseNumbers() {
        var csv = PegParser.fromGrammar(SIMPLE_GRAMMAR).unwrap();

        var result = csv.parse("1,2,3").unwrap();
        var row = (List<?>) result;

        assertEquals(3, row.size());
        assertEquals("1", row.get(0));
        assertEquals("2", row.get(1));
        assertEquals("3", row.get(2));
    }

    // CST parsing for quoted fields
    static final String FULL_GRAMMAR = """
        Row   <- Field (',' Field)*
        Field <- Quoted / Unquoted
        Quoted   <- '"' [^"]* '"'
        Unquoted <- [^,\\n]*
        """;

    @Test
    void parseCst_quotedFields() {
        var csv = PegParser.fromGrammar(FULL_GRAMMAR).unwrap();
        assertTrue(csv.parseCst("\"hello\",\"world\"").isSuccess());
    }

    @Test
    void parseCst_mixedFields() {
        var csv = PegParser.fromGrammar(FULL_GRAMMAR).unwrap();
        assertTrue(csv.parseCst("name,\"Alice\",30").isSuccess());
    }

    @Test
    void parseCst_quotedWithComma() {
        var csv = PegParser.fromGrammar(FULL_GRAMMAR).unwrap();
        assertTrue(csv.parseCst("\"hello, world\",test").isSuccess());
    }
}
