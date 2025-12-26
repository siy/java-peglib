package org.pragmatica.peg;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.tree.CstNode;

import static org.junit.jupiter.api.Assertions.*;

class PegParserTest {

    @Test
    void parse_simpleLiteral_succeeds() {
        var parser = PegParser.fromGrammar("Root <- 'hello'").unwrap();
        var result = parser.parseCst("hello");

        assertTrue(result.isSuccess());
        var node = result.unwrap();
        assertEquals("Root", node.rule());
    }

    @Test
    void parse_simpleLiteral_failsOnMismatch() {
        var parser = PegParser.fromGrammar("Root <- 'hello'").unwrap();
        var result = parser.parseCst("world");

        assertTrue(result.isFailure());
    }

    @Test
    void parse_characterClass_matchesRange() {
        var parser = PegParser.fromGrammar("Digit <- [0-9]").unwrap();

        assertTrue(parser.parseCst("5").isSuccess());
        assertTrue(parser.parseCst("0").isSuccess());
        assertTrue(parser.parseCst("9").isSuccess());
        assertTrue(parser.parseCst("a").isFailure());
    }

    @Test
    void parse_negatedCharClass_matchesComplement() {
        var parser = PegParser.fromGrammar("NonDigit <- [^0-9]").unwrap();

        assertTrue(parser.parseCst("a").isSuccess());
        assertTrue(parser.parseCst("Z").isSuccess());
        assertTrue(parser.parseCst("5").isFailure());
    }

    @Test
    void parse_anyChar_matchesSingle() {
        var parser = PegParser.fromGrammar("Any <- .").unwrap();

        assertTrue(parser.parseCst("a").isSuccess());
        assertTrue(parser.parseCst("1").isSuccess());
        assertTrue(parser.parseCst("").isFailure());
    }

    @Test
    void parse_sequence_matchesAll() {
        var parser = PegParser.fromGrammar("ABC <- 'a' 'b' 'c'").unwrap();

        assertTrue(parser.parseCst("abc").isSuccess());
        assertTrue(parser.parseCst("ab").isFailure());
        assertTrue(parser.parseCst("abd").isFailure());
    }

    @Test
    void parse_choice_matchesFirst() {
        var parser = PegParser.fromGrammar("Choice <- 'a' / 'b' / 'c'").unwrap();

        assertTrue(parser.parseCst("a").isSuccess());
        assertTrue(parser.parseCst("b").isSuccess());
        assertTrue(parser.parseCst("c").isSuccess());
        assertTrue(parser.parseCst("d").isFailure());
    }

    @Test
    void parse_zeroOrMore_matchesNone() {
        var parser = PegParser.fromGrammar("Stars <- 'a'*").unwrap();

        assertTrue(parser.parseCst("").isSuccess());
        assertTrue(parser.parseCst("a").isSuccess());
        assertTrue(parser.parseCst("aaa").isSuccess());
    }

    @Test
    void parse_oneOrMore_requiresAtLeastOne() {
        var parser = PegParser.fromGrammar("Plus <- 'a'+").unwrap();

        assertTrue(parser.parseCst("").isFailure());
        assertTrue(parser.parseCst("a").isSuccess());
        assertTrue(parser.parseCst("aaa").isSuccess());
    }

    @Test
    void parse_optional_matchesZeroOrOne() {
        var parser = PegParser.fromGrammar("Opt <- 'a'?").unwrap();

        assertTrue(parser.parseCst("").isSuccess());
        assertTrue(parser.parseCst("a").isSuccess());
    }

    @Test
    void parse_repetition_matchesExact() {
        var parser = PegParser.fromGrammar("Rep <- 'a'{3}").unwrap();

        assertTrue(parser.parseCst("aaa").isSuccess());
        assertTrue(parser.parseCst("aa").isFailure());
        assertTrue(parser.parseCst("aaaa").isFailure());
    }

