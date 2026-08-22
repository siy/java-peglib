package org.pragmatica.peg.grammar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.error.ParseError;
import org.pragmatica.peg.source.SourceLocation;
import org.pragmatica.peg.source.SourceSpan;


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
                return new ParseError.SemanticError(error.span().start(),
                                                    error.message()).result();
            }
        }

        return new GrammarParser(tokens).parseGrammar();
    }

    private Result<Grammar> parseGrammar() {
        var rules = new ArrayList<Rule>();
        var suggestRules = new ArrayList<String>();
        var imports = new ArrayList<Import>();
        var recoverSets = new LinkedHashMap<String, Set<Character>>();
        var checkpointRules = new LinkedHashSet<String>();
        var memoRules = new LinkedHashSet<String>();
        var parserRules = new LinkedHashSet<String>();
        var nestingTrivia = new ArrayList<NestingPair>();
        Option<String> startRule = Option.none();
        Option<Expression> whitespace = Option.none();
        Option<Expression> word = Option.none();

        while (!isAtEnd()) {
            var token = peek();

            if (token instanceof GrammarToken.Directive directive) {
                // Grammar-level %suggest RuleName — designates a rule whose
                // literal alternatives form a suggestion vocabulary for
                // "did you mean 'X'?" hints. Parsed specially because its
                // argument is a rule name (identifier), not an expression.
                if ("suggest".equals(directive.name())) {
                    advance();
                    var result = parseSuggestDirective();

                    if (result instanceof Result.Failure<?> f) {
                        return f.cause()
                                .result();
                    }

                    suggestRules.add(result.unwrap());
                    continue;
                }
                // 0.2.8 — Grammar-level %import GrammarName.RuleName [as LocalName]
                // Argument is a dotted identifier pair, optionally followed by
                // "as <LocalName>". Parsed specially because the argument is not
                // an expression.
                if ("import".equals(directive.name())) {
                    var start = directive.span().start();

                    advance();
                    var result = parseImportDirective(start);

                    if (result instanceof Result.Failure<?> f) {
                        return f.cause()
                                .result();
                    }

                    imports.add(result.unwrap());
                    continue;
                }
                // 0.6.0 — Grammar-level %recover [chars] RuleName designates a
                // per-rule sync set. Distinguished from rule-level %recover
                // "msg" by lookahead: top-level form is followed by a
                // CharClassLiteral, the rule-level form by a StringLiteral.
                if ("recover".equals(directive.name()) && pos + 1 < tokens.size() && tokens.get(pos + 1) instanceof GrammarToken.CharClassLiteral) {
                    advance();
                    var result = parseRecoverDirective();

                    if (result instanceof Result.Failure<?> f) {
                        return f.cause()
                                .result();
                    }

                    var entry = result.unwrap();

                    recoverSets.put(entry.ruleName(), entry.chars());
                    continue;
                }
                // 0.6.1 — Grammar-level %checkpoint RuleName designates an
                // incremental-reparse boundary consumed by IncrementalParser.
                // No rule-level form exists, so the only disambiguation needed
                // is the lookahead that the next token is an Identifier.
                if ("checkpoint".equals(directive.name()) && pos + 1 < tokens.size() && tokens.get(pos + 1) instanceof GrammarToken.Identifier) {
                    advance();
                    var result = parseCheckpointDirective();

                    if (result instanceof Result.Failure<?> f) {
                        return f.cause()
                                .result();
                    }

                    checkpointRules.add(result.unwrap());
                    continue;
                }
                // 0.7.1 — Grammar-level %memo RuleName designates a rule whose
                // successful parse the generated parser memoises at a token
                // position (targeted packrat). Same shape as %checkpoint: no
                // rule-level form, argument is a rule name.
                if ("memo".equals(directive.name()) && pos + 1 < tokens.size() && tokens.get(pos + 1) instanceof GrammarToken.Identifier) {
                    advance();
                    var result = parseMemoDirective();

                    if (result instanceof Result.Failure<?> f) {
                        return f.cause()
                                .result();
                    }

                    memoRules.add(result.unwrap());
                    continue;
                }
                // 0.7.2 — Grammar-level %parser RuleName pins a rule to PARSER, overriding
                // classification inference. Same shape as %memo: argument is a rule name.
                if ("parser".equals(directive.name()) && pos + 1 < tokens.size() && tokens.get(pos + 1) instanceof GrammarToken.Identifier) {
                    advance();
                    var result = parseRuleNameArgument("%parser");

                    if (result instanceof Result.Failure<?> f) {
                        return f.cause()
                                .result();
                    }

                    parserRules.add(result.unwrap());
                    continue;
                }
                // 0.7.3 — Grammar-level %nest '<open>' '<close>' declares one delimiter pair
                // whose occurrences nest, lexed by a depth-counting scanner. Parsed specially
                // because the argument is a pair of string literals rather than an expression:
                // the recursive rule that would otherwise express nesting is refused as a
                // %whitespace cycle, and a nested block is not a regular language, so no DFA
                // path could match it however it were spelled.
                //
                // Unlike %memo and %parser above, this is NOT guarded on a lookahead at the
                // expected argument shape. Those guards let a malformed directive fall through
                // to the generic expression path, where an unrecognised name is silently
                // dropped. %nest is new in 0.7.3 and has no other reading, so claiming the
                // name unconditionally costs nothing and turns "%nest Foo" from a directive
                // that quietly does nothing into a named parse error.
                if ("nest".equals(directive.name())) {
                    advance();
                    var result = parseNestDirective();

                    if (result instanceof Result.Failure<?> f) {
                        return f.cause()
                                .result();
                    }

                    nestingTrivia.add(result.unwrap());
                    continue;
                }

                advance();
                var result = parseDirective(directive);

                if (result instanceof Result.Failure<?> f) {
                    return f.cause()
                            .result();
                }

                var expr = result.unwrap();

                switch (directive.name()) {
                    case "whitespace" -> whitespace = Option.some(expr);
                    case "word" -> word = Option.some(expr);
                }
            } else if (token instanceof GrammarToken.Identifier) {
                var result = parseRule();

                if (result instanceof Result.Failure<?> f) {
                    return f.cause()
                            .result();
                }

                rules.add(result.unwrap());
            } else if (token instanceof GrammarToken.Eof) {
                break;
            } else {
                return new ParseError.UnexpectedInput(token.span().start(),
                                                      tokenDescription(token),
                                                      "rule definition or directive").result();
            }
        }

        var copiedSuggest = List.copyOf(suggestRules);
        var copiedImports = List.copyOf(imports);
        var copiedRecover = Map.copyOf(recoverSets);
        var copiedCheckpoint = Set.copyOf(checkpointRules);
        var copiedMemo = Set.copyOf(memoRules);
        var copiedParser = Set.copyOf(parserRules);
        var copiedNesting = List.copyOf(nestingTrivia);
        // 0.4.0 — when a grammar declares no imports, validate eagerly via the
        // parse-don't-validate factory. With imports, the root grammar may
        // legitimately reference rule names that only appear after %import
        // resolution (e.g. {@code A_RuleA}); deferring validation until after
        // composition is required for those cases. The {@link GrammarResolver}
        // routes its final composed grammar through {@link Grammar#grammar}
        // so the validation still runs — just at the right point in the pipe.
        if (copiedImports.isEmpty()) {
            return Grammar.grammar(rules,
                                   startRule,
                                   whitespace,
                                   word,
                                   copiedSuggest,
                                   copiedImports,
                                   copiedRecover,
                                   copiedCheckpoint,
                                   copiedMemo,
                                   copiedParser,
                                   copiedNesting);
        }

        return Result.success(new Grammar(rules,
                                          startRule,
                                          whitespace,
                                          word,
                                          copiedSuggest,
                                          copiedImports,
                                          copiedRecover,
                                          copiedCheckpoint,
                                          copiedMemo,
                                          copiedParser,
                                          copiedNesting));
    }

    /** Result tuple for a parsed {@code %recover &lt;CharClass&gt; RuleName} directive. */
    private record RecoverEntry(String ruleName, Set<Character> chars) {}

    /**
     * Parse the body of a top-level {@code %recover &lt;CharClass&gt; RuleName}
     * directive. The {@code %recover} keyword has already been consumed.
     */
    private Result<RecoverEntry> parseRecoverDirective() {
        if (! (peek() instanceof GrammarToken.CharClassLiteral cc)) {
            return new ParseError.UnexpectedInput(peek().span().start(),
                                                  tokenDescription(peek()),
                                                  "character class for '%recover'").result();
        }

        advance();
        if (! (peek() instanceof GrammarToken.Identifier ruleId)) {
            return new ParseError.UnexpectedInput(peek().span().start(),
                                                  tokenDescription(peek()),
                                                  "rule name after '%recover' character class").result();
        }

        advance();
        var chars = expandCharClass(cc.pattern());

        return Result.success(new RecoverEntry(ruleId.name(), chars));
    }

    /**
     * Parse the body of a top-level {@code %checkpoint RuleName} directive.
     * The {@code %checkpoint} keyword has already been consumed. Unknown
     * rule names are accepted — the engine silently ignores them, matching
     * the relaxed handling of grammar-level {@code %recover}.
     *
     * @since 0.6.1
     */
    private Result<String> parseCheckpointDirective() {
        if (! (peek() instanceof GrammarToken.Identifier ruleId)) {
            return new ParseError.UnexpectedInput(peek().span().start(),
                                                  tokenDescription(peek()),
                                                  "rule name for '%checkpoint'").result();
        }

        advance();

        return Result.success(ruleId.name());
    }

    /**
     * Parse the body of a top-level {@code %memo RuleName} directive. The
     * {@code %memo} keyword has already been consumed. Unknown rule names are
     * accepted — the generator silently ignores them, matching the relaxed
     * handling of {@code %checkpoint}.
     *
     * @since 0.7.1
     */
    private Result<String> parseMemoDirective() {
        return parseRuleNameArgument("%memo");
    }

    /**
     * Parse the body of a top-level {@code %nest '<open>' '<close>'} directive. The
     * {@code %nest} keyword has already been consumed.
     *
     * @since 0.7.3
     */
    private Result<NestingPair> parseNestDirective() {
        var open = parseNestDelimiter("open");

        if (open instanceof Result.Failure<?> f) {
            return f.cause()
                    .result();
        }

        var close = parseNestDelimiter("close");

        if (close instanceof Result.Failure<?> f) {
            return f.cause()
                    .result();
        }

        return Result.success(new NestingPair(open.unwrap(), close.unwrap()));
    }

    /**
     * Read one delimiter of a {@code %nest} pair.
     *
     * <p>An empty delimiter is refused here rather than tolerated. Every other directive
     * argument in this grammar follows a relaxed policy — an unknown rule name is inert, not
     * fatal — but an empty {@code %nest} open delimiter matches at every position and advances
     * the counting scanner by zero characters, so accepting one would hang the lexer on the
     * first input it saw. A grammar bug that refuses to compile is strictly better than one
     * that compiles and never returns.
     */
    private Result<String> parseNestDelimiter(String role) {
        if (! (peek() instanceof GrammarToken.StringLiteral literal)) {
            return new ParseError.UnexpectedInput(peek().span().start(),
                                                  tokenDescription(peek()),
                                                  role + " delimiter string for '%nest'").result();
        }

        advance();
        if (literal.value().isEmpty()) {
            return new ParseError.SemanticError(literal.span().start(),
                                                "'%nest' " + role + " delimiter must not be empty").result();
        }

        return Result.success(literal.value());
    }

    /** Read the single rule-name argument of a directive such as {@code %memo} or {@code %parser}. */
    private Result<String> parseRuleNameArgument(String directiveName) {
        if (! (peek() instanceof GrammarToken.Identifier ruleId)) {
            return new ParseError.UnexpectedInput(peek().span().start(),
                                                  tokenDescription(peek()),
                                                  "rule name for '" + directiveName + "'").result();
        }

        advance();

        return Result.success(ruleId.name());
    }

    /**
     * Expand a character-class pattern (the raw text inside {@code [...]}) into
     * the set of characters it matches. Supports literal characters and ranges
     * ({@code a-z}). Backslash escapes are honoured for the common escape
     * sequences ({@code \n}, {@code \t}, {@code \r}, {@code \\}, {@code \]},
     * {@code \[}). Negation ({@code [^...]}) is intentionally not honoured for
     * sync sets — sync chars are inherently a positive set; the lexer would
     * never emit a kind for "any char NOT in this set".
     */
    private static Set<Character> expandCharClass(String pattern) {
        var result = new LinkedHashSet<Character>();
        var i = 0;
        var n = pattern.length();

        while (i < n) {
            char c = pattern.charAt(i);

            if (c == '\\' && i + 1 < n) {
                char esc = pattern.charAt(i + 1);
                char decoded = switch (esc) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case '\\' -> '\\';
                    case ']' -> ']';
                    case '[' -> '[';
                    default -> esc;
                };

                result.add(decoded);
                i += 2;
                continue;
            }

            if (i + 2 < n && pattern.charAt(i + 1) == '-') {
                char start = c;
                char end = pattern.charAt(i + 2);

                for (char ch = start; ch <= end; ch++) {
                    result.add(ch);
                }

                i += 3;
                continue;
            }

            result.add(c);
            i++;
        }

        return Set.copyOf(result);
    }

    private Result<Import> parseImportDirective(SourceLocation start) {
        if (! (peek() instanceof GrammarToken.Identifier grammarId)) {
            return new ParseError.UnexpectedInput(peek().span().start(),
                                                  tokenDescription(peek()),
                                                  "grammar name for '%import'").result();
        }

        advance();
        if (! (peek() instanceof GrammarToken.Dot)) {
            return new ParseError.UnexpectedInput(peek().span().start(),
                                                  tokenDescription(peek()),
                                                  "'.' between grammar name and rule name").result();
        }

        advance();
        if (! (peek() instanceof GrammarToken.Identifier ruleId)) {
            return new ParseError.UnexpectedInput(peek().span().start(),
                                                  tokenDescription(peek()),
                                                  "rule name after '.'").result();
        }

        advance();
        Option<String> alias = Option.none();

        if (peek() instanceof GrammarToken.Identifier asId && "as".equals(asId.name())) {
            advance();
            if (! (peek() instanceof GrammarToken.Identifier aliasId)) {
                return new ParseError.UnexpectedInput(peek().span().start(),
                                                      tokenDescription(peek()),
                                                      "local name after 'as'").result();
            }

            advance();
            alias = Option.some(aliasId.name());
        }

        var span = SourceSpan.sourceSpan(start, currentLocation());

        return Result.success(new Import(span, grammarId.name(), ruleId.name(), alias));
    }

    private Result<String> parseSuggestDirective() {
        if (! (peek() instanceof GrammarToken.Identifier id)) {
            return new ParseError.UnexpectedInput(peek().span().start(),
                                                  tokenDescription(peek()),
                                                  "rule name").result();
        }

        advance();

        return Result.success(id.name());
    }

    private Result<Expression> parseDirective(GrammarToken.Directive directive) {
        if (!expect(GrammarToken.LeftArrow.class)) {
            return new ParseError.UnexpectedInput(peek().span().start(),
                                                  tokenDescription(peek()),
                                                  "'<-'").result();
        }

        return parseExpression();
    }

    private Result<Rule> parseRule() {
        var start = peek().span().start();

        if (! (peek() instanceof GrammarToken.Identifier id)) {
            return new ParseError.UnexpectedInput(peek().span().start(),
                                                  tokenDescription(peek()),
                                                  "rule name").result();
        }

        advance();
        if (!expect(GrammarToken.LeftArrow.class)) {
            return new ParseError.UnexpectedInput(peek().span().start(),
                                                  tokenDescription(peek()),
                                                  "'<-'").result();
        }

        var exprResult = parseExpression();

        if (exprResult instanceof Result.Failure<?> f) {
            return f.cause()
                    .result();
        }

        var expression = exprResult.unwrap();
        // Check for action and/or error_message
        Option<String> action = Option.none();
        Option<String> errorMessage = Option.none();

        while (peek() instanceof GrammarToken.ActionCode actionCode) {
            advance();
            var code = actionCode.code().trim();

            if (code.startsWith("error_message")) {
                // Parse: error_message "message text"
                var msgStart = code.indexOf('"');
                var msgEnd = code.lastIndexOf('"');

                if (msgStart != -1 && msgEnd > msgStart) {
                    errorMessage = Option.some(code.substring(msgStart + 1, msgEnd));
                }
            } else {
                action = Option.some(actionCode.code());
            }
        }
        // Trailing rule-level directives: %expected / %recover / %tag (0.2.4).
        // Each takes a single string-literal argument and is optional; order
        // among them is flexible so the author can pick whichever reads best.
        //
        // 0.6.0 — the grammar-level form {@code %recover [chars] RuleName} is
        // disambiguated by lookahead: when {@code %recover} is followed by a
        // CharClassLiteral the directive is left for {@link #parseGrammar()}
        // to consume on the next iteration.
        Option<String> expected = Option.none();
        Option<String> recover = Option.none();
        Option<String> tag = Option.none();

        while (peek() instanceof GrammarToken.Directive d && isRuleLevelTrailingDirective(d)) {
            advance();
            var argResult = parseStringLiteralArg(d.name());

            if (argResult instanceof Result.Failure<?> f) {
                return f.cause()
                        .result();
            }

            var value = argResult.unwrap();

            switch (d.name()) {
                case "expected" -> expected = Option.some(value);
                case "recover" -> recover = Option.some(value);
                case "tag" -> tag = Option.some(value);
            }
        }

        var span = SourceSpan.sourceSpan(start, currentLocation());

        return Result.success(new Rule(span, id.name(), expression, action, errorMessage, expected, recover, tag));
    }

    /**
     * True when {@code d} is a rule-level trailing directive that consumes a
     * string-literal argument. The grammar-level {@code %recover [chars] RuleName}
     * form is intentionally excluded — it is recognised by lookahead in
     * {@link #parseGrammar()} where the next token is a CharClassLiteral.
     */
    private boolean isRuleLevelTrailingDirective(GrammarToken.Directive d) {
        var name = d.name();

        if (!"expected".equals(name) && !"recover".equals(name) && !"tag".equals(name)) {
            return false;
        }

        if ("recover".equals(name) && pos + 1 < tokens.size() && tokens.get(pos + 1) instanceof GrammarToken.CharClassLiteral) {
            return false;
        }

        return true;
    }

    private Result<String> parseStringLiteralArg(String directiveName) {
        if (! (peek() instanceof GrammarToken.StringLiteral lit)) {
            return new ParseError.UnexpectedInput(peek().span().start(),
                                                  tokenDescription(peek()),
                                                  "string literal argument for '%" + directiveName + "'").result();
        }

        advance();

        return Result.success(lit.value());
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

        var span = SourceSpan.sourceSpan(start, currentLocation());

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
            return new ParseError.UnexpectedInput(peek().span().start(),
                                                  tokenDescription(peek()),
                                                  "expression").result();
        }

        if (elements.size() == 1) {
            return Result.success(elements.getFirst());
        }

        var span = SourceSpan.sourceSpan(start, currentLocation());

        return Result.success(new Expression.Sequence(span, elements));
    }

    private boolean isSequenceElement() {
        var token = peek();
        // Identifier followed by <- is a new rule definition, not a reference
        if (token instanceof GrammarToken.Identifier) {
            return ! isRuleDefinitionStart();
        }

        return token instanceof GrammarToken.StringLiteral || token instanceof GrammarToken.CharClassLiteral || token instanceof GrammarToken.Dot || token instanceof GrammarToken.LParen || token instanceof GrammarToken.LAngle || token instanceof GrammarToken.Ampersand || token instanceof GrammarToken.Exclamation || token instanceof GrammarToken.Tilde || token instanceof GrammarToken.Dollar || token instanceof GrammarToken.Cut;
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

            var span = SourceSpan.sourceSpan(start, currentLocation());

            return Result.success(new Expression.And(span, inner.unwrap()));
        }

        if (peek() instanceof GrammarToken.Exclamation) {
            advance();
            var inner = parseSuffix();

            if (inner.isFailure()) return inner;

            var span = SourceSpan.sourceSpan(start, currentLocation());

            return Result.success(new Expression.Not(span, inner.unwrap()));
        }

        if (peek() instanceof GrammarToken.Tilde) {
            advance();
            var inner = parseSuffix();

            if (inner.isFailure()) return inner;

            var span = SourceSpan.sourceSpan(start, currentLocation());

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
        // Check for dictionary operator: 'word1' | 'word2' | 'word3'
        if (expr instanceof Expression.Literal firstLit && peek() instanceof GrammarToken.Pipe) {
            var words = new ArrayList<String>();

            words.add(firstLit.text());
            boolean caseInsensitive = firstLit.caseInsensitive();

            while (peek() instanceof GrammarToken.Pipe) {
                advance();
                // skip |
                var nextPrimary = parsePrimary();

                if (nextPrimary.isFailure()) {
                    return nextPrimary;
                }

                if (! (nextPrimary.unwrap() instanceof Expression.Literal nextLit)) {
                    return new ParseError.UnexpectedInput(peek().span().start(),
                                                          "non-literal",
                                                          "string literal for dictionary").result();
                }

                words.add(nextLit.text());
                // If any literal is case-insensitive, the whole dictionary is
                if (nextLit.caseInsensitive()) {
                    caseInsensitive = true;
                }
            }

            var span = SourceSpan.sourceSpan(start, currentLocation());

            expr = new Expression.Dictionary(span, words, caseInsensitive);
        }

        while (true) {
            if (peek() instanceof GrammarToken.Star) {
                advance();
                var span = SourceSpan.sourceSpan(start, currentLocation());

                expr = new Expression.ZeroOrMore(span, expr);
            } else if (peek() instanceof GrammarToken.Plus) {
                advance();
                var span = SourceSpan.sourceSpan(start, currentLocation());

                expr = new Expression.OneOrMore(span, expr);
            } else if (peek() instanceof GrammarToken.Question) {
                advance();
                var span = SourceSpan.sourceSpan(start, currentLocation());

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
        advance();
        // skip {
        if (! (peek() instanceof GrammarToken.Number min)) {
            return new ParseError.UnexpectedInput(peek().span().start(),
                                                  tokenDescription(peek()),
                                                  "number").result();
        }

        advance();
        Option<Integer> max;

        if (peek() instanceof GrammarToken.Comma) {
            advance();
            if (peek() instanceof GrammarToken.Number maxNum) {
                advance();
                max = Option.some(maxNum.value());
            } else {
                max = Option.none();
            }
        } else {
            max = Option.some(min.value());
        }

        if (! (peek() instanceof GrammarToken.RBrace)) {
            return new ParseError.UnexpectedInput(peek().span().start(),
                                                  tokenDescription(peek()),
                                                  "'}'").result();
        }

        advance();
        var span = SourceSpan.sourceSpan(start, currentLocation());

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

            return Result.success(new Expression.CharClass(token.span(),
                                                           cc.pattern(),
                                                           cc.negated(),
                                                           cc.caseInsensitive()));
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

            if (! (peek() instanceof GrammarToken.RParen)) {
                return new ParseError.UnexpectedInput(peek().span().start(),
                                                      tokenDescription(peek()),
                                                      "')'").result();
            }

            advance();
            var span = SourceSpan.sourceSpan(start, currentLocation());

            return Result.success(new Expression.Group(span, inner.unwrap()));
        }
        // Token boundary < >
        if (token instanceof GrammarToken.LAngle) {
            advance();
            var inner = parseExpression();

            if (inner.isFailure()) return inner;

            if (! (peek() instanceof GrammarToken.RAngle)) {
                return new ParseError.UnexpectedInput(peek().span().start(),
                                                      tokenDescription(peek()),
                                                      "'>'").result();
            }

            advance();
            var span = SourceSpan.sourceSpan(start, currentLocation());

            return Result.success(new Expression.TokenBoundary(span, inner.unwrap()));
        }
        // Capture scope $(...), Named capture $name< >, or Back-reference $name
        if (token instanceof GrammarToken.Dollar) {
            advance();
            // Capture scope: $(...)
            if (peek() instanceof GrammarToken.LParen) {
                advance();
                var inner = parseExpression();

                if (inner.isFailure()) return inner;

                if (! (peek() instanceof GrammarToken.RParen)) {
                    return new ParseError.UnexpectedInput(peek().span().start(),
                                                          tokenDescription(peek()),
                                                          "')'").result();
                }

                advance();
                var span = SourceSpan.sourceSpan(start, currentLocation());

                return Result.success(new Expression.CaptureScope(span, inner.unwrap()));
            }
            // Named capture or back-reference requires identifier
            if (! (peek() instanceof GrammarToken.Identifier nameId)) {
                return new ParseError.UnexpectedInput(peek().span().start(),
                                                      tokenDescription(peek()),
                                                      "capture name or '('").result();
            }

            advance();
            if (peek() instanceof GrammarToken.LAngle) {
                advance();
                var inner = parseExpression();

                if (inner.isFailure()) return inner;

                if (! (peek() instanceof GrammarToken.RAngle)) {
                    return new ParseError.UnexpectedInput(peek().span().start(),
                                                          tokenDescription(peek()),
                                                          "'>'").result();
                }

                advance();
                var span = SourceSpan.sourceSpan(start, currentLocation());

                return Result.success(new Expression.Capture(span, nameId.name(), inner.unwrap()));
            } else {
                // Back-reference
                var span = SourceSpan.sourceSpan(start, currentLocation());

                return Result.success(new Expression.BackReference(span, nameId.name()));
            }
        }

        return new ParseError.UnexpectedInput(token.span().start(),
                                              tokenDescription(token),
                                              "expression").result();
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
        return peek().span()
                   .start();
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
            case GrammarToken.Cut c -> "'^'";
            case GrammarToken.LParen l -> "'('";
            case GrammarToken.RParen r -> "')'";
            case GrammarToken.LAngle l -> "'<'";
            case GrammarToken.RAngle r -> "'>'";
            case GrammarToken.LBrace l -> "'{'";
            case GrammarToken.RBrace r -> "'}'";
            case GrammarToken.Comma c -> "','";
            case GrammarToken.Dollar d -> "'$'";
            case GrammarToken.Pipe p -> "'|'";
            case GrammarToken.Directive d -> "directive '%" + d.name() + "'";
            case GrammarToken.Eof e -> "end of input";
            case GrammarToken.Error e -> "error";
        };
    }
}
