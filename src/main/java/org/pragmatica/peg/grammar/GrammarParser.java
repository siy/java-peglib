package org.pragmatica.peg.grammar;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.error.ParseError;
import org.pragmatica.peg.tree.SourceLocation;
import org.pragmatica.peg.tree.SourceSpan;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for PEG grammar syntax.
 * Converts grammar text into Grammar object.
 */
public final class GrammarParser {

    private final List<GrammarToken> tokens;
    private int pos;

    private GrammarParser(List<GrammarToken> tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    /**
     * Parse grammar text into Grammar object.
     */
    public static Result<Grammar> parse(String grammarText) {
        var tokens = GrammarLexer.tokenize(grammarText);

        // Check for lexer errors
        for (var token : tokens) {
            if (token instanceof GrammarToken.Error error) {
                return Result.failure(new ParseError.SemanticError(
                    error.span().start(),
                    error.message()
                ));
            }
        }

        return new GrammarParser(tokens).parseGrammar();
    }

    private Result<Grammar> parseGrammar() {
        var rules = new ArrayList<Rule>();
        Option<String> startRule = Option.none();
        Option<Expression> whitespace = Option.none();
        Option<Expression> word = Option.none();

        while (!isAtEnd()) {
            var token = peek();

            if (token instanceof GrammarToken.Directive directive) {
                advance();
                var result = parseDirective(directive);
                if (result.isFailure()) {
                    return result.fold(Result::failure, _ -> null);
                }
                var expr = result.unwrap();
                switch (directive.name()) {
                    case "whitespace" -> whitespace = Option.some(expr);
                    case "word" -> word = Option.some(expr);
                }
            } else if (token instanceof GrammarToken.Identifier) {
                var result = parseRule();
                if (result.isFailure()) {
                    return result.fold(Result::failure, _ -> null);
                }
                rules.add(result.unwrap());
            } else if (token instanceof GrammarToken.Eof) {
                break;
            } else {
                return Result.failure(new ParseError.UnexpectedInput(
                    token.span().start(),
                    tokenDescription(token),
                    "rule definition or directive"
                ));
            }
        }

        return Result.success(new Grammar(rules, startRule, whitespace, word));
    }

    private Result<Expression> parseDirective(GrammarToken.Directive directive) {
        if (!expect(GrammarToken.LeftArrow.class)) {
            return Result.failure(new ParseError.UnexpectedInput(
                peek().span().start(),
                tokenDescription(peek()),
                "'<-'"
            ));
        }
        return parseExpression();
    }

    private Result<Rule> parseRule() {
        var start = peek().span().start();

        if (!(peek() instanceof GrammarToken.Identifier id)) {
            return Result.failure(new ParseError.UnexpectedInput(
                peek().span().start(),
                tokenDescription(peek()),
                "rule name"
            ));
        }
        advance();

        if (!expect(GrammarToken.LeftArrow.class)) {
            return Result.failure(new ParseError.UnexpectedInput(
                peek().span().start(),
                tokenDescription(peek()),
                "'<-'"
            ));
        }

        var exprResult = parseExpression();
        if (exprResult.isFailure()) {
            return exprResult.fold(Result::failure, _ -> null);
        }
        var expression = exprResult.unwrap();

        // Check for action
        Option<String> action = Option.none();
        if (peek() instanceof GrammarToken.ActionCode actionCode) {
            advance();
            action = Option.some(actionCode.code());
        }

        var span = SourceSpan.of(start, currentLocation());
        return Result.success(new Rule(span, id.name(), expression, action));
    }

    private Result<Expression> parseExpression() {
        return parseChoice();
    }

    private Result<Expression> parseChoice() {
        var start = peek().span().start();
        var alternatives = new ArrayList<Expression>();

        var first = parseSequence();
        if (first.isFailure()) {
            return first;
        }
        alternatives.add(first.unwrap());

        while (peek() instanceof GrammarToken.Slash) {
            advance();
            var next = parseSequence();
            if (next.isFailure()) {
                return next;
            }
            alternatives.add(next.unwrap());
        }

        if (alternatives.size() == 1) {
            return Result.success(alternatives.getFirst());
        }
        var span = SourceSpan.of(start, currentLocation());
        return Result.success(new Expression.Choice(span, alternatives));
    }

    private Result<Expression> parseSequence() {
        var start = peek().span().start();
        var elements = new ArrayList<Expression>();

        while (isSequenceElement()) {
            var result = parsePrefix();
            if (result.isFailure()) {
                return result;
            }
            elements.add(result.unwrap());
        }

        if (elements.isEmpty()) {
            return Result.failure(new ParseError.UnexpectedInput(
                peek().span().start(),
                tokenDescription(peek()),
                "expression"
            ));
        }

        if (elements.size() == 1) {
            return Result.success(elements.getFirst());
        }
        var span = SourceSpan.of(start, currentLocation());
        return Result.success(new Expression.Sequence(span, elements));
    }

    private boolean isSequenceElement() {
        var token = peek();
        // Identifier followed by <- is a new rule definition, not a reference
        if (token instanceof GrammarToken.Identifier) {
            return !isRuleDefinitionStart();
        }
        return token instanceof GrammarToken.StringLiteral
            || token instanceof GrammarToken.CharClassLiteral
            || token instanceof GrammarToken.Dot
            || token instanceof GrammarToken.LParen
            || token instanceof GrammarToken.LAngle
            || token instanceof GrammarToken.Ampersand
            || token instanceof GrammarToken.Exclamation
            || token instanceof GrammarToken.Tilde
            || token instanceof GrammarToken.Dollar
            || token instanceof GrammarToken.Cut;
    }

    private boolean isRuleDefinitionStart() {
        // Check if current Identifier is followed by <-
        if (pos + 1 < tokens.size()) {
            return tokens.get(pos + 1) instanceof GrammarToken.LeftArrow;
        }
        return false;
    }

    private Result<Expression> parsePrefix() {
        var start = peek().span().start();

        if (peek() instanceof GrammarToken.Ampersand) {
            advance();
            var inner = parseSuffix();
            if (inner.isFailure()) return inner;
            var span = SourceSpan.of(start, currentLocation());
            return Result.success(new Expression.And(span, inner.unwrap()));
        }

        if (peek() instanceof GrammarToken.Exclamation) {
            advance();
            var inner = parseSuffix();
            if (inner.isFailure()) return inner;
            var span = SourceSpan.of(start, currentLocation());
            return Result.success(new Expression.Not(span, inner.unwrap()));
        }

        if (peek() instanceof GrammarToken.Tilde) {
            advance();
            var inner = parseSuffix();
            if (inner.isFailure()) return inner;
            var span = SourceSpan.of(start, currentLocation());
            return Result.success(new Expression.Ignore(span, inner.unwrap()));
        }

        return parseSuffix();
    }

    private Result<Expression> parseSuffix() {
        var start = peek().span().start();
        var result = parsePrimary();
        if (result.isFailure()) {
            return result;
        }
        var expr = result.unwrap();

        while (true) {
            if (peek() instanceof GrammarToken.Star) {
                advance();
                var span = SourceSpan.of(start, currentLocation());
                expr = new Expression.ZeroOrMore(span, expr);
            } else if (peek() instanceof GrammarToken.Plus) {
                advance();
                var span = SourceSpan.of(start, currentLocation());
                expr = new Expression.OneOrMore(span, expr);
            } else if (peek() instanceof GrammarToken.Question) {
                advance();
                var span = SourceSpan.of(start, currentLocation());
                expr = new Expression.Optional(span, expr);
            } else if (peek() instanceof GrammarToken.LBrace) {
                var repResult = parseRepetition(start, expr);
                if (repResult.isFailure()) return repResult;
                expr = repResult.unwrap();
            } else {
                break;
            }
        }

        return Result.success(expr);
    }

    private Result<Expression> parseRepetition(SourceLocation start, Expression expr) {
        advance(); // skip {
        if (!(peek() instanceof GrammarToken.Number min)) {
            return Result.failure(new ParseError.UnexpectedInput(
                peek().span().start(),
                tokenDescription(peek()),
                "number"
            ));
        }
        advance();

        Option<Integer> max;
        if (peek() instanceof GrammarToken.Comma) {
            advance();
            if (peek() instanceof GrammarToken.Number maxNum) {
                advance();
                max = Option.some(maxNum.value());
            } else {
                max = Option.none(); // unbounded: {n,}
            }
        } else {
            max = Option.some(min.value()); // exact: {n}
        }

        if (!(peek() instanceof GrammarToken.RBrace)) {
            return Result.failure(new ParseError.UnexpectedInput(
                peek().span().start(),
                tokenDescription(peek()),
                "'}'"
            ));
        }
        advance();

        var span = SourceSpan.of(start, currentLocation());
        return Result.success(new Expression.Repetition(span, expr, min.value(), max));
    }

    private Result<Expression> parsePrimary() {
        var token = peek();
        var start = token.span().start();

        // Identifier (rule reference)
        if (token instanceof GrammarToken.Identifier id) {
            advance();
            return Result.success(new Expression.Reference(token.span(), id.name()));
        }

        // String literal
        if (token instanceof GrammarToken.StringLiteral str) {
            advance();
            return Result.success(new Expression.Literal(token.span(), str.value(), str.caseInsensitive()));
        }

        // Character class
        if (token instanceof GrammarToken.CharClassLiteral cc) {
            advance();
            return Result.success(new Expression.CharClass(token.span(), cc.pattern(), cc.negated(), cc.caseInsensitive()));
        }

        // Any character
        if (token instanceof GrammarToken.Dot) {
            advance();
            return Result.success(new Expression.Any(token.span()));
        }

        // Cut
        if (token instanceof GrammarToken.Cut) {
            advance();
            return Result.success(new Expression.Cut(token.span()));
        }

        // Grouping or token boundary
        if (token instanceof GrammarToken.LParen) {
            advance();
            var inner = parseExpression();
            if (inner.isFailure()) return inner;
            if (!(peek() instanceof GrammarToken.RParen)) {
                return Result.failure(new ParseError.UnexpectedInput(
                    peek().span().start(),
                    tokenDescription(peek()),
                    "')'"
                ));
            }
            advance();
            var span = SourceSpan.of(start, currentLocation());
            return Result.success(new Expression.Group(span, inner.unwrap()));
        }

        // Token boundary < >
        if (token instanceof GrammarToken.LAngle) {
            advance();
            var inner = parseExpression();
            if (inner.isFailure()) return inner;
            if (!(peek() instanceof GrammarToken.RAngle)) {
                return Result.failure(new ParseError.UnexpectedInput(
                    peek().span().start(),
                    tokenDescription(peek()),
                    "'>'"
                ));
            }
            advance();
            var span = SourceSpan.of(start, currentLocation());
            return Result.success(new Expression.TokenBoundary(span, inner.unwrap()));
        }

        // Named capture $name< >
        if (token instanceof GrammarToken.Dollar) {
            advance();
            if (!(peek() instanceof GrammarToken.Identifier nameId)) {
                // Back-reference would need lookahead
                return Result.failure(new ParseError.UnexpectedInput(
                    peek().span().start(),
                    tokenDescription(peek()),
                    "capture name"
                ));
            }
            advance();

            if (peek() instanceof GrammarToken.LAngle) {
                advance();
                var inner = parseExpression();
                if (inner.isFailure()) return inner;
                if (!(peek() instanceof GrammarToken.RAngle)) {
                    return Result.failure(new ParseError.UnexpectedInput(
                        peek().span().start(),
                        tokenDescription(peek()),
                        "'>'"
                    ));
                }
                advance();
                var span = SourceSpan.of(start, currentLocation());
                return Result.success(new Expression.Capture(span, nameId.name(), inner.unwrap()));
            } else {
                // Back-reference
                var span = SourceSpan.of(start, currentLocation());
                return Result.success(new Expression.BackReference(span, nameId.name()));
            }
        }

        return Result.failure(new ParseError.UnexpectedInput(
            token.span().start(),
            tokenDescription(token),
            "expression"
        ));
    }

    private boolean isAtEnd() {
        return peek() instanceof GrammarToken.Eof;
    }

    private GrammarToken peek() {
        return tokens.get(pos);
    }

    private void advance() {
        if (!isAtEnd()) {
            pos++;
        }
    }

    private boolean expect(Class<? extends GrammarToken> tokenClass) {
        if (tokenClass.isInstance(peek())) {
            advance();
            return true;
        }
        return false;
    }

    private SourceLocation currentLocation() {
        return peek().span().start();
    }

    private String tokenDescription(GrammarToken token) {
        return switch (token) {
            case GrammarToken.Identifier id -> "identifier '" + id.name() + "'";
            case GrammarToken.StringLiteral s -> "string literal";
            case GrammarToken.CharClassLiteral c -> "character class";
            case GrammarToken.ActionCode a -> "action code";
            case GrammarToken.Number n -> "number " + n.value();
            case GrammarToken.LeftArrow l -> "'<-'";
            case GrammarToken.Slash s -> "'/'";
            case GrammarToken.Ampersand a -> "'&'";
            case GrammarToken.Exclamation e -> "'!'";
            case GrammarToken.Question q -> "'?'";
            case GrammarToken.Star s -> "'*'";
            case GrammarToken.Plus p -> "'+'";
            case GrammarToken.Dot d -> "'.'";
            case GrammarToken.Tilde t -> "'~'";
            case GrammarToken.Cut c -> "'↑'";
            case GrammarToken.LParen l -> "'('";
            case GrammarToken.RParen r -> "')'";
            case GrammarToken.LAngle l -> "'<'";
            case GrammarToken.RAngle r -> "'>'";
            case GrammarToken.LBrace l -> "'{'";
            case GrammarToken.RBrace r -> "'}'";
            case GrammarToken.Comma c -> "','";
            case GrammarToken.Dollar d -> "'$'";
            case GrammarToken.Directive d -> "directive '%" + d.name() + "'";
            case GrammarToken.Eof e -> "end of input";
            case GrammarToken.Error e -> "error";
        };
    }
}
