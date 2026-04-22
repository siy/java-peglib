package org.pragmatica.peg.examples;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Calculator example demonstrating semantic actions.
 *
 * Features showcased:
 * - Inline Java actions in { } blocks
 * - $0 for matched text
 * - $1/$2 for child values
 * - sv.toInt() for parsing integers
 */
class CalculatorExample {

    @Test
    void parseNumber() {
        var grammar = """
            Number <- < [0-9]+ > { return sv.toInt(); }
            """;
        var calc = PegParser.fromGrammar(grammar).unwrap();

        assertEquals(42, calc.parse("42").unwrap());
        assertEquals(123, calc.parse("123").unwrap());
        assertEquals(0, calc.parse("0").unwrap());
    }

    @Test
    void simpleAddition() {
        var grammar = """
            Sum    <- Number '+' Number { return (Integer)$1 + (Integer)$2; }
            Number <- < [0-9]+ > { return sv.toInt(); }
            %whitespace <- [ ]*
            """;
        var calc = PegParser.fromGrammar(grammar).unwrap();

        assertEquals(5, calc.parse("2 + 3").unwrap());
        assertEquals(100, calc.parse("50 + 50").unwrap());
    }

    @Test
    void simpleMultiplication() {
        var grammar = """
            Prod   <- Number '*' Number { return (Integer)$1 * (Integer)$2; }
            Number <- < [0-9]+ > { return sv.toInt(); }
            %whitespace <- [ ]*
            """;
        var calc = PegParser.fromGrammar(grammar).unwrap();

        assertEquals(12, calc.parse("3 * 4").unwrap());
        assertEquals(100, calc.parse("10 * 10").unwrap());
    }

    @Test
    void textToUppercase() {
        var grammar = """
            Word <- < [a-z]+ > { return $0.toUpperCase(); }
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertEquals("HELLO", parser.parse("hello").unwrap());
        assertEquals("WORLD", parser.parse("world").unwrap());
    }

    @Test
    void parseBoolean() {
        var grammar = """
            Bool <- True / False
            True  <- 'true'  { return Boolean.TRUE; }
            False <- 'false' { return Boolean.FALSE; }
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertEquals(true, parser.parse("true").unwrap());
        assertEquals(false, parser.parse("false").unwrap());
    }

    @Test
    void parseDouble() {
        var grammar = """
            Number <- < [0-9]+ '.' [0-9]+ > { return Double.parseDouble($0); }
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertEquals(3.14, parser.parse("3.14").unwrap());
        assertEquals(0.5, parser.parse("0.5").unwrap());
    }
}
