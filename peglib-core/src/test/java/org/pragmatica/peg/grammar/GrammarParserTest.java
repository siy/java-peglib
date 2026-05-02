package org.pragmatica.peg.grammar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GrammarParserTest {

    @Test
    void parse_simpleRule_succeeds() {
        GrammarParser.parse("Number <- [0-9]+")
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         assertEquals(1, grammar.rules().size());
                         var rule = grammar.rules().getFirst();
                         assertEquals("Number", rule.name());
                         assertInstanceOf(Expression.OneOrMore.class, rule.expression());
                     });
    }

    @Test
    void parse_ruleWithAction_capturesAction() {
        GrammarParser.parse("""
                                Number <- < [0-9]+ > { return Integer.parseInt($0); }
                                """)
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         var rule = grammar.rules().getFirst();
                         assertTrue(rule.hasAction());
                         assertEquals("return Integer.parseInt($0);", rule.action().unwrap());
                     });
    }

    @Test
    void parse_sequence_createsSequence() {
        GrammarParser.parse("Expr <- 'a' 'b' 'c'")
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         var expr = grammar.rules().getFirst().expression();
                         assertInstanceOf(Expression.Sequence.class, expr);
                         var seq = (Expression.Sequence) expr;
                         assertEquals(3, seq.elements().size());
                     });
    }

    @Test
    void parse_choice_createsChoice() {
        GrammarParser.parse("Expr <- 'a' / 'b' / 'c'")
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         var expr = grammar.rules().getFirst().expression();
                         assertInstanceOf(Expression.Choice.class, expr);
                         var choice = (Expression.Choice) expr;
                         assertEquals(3, choice.alternatives().size());
                     });
    }

    @Test
    void parse_lookahead_createsAndNot() {
        GrammarParser.parse("""
                                Positive <- &'x'
                                Negative <- !'y'
                                """)
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         var rules = grammar.rules();
                         assertInstanceOf(Expression.And.class, rules.get(0).expression());
                         assertInstanceOf(Expression.Not.class, rules.get(1).expression());
                     });
    }

    @Test
    void parse_repetition_createsSuffixes() {
        GrammarParser.parse("""
                                ZeroMore <- 'a'*
                                OneMore <- 'b'+
                                Optional <- 'c'?
                                """)
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         var rules = grammar.rules();
                         assertInstanceOf(Expression.ZeroOrMore.class, rules.get(0).expression());
                         assertInstanceOf(Expression.OneOrMore.class, rules.get(1).expression());
                         assertInstanceOf(Expression.Optional.class, rules.get(2).expression());
                     });
    }

    @Test
    void parse_tokenBoundary_createsTokenBoundary() {
        GrammarParser.parse("Token <- < [a-z]+ >")
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         var expr = grammar.rules().getFirst().expression();
                         assertInstanceOf(Expression.TokenBoundary.class, expr);
                     });
    }

    @Test
    void parse_whitespaceDirective_setsWhitespace() {
        GrammarParser.parse("""
                                Number <- [0-9]+
                                %whitespace <- [ \\t]*
                                """)
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> assertTrue(grammar.whitespace().isPresent()));
    }

    @Test
    void parse_multipleRules_allParsed() {
        GrammarParser.parse("""
                                Additive    <- Multiplicative ('+' Multiplicative)*
                                Multiplicative <- Primary ('*' Primary)*
                                Primary     <- '(' Additive ')' / Number
                                Number      <- < [0-9]+ >
                                """)
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         assertEquals(4, grammar.rules().size());
                         assertEquals("Additive", grammar.effectiveStartRule().unwrap().name());
                     });
    }

    @Test
    void parse_caseInsensitive_setsFlag() {
        GrammarParser.parse("Keyword <- 'select'i")
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         var expr = grammar.rules().getFirst().expression();
                         assertInstanceOf(Expression.Literal.class, expr);
                         assertTrue(((Expression.Literal) expr).caseInsensitive());
                     });
    }

    @Test
    void parse_namedCapture_createsCapture() {
        GrammarParser.parse("Match <- $name< [a-z]+ >")
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         var expr = grammar.rules().getFirst().expression();
                         assertInstanceOf(Expression.Capture.class, expr);
                         assertEquals("name", ((Expression.Capture) expr).name());
                     });
    }

    @Test
    void parse_backReference_createsBackReference() {
        GrammarParser.parse("Match <- $name< [a-z]+ > '=' $name")
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         var expr = grammar.rules().getFirst().expression();
                         assertInstanceOf(Expression.Sequence.class, expr);
                         var seq = (Expression.Sequence) expr;
                         assertInstanceOf(Expression.BackReference.class, seq.elements().get(2));
                     });
    }

    @Test
    void parse_boundedRepetition_createsRepetition() {
        GrammarParser.parse("""
                                Exact <- 'a'{3}
                                AtLeast <- 'b'{2,}
                                Range <- 'c'{1,5}
                                """)
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         var rules = grammar.rules();
                         var exact = (Expression.Repetition) rules.get(0).expression();
                         assertEquals(3, exact.min());
                         assertEquals(3, exact.max().unwrap());

                         var atLeast = (Expression.Repetition) rules.get(1).expression();
                         assertEquals(2, atLeast.min());
                         assertTrue(atLeast.max().isEmpty());

                         var range = (Expression.Repetition) rules.get(2).expression();
                         assertEquals(1, range.min());
                         assertEquals(5, range.max().unwrap());
                     });
    }

    @Test
    void parse_invalidSyntax_returnsFailure() {
        var result = GrammarParser.parse("BadRule <- <- syntax");

        assertTrue(result.isFailure());
    }

    @Test
    void validate_undefinedReference_returnsFailure() {
        // 0.4.0 — Grammar.grammar(...) factory now validates at construction;
        // GrammarParser.parse(...) returns the same Result the factory would.
        var result = GrammarParser.parse("Start <- 'a' UndefinedRule 'b'");

        assertTrue(result.isFailure());
        var message = result.fold(cause -> cause.message(), g -> "");
        assertTrue(message.contains("Undefined rule reference: 'UndefinedRule'"), message);
    }

    @Test
    void validate_validGrammar_succeeds() {
        var result = GrammarParser.parse("""
            Start <- 'a' Middle 'b'
            Middle <- [0-9]+
            """);

        assertTrue(result.isSuccess());
    }

    @Test
    void validate_nestedUndefinedReference_returnsFailure() {
        var result = GrammarParser.parse("""
            Start <- (Foo / Bar)*
            Foo <- 'foo'
            """);

        assertTrue(result.isFailure());
        var message = result.fold(cause -> cause.message(), g -> "");
        assertTrue(message.contains("Undefined rule reference: 'Bar'"), message);
    }
}