    @Test
    void parse_repetition_matchesRange() {
        var parser = PegParser.fromGrammar("Rep <- 'a'{2,4}").unwrap();

        assertTrue(parser.parseCst("a").isFailure());
        assertTrue(parser.parseCst("aa").isSuccess());
        assertTrue(parser.parseCst("aaa").isSuccess());
        assertTrue(parser.parseCst("aaaa").isSuccess());
        assertTrue(parser.parseCst("aaaaa").isFailure());
    }

    @Test
    void parse_andPredicate_doesNotConsume() {
        var parser = PegParser.fromGrammar("And <- &'a' 'a'").unwrap();

        assertTrue(parser.parseCst("a").isSuccess());
        assertTrue(parser.parseCst("b").isFailure());
    }

    @Test
    void parse_notPredicate_negates() {
        var parser = PegParser.fromGrammar("Not <- !'a' .").unwrap();

        assertTrue(parser.parseCst("b").isSuccess());
        assertTrue(parser.parseCst("a").isFailure());
    }

    @Test
    void parse_tokenBoundary_capturesText() {
        var parser = PegParser.fromGrammar("Number <- < [0-9]+ >").unwrap();
        var result = parser.parseCst("123");

        assertTrue(result.isSuccess());
        var node = result.unwrap();
        assertInstanceOf(CstNode.Token.class, node);
        assertEquals("123", ((CstNode.Token) node).text());
    }

    @Test
    void parse_ruleReference_works() {
        var parser = PegParser.fromGrammar("""
            Root <- Digit Digit Digit
            Digit <- [0-9]
            """).unwrap();

        assertTrue(parser.parseCst("123").isSuccess());
        assertTrue(parser.parseCst("12").isFailure());
    }

    @Test
    void parse_withWhitespace_skipsSpaces() {
        var parser = PegParser.fromGrammar("""
            Expr <- 'a' 'b' 'c'
            %whitespace <- [ \\t]*
            """).unwrap();

        assertTrue(parser.parseCst("abc").isSuccess());
        assertTrue(parser.parseCst("a b c").isSuccess());
        assertTrue(parser.parseCst("a  b  c").isSuccess());
    }

    @Test
    void parse_caseInsensitive_matchesBothCases() {
        var parser = PegParser.fromGrammar("Kw <- 'select'i").unwrap();

        assertTrue(parser.parseCst("select").isSuccess());
        assertTrue(parser.parseCst("SELECT").isSuccess());
        assertTrue(parser.parseCst("SeLeCt").isSuccess());
    }

    @Test
    void parse_calculator_works() {
        var parser = PegParser.fromGrammar("""
            Expr    <- Term (('+' / '-') Term)*
            Term    <- Factor (('*' / '/') Factor)*
            Factor  <- '(' Expr ')' / Number
            Number  <- < [0-9]+ >
            %whitespace <- [ \\t]*
            """).unwrap();

        assertTrue(parser.parseCst("1").isSuccess());
        assertTrue(parser.parseCst("1+2").isSuccess());
        assertTrue(parser.parseCst("1 + 2").isSuccess());
        assertTrue(parser.parseCst("1 + 2 * 3").isSuccess());
        assertTrue(parser.parseCst("(1 + 2) * 3").isSuccess());
    }

    @Test
    void parse_namedCapture_andBackref() {
        var parser = PegParser.fromGrammar("""
            Match <- $tag< [a-z]+ > '=' $tag
            """).unwrap();

        assertTrue(parser.parseCst("foo=foo").isSuccess());
        assertTrue(parser.parseCst("bar=bar").isSuccess());
        assertTrue(parser.parseCst("foo=bar").isFailure());
    }

    @Test
    void backReference_matchesXmlTags() {
        var parser = PegParser.fromGrammar("""
            Element <- '<' $tag< [a-z]+ > '>' Content '</' $tag '>'
            Content <- < [^<]* >
            %whitespace <- [ ]*
            """).unwrap();

        // Matching tags should succeed
        assertTrue(parser.parseCst("<div>hello</div>").isSuccess());
        assertTrue(parser.parseCst("<span>world</span>").isSuccess());
        assertTrue(parser.parseCst("<p></p>").isSuccess());

        // Mismatched tags should fail
        assertTrue(parser.parseCst("<div>hello</span>").isFailure());
        assertTrue(parser.parseCst("<b>text</i>").isFailure());
    }

