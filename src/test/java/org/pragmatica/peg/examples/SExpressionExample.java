package org.pragmatica.peg.examples;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S-expression parser example demonstrating Lisp-like syntax.
 *
 * Features showcased:
 * - Recursive grammar (lists can contain lists)
 * - CST parsing of nested structures
 * - Symbol handling with various characters
 */
class SExpressionExample {

    // S-expression grammar for CST parsing
    static final String GRAMMAR = """
        Sexpr   <- List / Atom

        List    <- '(' Sexpr* ')'

        Atom    <- Number / String / Symbol

        Number  <- < '-'? [0-9]+ >
        String  <- '"' < [^"]* > '"'
        Symbol  <- < [a-zA-Z_+*/<>=!?-][a-zA-Z0-9_+*/<>=!?-]* >

        %whitespace <- [ \\t\\r\\n]*
        """;

    @Test
    void parseNumber() {
        var lisp = PegParser.fromGrammar(GRAMMAR).unwrap();

        assertTrue(lisp.parseCst("42").isSuccess());
        assertTrue(lisp.parseCst("-17").isSuccess());
    }

    @Test
    void parseSymbol() {
        var lisp = PegParser.fromGrammar(GRAMMAR).unwrap();

        assertTrue(lisp.parseCst("foo").isSuccess());
        assertTrue(lisp.parseCst("hello-world").isSuccess());
        assertTrue(lisp.parseCst("+").isSuccess());
    }

    @Test
    void parseString() {
        var lisp = PegParser.fromGrammar(GRAMMAR).unwrap();

        assertTrue(lisp.parseCst("\"hello\"").isSuccess());
        assertTrue(lisp.parseCst("\"hello world\"").isSuccess());
    }

    @Test
    void parseEmptyList() {
        var lisp = PegParser.fromGrammar(GRAMMAR).unwrap();
        assertTrue(lisp.parseCst("()").isSuccess());
    }

    @Test
    void parseSimpleList() {
        var lisp = PegParser.fromGrammar(GRAMMAR).unwrap();
        assertTrue(lisp.parseCst("(+ 1 2)").isSuccess());
    }

    @Test
    void parseNestedList() {
        var lisp = PegParser.fromGrammar(GRAMMAR).unwrap();
        assertTrue(lisp.parseCst("(+ (* 2 3) 4)").isSuccess());
    }

    @Test
    void parseDefine() {
        var lisp = PegParser.fromGrammar(GRAMMAR).unwrap();
        assertTrue(lisp.parseCst("(define x 42)").isSuccess());
    }

    @Test
    void parseLambda() {
        var lisp = PegParser.fromGrammar(GRAMMAR).unwrap();
        assertTrue(lisp.parseCst("(lambda (x y) (+ x y))").isSuccess());
    }

    @Test
    void parseDeeplyNested() {
        var lisp = PegParser.fromGrammar(GRAMMAR).unwrap();
        assertTrue(lisp.parseCst("(((nested)))").isSuccess());
    }

    // Simple actions
    @Test
    void parseNumberWithAction() {
        var grammar = """
            Number <- < [0-9]+ > { return Long.parseLong($0); }
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();
        assertEquals(42L, parser.parse("42").unwrap());
    }

    @Test
    void parseSymbolWithAction() {
        var grammar = """
            Symbol <- < [a-z]+ > { return $0; }
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();
        assertEquals("foo", parser.parse("foo").unwrap());
    }
}
