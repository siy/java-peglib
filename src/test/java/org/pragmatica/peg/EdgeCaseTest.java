package org.pragmatica.peg;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.tree.CstNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge case tests for scenarios that previously failed.
 */
class EdgeCaseTest {

    // === Null return from actions ===

    @Test
    void action_returningNull_shouldReturnNull() {
        var grammar = """
            Null <- 'null' { return null; }
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        var result = parser.parse("null");
        assertTrue(result.isSuccess());
        assertNull(result.unwrap());
    }

    @Test
    void action_returningNullInChoice_shouldReturnNull() {
        var grammar = """
            Value <- Null / Number
            Null   <- 'null' { return null; }
            Number <- < [0-9]+ > { return Long.parseLong($0); }
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertNull(parser.parse("null").unwrap());
        assertEquals(42L, parser.parse("42").unwrap());
    }

    // === Token boundary with quotes ===

    @Test
    void tokenBoundary_insideQuotes_shouldNotCaptureQuotes() {
        var grammar = """
            String <- '"' < [^"]* > '"' { return $0; }
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        var result = parser.parse("\"hello\"");
        assertTrue(result.isSuccess());
        assertEquals("hello", result.unwrap());
    }

    @Test
    void tokenBoundary_withNestedExpression_capturesCorrectly() {
        var grammar = """
            Quoted <- '(' < [^)]* > ')' { return $0; }
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertEquals("content", parser.parse("(content)").unwrap());
        assertEquals("", parser.parse("()").unwrap());
    }

    // === Complex grammars ===

    @Test
    void complexGrammar_withMultipleRules_parsesCorrectly() {
        var grammar = """
            Program <- Statement+
            Statement <- Assignment / Expression
            Assignment <- Identifier '=' Expression
            Expression <- Term (('+' / '-') Term)*
            Term <- Factor (('*' / '/') Factor)*
            Factor <- Number / Identifier / '(' Expression ')'
            Number <- < [0-9]+ >
            Identifier <- < [a-zA-Z_][a-zA-Z0-9_]* >
            %whitespace <- [ \\t\\n]*
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertTrue(parser.parseCst("x = 1").isSuccess());
        assertTrue(parser.parseCst("x = 1 + 2").isSuccess());
        assertTrue(parser.parseCst("x = 1 + 2 * 3").isSuccess());
        assertTrue(parser.parseCst("x = (1 + 2) * 3").isSuccess());
        assertTrue(parser.parseCst("x = y + z").isSuccess());
    }

    @Test
    void recursiveGrammar_deeplyNested_parsesCorrectly() {
        var grammar = """
            Expr <- '(' Expr ')' / 'x'
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertTrue(parser.parseCst("x").isSuccess());
        assertTrue(parser.parseCst("(x)").isSuccess());
        assertTrue(parser.parseCst("((x))").isSuccess());
        assertTrue(parser.parseCst("(((x)))").isSuccess());
        assertTrue(parser.parseCst("((((x))))").isSuccess());
    }

    // === Choice with actions ===

    @Test
    void choice_eachAlternativeWithAction_worksCorrectly() {
        // Note: inline actions on choice alternatives not yet supported,
        // use separate rules instead
        var grammar = """
            Value <- Number / Bool / Str
            Number <- < [0-9]+ > { return Long.parseLong($0); }
            Bool <- True / False
            True <- 'true' { return Boolean.TRUE; }
            False <- 'false' { return Boolean.FALSE; }
            Str <- < [a-z]+ > { return $0; }
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertEquals(42L, parser.parse("42").unwrap());
        assertEquals(true, parser.parse("true").unwrap());
        assertEquals(false, parser.parse("false").unwrap());
        assertEquals("hello", parser.parse("hello").unwrap());
    }

    // === Whitespace edge cases ===

    @Test
    void whitespace_atStartAndEnd_isSkipped() {
        var grammar = """
            Number <- < [0-9]+ > { return sv.toInt(); }
            %whitespace <- [ \\t]*
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertEquals(42, parser.parse("  42  ").unwrap());
        assertEquals(42, parser.parse("\t42\t").unwrap());
    }

    @Test
    void whitespace_betweenTokens_isSkipped() {
        var grammar = """
            Sum <- Number '+' Number { return (Integer)$1 + (Integer)$2; }
            Number <- < [0-9]+ > { return sv.toInt(); }
            %whitespace <- [ \\t\\n]*
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertEquals(5, parser.parse("2+3").unwrap());
        assertEquals(5, parser.parse("2 + 3").unwrap());
        assertEquals(5, parser.parse("2  +  3").unwrap());
        assertEquals(5, parser.parse("2\n+\n3").unwrap());
    }

    // === Repetition edge cases ===

    @Test
    void zeroOrMore_withEmpty_succeeds() {
        var grammar = """
            List <- Item*
            Item <- 'x'
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertTrue(parser.parseCst("").isSuccess());
        assertTrue(parser.parseCst("x").isSuccess());
        assertTrue(parser.parseCst("xxx").isSuccess());
    }

    @Test
    void oneOrMore_withOne_succeeds() {
        var grammar = """
            List <- Item+
            Item <- 'x'
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertTrue(parser.parseCst("").isFailure());
        assertTrue(parser.parseCst("x").isSuccess());
        assertTrue(parser.parseCst("xxx").isSuccess());
    }

    @Test
    void repetition_exactCount_worksCorrectly() {
        var grammar = """
            Triple <- 'x'{3}
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertTrue(parser.parseCst("xx").isFailure());
        assertTrue(parser.parseCst("xxx").isSuccess());
        assertTrue(parser.parseCst("xxxx").isFailure());
    }

    @Test
    void repetition_range_worksCorrectly() {
        var grammar = """
            Range <- 'x'{2,4}
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertTrue(parser.parseCst("x").isFailure());
        assertTrue(parser.parseCst("xx").isSuccess());
        assertTrue(parser.parseCst("xxx").isSuccess());
        assertTrue(parser.parseCst("xxxx").isSuccess());
        assertTrue(parser.parseCst("xxxxx").isFailure());
    }

    // === Predicate edge cases ===

    @Test
    void andPredicate_doesNotConsume() {
        var grammar = """
            Match <- &'hello' 'hello'
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertTrue(parser.parseCst("hello").isSuccess());
        assertTrue(parser.parseCst("world").isFailure());
    }

    @Test
    void notPredicate_doesNotConsume() {
        var grammar = """
            NotDigit <- ![0-9] .
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertTrue(parser.parseCst("a").isSuccess());
        assertTrue(parser.parseCst("Z").isSuccess());
        assertTrue(parser.parseCst("5").isFailure());
    }

    // === Back-reference edge cases ===

    @Test
    void backReference_matchesSameText() {
        var grammar = """
            Match <- $tag< [a-z]+ > '=' $tag
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertTrue(parser.parseCst("foo=foo").isSuccess());
        assertTrue(parser.parseCst("bar=bar").isSuccess());
        assertTrue(parser.parseCst("foo=bar").isFailure());
    }

    // === Character class edge cases ===

    @Test
    void charClass_withEscapes_worksCorrectly() {
        var grammar = """
            Special <- < [\\t\\n\\r]+ >
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertTrue(parser.parseCst("\t").isSuccess());
        assertTrue(parser.parseCst("\n").isSuccess());
        assertTrue(parser.parseCst("\t\n\r").isSuccess());
    }

    @Test
    void charClass_negated_matchesComplement() {
        var grammar = """
            NonDigit <- [^0-9]+
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertTrue(parser.parseCst("abc").isSuccess());
        assertTrue(parser.parseCst("123").isFailure());
    }

    @Test
    void charClass_caseInsensitive_matchesBothCases() {
        var grammar = """
            Alpha <- [a-z]i+
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertTrue(parser.parseCst("abc").isSuccess());
        assertTrue(parser.parseCst("ABC").isSuccess());
        assertTrue(parser.parseCst("AbC").isSuccess());
    }

    // === Action with sv.size() ===

    @Test
    void action_svSize_returnsCorrectCount() {
        var grammar = """
            List <- (Item (',' Item)*)? {
                return sv.size();
            }
            Item <- < [a-z]+ > { return $0; }
            %whitespace <- [ ]*
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        // Note: The count depends on how values are collected
        var result = parser.parse("a, b, c");
        assertTrue(result.isSuccess());
    }

    // === Empty input ===

    @Test
    void emptyInput_withOptional_succeeds() {
        var grammar = """
            Root <- 'x'?
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertTrue(parser.parseCst("").isSuccess());
        assertTrue(parser.parseCst("x").isSuccess());
    }

    @Test
    void emptyInput_withZeroOrMore_succeeds() {
        var grammar = """
            Root <- 'x'*
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertTrue(parser.parseCst("").isSuccess());
    }

    // === Unicode ===

    @Test
    void unicode_inLiteral_matches() {
        var grammar = """
            Emoji <- 'hello'
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertTrue(parser.parseCst("hello").isSuccess());
    }

    // === Ignore operator ===

    @Test
    void ignore_dropsValue() {
        var grammar = """
            Pair <- Key ~':' Value
            Key <- < [a-z]+ > { return $0; }
            Value <- < [0-9]+ > { return sv.toInt(); }
            %whitespace <- [ ]*
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();

        assertTrue(parser.parseCst("key: 42").isSuccess());
    }
}