    @Test
    void hexEscape_inLiteral() {
        // \x20 is space, \x41 is 'A'
        var parser = PegParser.fromGrammar("Match <- '\\x41\\x42\\x43'").unwrap();
        assertTrue(parser.parseCst("ABC").isSuccess());
        assertTrue(parser.parseCst("abc").isFailure());
    }

    @Test
    void hexEscape_inCharClass() {
        // \x30\x31\x32 are 0, 1, 2 - test without range first
        var parser = PegParser.fromGrammar("Digit <- [\\x30\\x31\\x32]+").unwrap();
        assertTrue(parser.parseCst("012").isSuccess());
        assertTrue(parser.parseCst("abc").isFailure());
    }

    @Test
    void unicodeEscape_inLiteral() {
        // \u0041 is 'A'
        var parser = PegParser.fromGrammar("Match <- '\\u0041'").unwrap();
        assertTrue(parser.parseCst("A").isSuccess());
    }

    @Test
    void unicodeEscape_inCharClass() {
        // Test single unicode chars without range
        var parser = PegParser.fromGrammar("Alpha <- [\\u03B1\\u03B2\\u03B3]+").unwrap();
        assertTrue(parser.parseCst("\u03B1\u03B2\u03B3").isSuccess());  // αβγ
        assertTrue(parser.parseCst("abc").isFailure());
    }

    @Test
    void parseAst_convertsFromCst() {
        var parser = PegParser.fromGrammar("Number <- < [0-9]+ >").unwrap();
        var result = parser.parseAst("42");

        assertTrue(result.isSuccess());
        var node = result.unwrap();
        assertEquals("Number", node.rule());
    }

    @Test
    void builder_allowsConfiguration() {
        var result = PegParser.builder("Root <- 'test'")
            .packrat(true)
            .build();

        assertTrue(result.isSuccess());
        assertTrue(result.unwrap().parseCst("test").isSuccess());
    }

    // === Action Tests ===

    @Test
    void parse_simpleAction_returnsInteger() {
        var parser = PegParser.fromGrammar("""
            Number <- < [0-9]+ > { return sv.toInt(); }
            """).unwrap();

        var result = parser.parse("42");
        assertTrue(result.isSuccess());
        assertEquals(42, result.unwrap());
    }

    @Test
    void parse_actionWithChildValues_computesSum() {
        var parser = PegParser.fromGrammar("""
            Sum <- Number '+' Number { return (Integer)$1 + (Integer)$2; }
            Number <- < [0-9]+ > { return sv.toInt(); }
            %whitespace <- [ ]*
            """).unwrap();

        var result = parser.parse("3 + 5");
        assertTrue(result.isSuccess());
        assertEquals(8, result.unwrap());
    }

    @Test
    void parse_actionWithToken_returnsMatchedText() {
        var parser = PegParser.fromGrammar("""
            Word <- < [a-z]+ > { return $0.toUpperCase(); }
            """).unwrap();

        var result = parser.parse("hello");
        assertTrue(result.isSuccess());
        assertEquals("HELLO", result.unwrap());
    }

    @Test
    void parse_calculatorWithActions_evaluates() {
        // Simplified - just test basic arithmetic with actions
        var parser = PegParser.fromGrammar("""
            Sum <- Number '+' Number { return (Integer)$1 + (Integer)$2; }
            Number <- < [0-9]+ > { return sv.toInt(); }
            %whitespace <- [ ]*
            """).unwrap();

        assertEquals(8, parser.parse("3 + 5").unwrap());
    }

    @Test
    void parse_actionBuildsList() {
        var parser = PegParser.fromGrammar("""
            List <- Item (',' Item)* {
                var list = new java.util.ArrayList<String>();
                for (int i = 0; i < sv.size(); i++) {
                    list.add((String)sv.get(i));
                }
                return list;
            }
            Item <- < [a-z]+ > { return $0; }
            %whitespace <- [ ]*
            """).unwrap();

        var result = parser.parse("a, b, c");
        assertTrue(result.isSuccess());
        var list = (java.util.List<?>) result.unwrap();
        assertEquals(3, list.size());
        assertEquals("a", list.get(0));
        assertEquals("b", list.get(1));
        assertEquals("c", list.get(2));
    }

