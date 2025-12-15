package org.pragmatica.peg.grammar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GrammarParserTest {

    @Test
    void parse_simpleRule_succeeds() {
        var result = GrammarParser.parse("Number <- [0-9]+");

        assertTrue(result.isSuccess());
        var grammar = result.unwrap();
        assertEquals(1, grammar.rules().size());

        var rule = grammar.rules().getFirst();
        assertEquals("Number", rule.name());
        assertInstanceOf(Expression.OneOrMore.class, rule.expression());
    }

    @Test
    void parse_ruleWithAction_capturesAction() {
        var result = GrammarParser.parse("""
            Number <- < [0-9]+ > { return Integer.parseInt($0); }
            """);

        assertTrue(result.isSuccess());
        var rule = result.unwrap().rules().getFirst();
        assertTrue(rule.hasAction());
        assertEquals("return Integer.parseInt($0);", rule.action().unwrap());
    }

    @Test
    void parse_sequence_createsSequence() {
        var result = GrammarParser.parse("Expr <- 'a' 'b' 'c'");

        assertTrue(result.isSuccess());
        var expr = result.unwrap().rules().getFirst().expression();
        assertInstanceOf(Expression.Sequence.class, expr);

        var seq = (Expression.Sequence) expr;
        assertEquals(3, seq.elements().size());
    }

    @Test
    void parse_choice_createsChoice() {
        var result = GrammarParser.parse("Expr <- 'a' / 'b' / 'c'");

        assertTrue(result.isSuccess());
        var expr = result.unwrap().rules().getFirst().expression();
        assertInstanceOf(Expression.Choice.class, expr);

        var choice = (Expression.Choice) expr;
        assertEquals(3, choice.alternatives().size());
    }

    @Test
    void parse_lookahead_createsAndNot() {
        var result = GrammarParser.parse("""
            Positive <- &'x'
            Negative <- !'y'
            """);

        assertTrue(result.isSuccess());
        var rules = result.unwrap().rules();
        assertInstanceOf(Expression.And.class, rules.get(0).expression());
        assertInstanceOf(Expression.Not.class, rules.get(1).expression());
    }

    @Test
    void parse_repetition_createsSuffixes() {
        var result = GrammarParser.parse("""
            ZeroMore <- 'a'*
            OneMore <- 'b'+
            Optional <- 'c'?
            """);

        assertTrue(result.isSuccess());
        var rules = result.unwrap().rules();
        assertInstanceOf(Expression.ZeroOrMore.class, rules.get(0).expression());
        assertInstanceOf(Expression.OneOrMore.class, rules.get(1).expression());
        assertInstanceOf(Expression.Optional.class, rules.get(2).expression());
    }

    @Test
    void parse_tokenBoundary_createsTokenBoundary() {
        var result = GrammarParser.parse("Token <- < [a-z]+ >");

        assertTrue(result.isSuccess());
        var expr = result.unwrap().rules().getFirst().expression();
        assertInstanceOf(Expression.TokenBoundary.class, expr);
    }

    @Test
    void parse_whitespaceDirective_setsWhitespace() {
        var result = GrammarParser.parse("""
            Number <- [0-9]+
            %whitespace <- [ \\t]*
            """);

        assertTrue(result.isSuccess());
        var grammar = result.unwrap();
        assertTrue(grammar.whitespace().isPresent());
    }

    @Test
    void parse_multipleRules_allParsed() {
        var result = GrammarParser.parse("""
            Additive    <- Multiplicative ('+' Multiplicative)*
            Multiplicative <- Primary ('*' Primary)*
            Primary     <- '(' Additive ')' / Number
            Number      <- < [0-9]+ >
            """);

        assertTrue(result.isSuccess());
        var grammar = result.unwrap();
        assertEquals(4, grammar.rules().size());
        assertEquals("Additive", grammar.effectiveStartRule().unwrap().name());
    }

    @Test
    void parse_caseInsensitive_setsFlag() {
        var result = GrammarParser.parse("Keyword <- 'select'i");

        assertTrue(result.isSuccess());
        var expr = result.unwrap().rules().getFirst().expression();
        assertInstanceOf(Expression.Literal.class, expr);
        assertTrue(((Expression.Literal) expr).caseInsensitive());
    }

    @Test
    void parse_namedCapture_createsCapture() {
        var result = GrammarParser.parse("Match <- $name< [a-z]+ >");

        assertTrue(result.isSuccess());
        var expr = result.unwrap().rules().getFirst().expression();
        assertInstanceOf(Expression.Capture.class, expr);
        assertEquals("name", ((Expression.Capture) expr).name());
    }

    @Test
    void parse_backReference_createsBackReference() {
        var result = GrammarParser.parse("Match <- $name< [a-z]+ > '=' $name");

        assertTrue(result.isSuccess());
        var expr = result.unwrap().rules().getFirst().expression();
        assertInstanceOf(Expression.Sequence.class, expr);

        var seq = (Expression.Sequence) expr;
        assertInstanceOf(Expression.BackReference.class, seq.elements().get(2));
    }

    @Test
    void parse_boundedRepetition_createsRepetition() {
        var result = GrammarParser.parse("""
            Exact <- 'a'{3}
            AtLeast <- 'b'{2,}
            Range <- 'c'{1,5}
            """);

        assertTrue(result.isSuccess());
        var rules = result.unwrap().rules();

        var exact = (Expression.Repetition) rules.get(0).expression();
        assertEquals(3, exact.min());
        assertEquals(3, exact.max().unwrap());

        var atLeast = (Expression.Repetition) rules.get(1).expression();
        assertEquals(2, atLeast.min());
        assertTrue(atLeast.max().isEmpty());

        var range = (Expression.Repetition) rules.get(2).expression();
        assertEquals(1, range.min());
        assertEquals(5, range.max().unwrap());
    }

    @Test
    void parse_invalidSyntax_returnsFailure() {
        var result = GrammarParser.parse("BadRule <- <- syntax");

        assertTrue(result.isFailure());
    }
}