    @Test
    void parse_noAction_returnsCstNode() {
        var parser = PegParser.fromGrammar("Root <- 'hello'").unwrap();
        var result = parser.parse("hello");

        assertTrue(result.isSuccess());
        assertInstanceOf(CstNode.class, result.unwrap());
    }

    // === Custom Error Message Tests ===

    @Test
    void customErrorMessage_usedOnFailure() {
        var parser = PegParser.fromGrammar("""
            Statement <- Semicolon
            Semicolon <- ';' { error_message "expected semicolon" }
            """).unwrap();

        var result = parser.parseCst("x");
        assertTrue(result.isFailure());
        var error = result.fold(err -> err.message(), _ -> "");
        assertTrue(error.contains("expected semicolon"), "Error should contain custom message: " + error);
    }

    @Test
    void customErrorMessage_successNotAffected() {
        var parser = PegParser.fromGrammar("""
            Semicolon <- ';' { error_message "expected semicolon" }
            """).unwrap();

        var result = parser.parseCst(";");
        assertTrue(result.isSuccess());
    }

    @Test
    void customErrorMessage_withAction() {
        var parser = PegParser.fromGrammar("""
            Number <- < [0-9]+ > { return sv.toInt(); } { error_message "expected number" }
            """).unwrap();

        // Success case - action works
        var success = parser.parse("42");
        assertTrue(success.isSuccess());
        assertEquals(42, success.unwrap());

        // Failure case - custom error message
        var failure = parser.parse("abc");
        assertTrue(failure.isFailure());
        var errorMsg = failure.fold(err -> err.message(), _ -> "");
        assertTrue(errorMsg.contains("expected number"), "Error should contain custom message: " + errorMsg);
    }

    // === Capture Scope Tests ===

    @Test
    void captureScope_isolatesCaptures() {
        // Without capture scope, the second $tag would fail because 'tag' captures different value
        // With capture scope, each element gets its own isolated capture
        var parser = PegParser.fromGrammar("""
            Doc <- Element Element
            Element <- $('<' $tag< [a-z]+ > '>' Content '</' $tag '>')
            Content <- < [^<]* >
            %whitespace <- [ ]*
            """).unwrap();

        // Both elements use same capture name, but are isolated
        assertTrue(parser.parseCst("<div>hello</div><span>world</span>").isSuccess());
    }

    @Test
    void captureScope_doesNotLeakOutward() {
        var parser = PegParser.fromGrammar("""
            Root <- $( $name< [a-z]+ > '=' $name ) $name
            """).unwrap();

        // After capture scope, $name should not be defined
        assertTrue(parser.parseCst("foo=foo bar").isFailure());
    }

    @Test
    void captureScope_innerBackrefWorks() {
        var parser = PegParser.fromGrammar("""
            Match <- $( $tag< [a-z]+ > '-' $tag )
            """).unwrap();

        // Inside scope, backreference works
        assertTrue(parser.parseCst("abc-abc").isSuccess());
        assertTrue(parser.parseCst("abc-xyz").isFailure());
    }

    // === Dictionary Tests ===

    @Test
    void dictionary_matchesAnyWord() {
        var parser = PegParser.fromGrammar("""
            Keyword <- 'if' | 'else' | 'while' | 'for'
            """).unwrap();

        assertTrue(parser.parseCst("if").isSuccess());
        assertTrue(parser.parseCst("else").isSuccess());
        assertTrue(parser.parseCst("while").isSuccess());
        assertTrue(parser.parseCst("for").isSuccess());
        assertTrue(parser.parseCst("then").isFailure());
    }

    @Test
    void dictionary_longestMatch() {
        var parser = PegParser.fromGrammar("""
            Op <- '+' | '++' | '+='
            """).unwrap();

        // Should match longest: '++'
        var result = parser.parseCst("++");
        assertTrue(result.isSuccess());
        assertEquals("++", ((CstNode.Terminal) result.unwrap()).text());

        // Should match longest: '+='
        var result2 = parser.parseCst("+=");
        assertTrue(result2.isSuccess());
        assertEquals("+=", ((CstNode.Terminal) result2.unwrap()).text());
    }

    @Test
    void dictionary_caseInsensitive() {
        var parser = PegParser.fromGrammar("""
            Keyword <- 'SELECT'i | 'FROM'i | 'WHERE'i
            """).unwrap();

        assertTrue(parser.parseCst("select").isSuccess());
        assertTrue(parser.parseCst("SELECT").isSuccess());
        assertTrue(parser.parseCst("SeLeCt").isSuccess());
        assertTrue(parser.parseCst("from").isSuccess());
        assertTrue(parser.parseCst("where").isSuccess());
    }

    @Test
    void dictionary_inSequence() {
        var parser = PegParser.fromGrammar("""
            Statement <- ('if' | 'while') Identifier
            Identifier <- < [a-z]+ >
            %whitespace <- [ ]*
            """).unwrap();

        assertTrue(parser.parseCst("if condition").isSuccess());
        assertTrue(parser.parseCst("while running").isSuccess());
        assertTrue(parser.parseCst("for loop").isFailure());
    }

    // === Cut Operator Tests ===

    @Test
    void cutOperator_caret_parsed() {
        var parser = PegParser.fromGrammar("Root <- 'a' ^ 'b'").unwrap();
        assertTrue(parser.parseCst("ab").isSuccess());
    }

    @Test
    void cutOperator_upArrow_parsed() {
        var parser = PegParser.fromGrammar("Root <- 'a' ↑ 'b'").unwrap();
        assertTrue(parser.parseCst("ab").isSuccess());
    }

    @Test
    void cutOperator_preventsBacktracking() {
        // Without cut, 'ax' would fail first alternative and try second
        // With cut after 'a', parser commits to first alternative
        var parser = PegParser.fromGrammar("""
            Root <- ('a' ^ 'b') / 'a'
            """).unwrap();

        // 'ab' succeeds - matches first alternative
        assertTrue(parser.parseCst("ab").isSuccess());

        // 'a' fails - cut prevents trying second alternative
        // Even though 'a' would match second alternative
        var result = parser.parseCst("a");
        assertTrue(result.isFailure());
    }

    @Test
    void cutOperator_errorPositionAfterCut() {
        var parser = PegParser.fromGrammar("""
            Root <- ('a' ^ 'b') / 'c'
            """).unwrap();

        // Input 'ax' - matches 'a', cut commits, 'b' fails
        // Error should indicate expected 'b', not 'c'
        var result = parser.parseCst("ax");
        assertTrue(result.isFailure());
        var error = result.fold(err -> err.message(), _ -> "");
        assertTrue(error.contains("'b'") || error.contains("x"),
            "Error should indicate failure after cut: " + error);
    }

    @Test
    void cutOperator_noEffectOnSuccessfulParse() {
        var parser = PegParser.fromGrammar("""
            Root <- 'a' ^ 'b' 'c'
            """).unwrap();

        assertTrue(parser.parseCst("abc").isSuccess());
    }

    @Test
    void cutOperator_worksInNestedChoice() {
        var parser = PegParser.fromGrammar("""
            Root <- Outer
            Outer <- Inner / 'fallback'
            Inner <- 'start' ^ 'middle' 'end'
            """).unwrap();

        // Successful parse
        assertTrue(parser.parseCst("startmiddleend").isSuccess());

        // After cut, don't try 'fallback' alternative
        var result = parser.parseCst("startfail");
        assertTrue(result.isFailure());
    }

    @Test
    void cutOperator_multipleInSequence() {
        var parser = PegParser.fromGrammar("""
            Root <- ('a' ^ 'b' ^ 'c') / 'x'
            """).unwrap();

        assertTrue(parser.parseCst("abc").isSuccess());

        // First cut commits, second cut commits further
        var result = parser.parseCst("abx");
        assertTrue(result.isFailure());
    }
}
