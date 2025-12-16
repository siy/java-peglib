package org.pragmatica.peg.parser;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.action.Action;
import org.pragmatica.peg.action.ActionCompiler;
import org.pragmatica.peg.action.SemanticValues;
import org.pragmatica.peg.error.ParseError;
import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.Rule;
import org.pragmatica.peg.tree.AstNode;
import org.pragmatica.peg.tree.CstNode;
import org.pragmatica.peg.tree.SourceLocation;
import org.pragmatica.peg.tree.SourceSpan;
import org.pragmatica.peg.tree.Trivia;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PEG parsing engine - interprets Grammar to parse input text.
 */
public final class PegEngine implements Parser {

    private final Grammar grammar;
    private final ParserConfig config;
    private final Map<String, Action> actions;

    private PegEngine(Grammar grammar, ParserConfig config, Map<String, Action> actions) {
        this.grammar = grammar;
        this.config = config;
        this.actions = actions;
    }

    public static Result<PegEngine> create(Grammar grammar, ParserConfig config) {
        // Compile all actions in the grammar
        var compiler = ActionCompiler.create();
        return compiler.compileGrammar(grammar)
            .map(actions -> new PegEngine(grammar, config, actions));
    }

    public static PegEngine createWithoutActions(Grammar grammar, ParserConfig config) {
        return new PegEngine(grammar, config, new HashMap<>());
    }

    @Override
    public Result<CstNode> parseCst(String input) {
        var startRule = grammar.effectiveStartRule();
        if (startRule.isEmpty()) {
            return Result.failure(new ParseError.SemanticError(
                SourceLocation.START,
                "No start rule defined in grammar"
            ));
        }
        return parseCst(input, startRule.unwrap().name());
    }

    @Override
    public Result<CstNode> parseCst(String input, String startRule) {
        var ruleOpt = grammar.rule(startRule);
        if (ruleOpt.isEmpty()) {
            return Result.failure(new ParseError.SemanticError(
                SourceLocation.START,
                "Unknown rule: " + startRule
            ));
        }

        var ctx = ParsingContext.create(input, grammar, config);
        var result = parseRule(ctx, ruleOpt.unwrap());

        if (result.isFailure()) {
            var failure = (ParseResult.Failure) result;
            return Result.failure(new ParseError.UnexpectedInput(
                failure.location(),
                ctx.isAtEnd() ? "end of input" : String.valueOf(ctx.peek()),
                failure.expected()
            ));
        }

        // Capture trailing trivia
        var trailingTrivia = skipWhitespace(ctx);

        // Check if we consumed all input
        if (!ctx.isAtEnd()) {
            return Result.failure(new ParseError.UnexpectedInput(
                ctx.location(),
                String.valueOf(ctx.peek()),
                "end of input"
            ));
        }

        var success = (ParseResult.Success) result;
        // Attach trailing trivia to root node
        var rootNode = attachTrailingTrivia(success.node(), trailingTrivia);
        return Result.success(rootNode);
    }

    @Override
    public Result<AstNode> parseAst(String input) {
        return parseCst(input).map(this::toAst);
    }

    @Override
    public Result<AstNode> parseAst(String input, String startRule) {
        return parseCst(input, startRule).map(this::toAst);
    }

    @Override
    public Result<Object> parse(String input) {
        var startRule = grammar.effectiveStartRule();
        if (startRule.isEmpty()) {
            return Result.failure(new ParseError.SemanticError(
                SourceLocation.START,
                "No start rule defined in grammar"
            ));
        }
        return parse(input, startRule.unwrap().name());
    }

    @Override
    public Result<Object> parse(String input, String startRule) {
        var ruleOpt = grammar.rule(startRule);
        if (ruleOpt.isEmpty()) {
            return Result.failure(new ParseError.SemanticError(
                SourceLocation.START,
                "Unknown rule: " + startRule
            ));
        }

        var ctx = ParsingContext.create(input, grammar, config);
        var result = parseRuleWithActions(ctx, ruleOpt.unwrap());

        if (result.isFailure()) {
            var failure = (ParseResult.Failure) result;
            return Result.failure(new ParseError.UnexpectedInput(
                failure.location(),
                ctx.isAtEnd() ? "end of input" : String.valueOf(ctx.peek()),
                failure.expected()
            ));
        }

        // Skip trailing whitespace before checking end
        skipWhitespace(ctx);

        if (!ctx.isAtEnd()) {
            return Result.failure(new ParseError.UnexpectedInput(
                ctx.location(),
                String.valueOf(ctx.peek()),
                "end of input"
            ));
        }

        var success = (ParseResult.Success) result;
        return Result.success(success.hasSemanticValue()
            ? success.unwrapSemanticValue()
            : success.node());
    }

    // === Rule Parsing ===

    private ParseResult parseRule(ParsingContext ctx, Rule rule) {
        var startPos = ctx.pos();
        var startLoc = ctx.location();

        // Check packrat cache at START position
        var cached = ctx.getCachedAt(rule.name(), startPos);
        if (cached.isPresent()) {
            var result = cached.unwrap();
            if (result.isSuccess()) {
                var success = (ParseResult.Success) result;
                ctx.restoreLocation(success.endLocation());
            }
            return result;
        }

        // Skip leading whitespace
        var leadingTrivia = skipWhitespace(ctx);

        var result = parseExpression(ctx, rule.expression(), rule.name());

        // Cache the result at START position
        ctx.cacheAt(rule.name(), startPos, result);

        if (result instanceof ParseResult.Success success) {
            // Wrap in a proper CST node with the rule name
            var node = wrapWithRuleName(success.node(), rule.name(), leadingTrivia);
            return ParseResult.Success.of(node, ctx.location());
        }

        // Restore position on failure
        ctx.restoreLocation(startLoc);
        // Use custom error message if available
        if (rule.hasErrorMessage()) {
            return ParseResult.Failure.at(startLoc, rule.errorMessage().unwrap());
        }
        return result;
    }

    /**
     * Parse rule with action execution.
     * Collects child semantic values and executes rule action if present.
     */
    private ParseResult parseRuleWithActions(ParsingContext ctx, Rule rule) {
        var startPos = ctx.pos();
        var startLoc = ctx.location();

        // Skip leading whitespace
        skipWhitespace(ctx);

        var childValues = new ArrayList<Object>();
        var tokenCapture = new String[1]; // Holder for token boundary capture
        var result = parseExpressionWithActions(ctx, rule.expression(), rule.name(), childValues, tokenCapture);

        if (result.isFailure()) {
            ctx.restoreLocation(startLoc);
            // Use custom error message if available
            if (rule.hasErrorMessage()) {
                return ParseResult.Failure.at(startLoc, rule.errorMessage().unwrap());
            }
            return result;
        }

        var success = (ParseResult.Success) result;
        // Use token capture if available, otherwise full match
        var matchedText = tokenCapture[0] != null ? tokenCapture[0] : ctx.substring(startPos, ctx.pos());
        var span = ctx.spanFrom(startLoc);

        // Execute action if present
        var action = actions.get(rule.name());
        if (action != null) {
            var sv = SemanticValues.of(matchedText, span, childValues);
            try {
                var value = action.apply(sv);
                var node = wrapWithRuleName(success.node(), rule.name(), List.of());
                return ParseResult.Success.withValue(node, ctx.location(), value);
            } catch (Exception e) {
                return ParseResult.Failure.at(startLoc, "action error: " + e.getMessage());
            }
        }

        // No action - return node with child values as semantic value if any
        var node = wrapWithRuleName(success.node(), rule.name(), List.of());
        if (!childValues.isEmpty()) {
            return ParseResult.Success.withValue(node, ctx.location(),
                childValues.size() == 1 ? childValues.getFirst() : childValues);
        }
        return ParseResult.Success.of(node, ctx.location());
    }

    /**
     * Parse expression collecting semantic values from child rules.
     */
    private ParseResult parseExpressionWithActions(ParsingContext ctx, Expression expr,
                                                    String ruleName, List<Object> values, String[] tokenCapture) {
        return switch (expr) {
            case Expression.Literal lit -> parseLiteral(ctx, lit);
            case Expression.CharClass cc -> parseCharClass(ctx, cc);
            case Expression.Any any -> parseAny(ctx, any);
            case Expression.Reference ref -> parseReferenceWithActions(ctx, ref, values);
            case Expression.Sequence seq -> parseSequenceWithActions(ctx, seq, ruleName, values, tokenCapture);
            case Expression.Choice choice -> parseChoiceWithActions(ctx, choice, ruleName, values, tokenCapture);
            case Expression.ZeroOrMore zom -> parseZeroOrMoreWithActions(ctx, zom, ruleName, values, tokenCapture);
            case Expression.OneOrMore oom -> parseOneOrMoreWithActions(ctx, oom, ruleName, values, tokenCapture);
            case Expression.Optional opt -> parseOptionalWithActions(ctx, opt, ruleName, values, tokenCapture);
            case Expression.Repetition rep -> parseRepetitionWithActions(ctx, rep, ruleName, values, tokenCapture);
            case Expression.And and -> parseAnd(ctx, and, ruleName);
            case Expression.Not not -> parseNot(ctx, not, ruleName);
            case Expression.TokenBoundary tb -> parseTokenBoundaryWithActions(ctx, tb, ruleName, values, tokenCapture);
            case Expression.Ignore ign -> parseIgnore(ctx, ign, ruleName);
            case Expression.Capture cap -> parseCaptureWithActions(ctx, cap, ruleName, values, tokenCapture);
            case Expression.CaptureScope cs -> parseCaptureScopeWithActions(ctx, cs, ruleName, values, tokenCapture);
            case Expression.Dictionary dict -> parseDictionary(ctx, dict);
            case Expression.BackReference br -> parseBackReference(ctx, br);
            case Expression.Cut cut -> parseCut(ctx, cut);
            case Expression.Group grp -> parseExpressionWithActions(ctx, grp.expression(), ruleName, values, tokenCapture);
        };
    }

    private ParseResult parseReferenceWithActions(ParsingContext ctx, Expression.Reference ref, List<Object> values) {
        var ruleOpt = grammar.rule(ref.ruleName());
        if (ruleOpt.isEmpty()) {
            return ParseResult.Failure.at(ctx.location(), "rule '" + ref.ruleName() + "'");
        }
        var result = parseRuleWithActions(ctx, ruleOpt.unwrap());
        if (result instanceof ParseResult.Success success && success.hasSemanticValue()) {
            values.add(success.unwrapSemanticValue());
        }
        return result;
    }

    private ParseResult parseSequenceWithActions(ParsingContext ctx, Expression.Sequence seq,
                                                  String ruleName, List<Object> values, String[] tokenCapture) {
        var startLoc = ctx.location();
        var children = new ArrayList<CstNode>();

        for (var element : seq.elements()) {
            // Skip whitespace between elements, but NOT before predicates
            if (!isPredicate(element)) {
                skipWhitespace(ctx);
            }
            var result = parseExpressionWithActions(ctx, element, ruleName, values, tokenCapture);
            if (result.isFailure()) {
                ctx.restoreLocation(startLoc);
                return result;
            }
            if (result instanceof ParseResult.Success success) {
                children.add(success.node());
            }
        }

        var span = ctx.spanFrom(startLoc);
        var node = new CstNode.NonTerminal(span, ruleName, children, List.of(), List.of());
        return ParseResult.Success.of(node, ctx.location());
    }

    private ParseResult parseChoiceWithActions(ParsingContext ctx, Expression.Choice choice,
                                                String ruleName, List<Object> values, String[] tokenCapture) {
        var startLoc = ctx.location();
        ParseResult lastFailure = null;

        for (var alt : choice.alternatives()) {
            var localValues = new ArrayList<Object>();
            var localTokenCapture = new String[1];
            var result = parseExpressionWithActions(ctx, alt, ruleName, localValues, localTokenCapture);
            if (result.isSuccess()) {
                values.addAll(localValues);
                if (localTokenCapture[0] != null) {
                    tokenCapture[0] = localTokenCapture[0];
                }
                return result;
            }
            lastFailure = result;
            ctx.restoreLocation(startLoc);
        }

        return lastFailure != null
            ? lastFailure
            : ParseResult.Failure.at(ctx.location(), "one of alternatives");
    }

    private ParseResult parseZeroOrMoreWithActions(ParsingContext ctx, Expression.ZeroOrMore zom,
                                                    String ruleName, List<Object> values, String[] tokenCapture) {
        var startLoc = ctx.location();
        var children = new ArrayList<CstNode>();

        while (true) {
            var beforeLoc = ctx.location();
            skipWhitespace(ctx);
            var localValues = new ArrayList<Object>();
            var localTokenCapture = new String[1];
            var result = parseExpressionWithActions(ctx, zom.expression(), ruleName, localValues, localTokenCapture);

            if (result.isFailure()) {
                ctx.restoreLocation(beforeLoc);
                break;
            }

            if (result instanceof ParseResult.Success success) {
                children.add(success.node());
            }
            values.addAll(localValues);
            if (localTokenCapture[0] != null) {
                tokenCapture[0] = localTokenCapture[0];
            }

            if (ctx.pos() == beforeLoc.offset()) {
                break;
            }
        }

        var span = ctx.spanFrom(startLoc);
        if (children.size() == 1) {
            return ParseResult.Success.of(children.getFirst(), ctx.location());
        }
        var node = new CstNode.NonTerminal(span, ruleName, children, List.of(), List.of());
        return ParseResult.Success.of(node, ctx.location());
    }

    private ParseResult parseOneOrMoreWithActions(ParsingContext ctx, Expression.OneOrMore oom,
                                                   String ruleName, List<Object> values, String[] tokenCapture) {
        var startLoc = ctx.location();
        var children = new ArrayList<CstNode>();

        var first = parseExpressionWithActions(ctx, oom.expression(), ruleName, values, tokenCapture);
        if (first.isFailure()) {
            return first;
        }
        if (first instanceof ParseResult.Success success) {
            children.add(success.node());
        }

        while (true) {
            var beforeLoc = ctx.location();
            skipWhitespace(ctx);
            var localValues = new ArrayList<Object>();
            var localTokenCapture = new String[1];
            var result = parseExpressionWithActions(ctx, oom.expression(), ruleName, localValues, localTokenCapture);

            if (result.isFailure()) {
                ctx.restoreLocation(beforeLoc);
                break;
            }

            if (result instanceof ParseResult.Success success) {
                children.add(success.node());
            }
            values.addAll(localValues);
            if (localTokenCapture[0] != null) {
                tokenCapture[0] = localTokenCapture[0];
            }

            if (ctx.pos() == beforeLoc.offset()) {
                break;
            }
        }

        var span = ctx.spanFrom(startLoc);
        if (children.size() == 1) {
            return ParseResult.Success.of(children.getFirst(), ctx.location());
        }
        var node = new CstNode.NonTerminal(span, ruleName, children, List.of(), List.of());
        return ParseResult.Success.of(node, ctx.location());
    }

    private ParseResult parseOptionalWithActions(ParsingContext ctx, Expression.Optional opt,
                                                  String ruleName, List<Object> values, String[] tokenCapture) {
        var startLoc = ctx.location();
        var result = parseExpressionWithActions(ctx, opt.expression(), ruleName, values, tokenCapture);

        if (result.isSuccess()) {
            return result;
        }

        ctx.restoreLocation(startLoc);
        var span = SourceSpan.at(startLoc);
        var node = new CstNode.NonTerminal(span, ruleName, List.of(), List.of(), List.of());
        return ParseResult.Success.of(node, ctx.location());
    }

    private ParseResult parseRepetitionWithActions(ParsingContext ctx, Expression.Repetition rep,
                                                    String ruleName, List<Object> values, String[] tokenCapture) {
        var startLoc = ctx.location();
        var children = new ArrayList<CstNode>();

        int count = 0;
        int max = rep.max().isPresent() ? rep.max().unwrap() : Integer.MAX_VALUE;

        while (count < max) {
            var beforeLoc = ctx.location();
            if (count > 0) {
                skipWhitespace(ctx);
            }
            var localValues = new ArrayList<Object>();
            var localTokenCapture = new String[1];
            var result = parseExpressionWithActions(ctx, rep.expression(), ruleName, localValues, localTokenCapture);

            if (result.isFailure()) {
                ctx.restoreLocation(beforeLoc);
                break;
            }

            if (result instanceof ParseResult.Success success) {
                children.add(success.node());
            }
            values.addAll(localValues);
            if (localTokenCapture[0] != null) {
                tokenCapture[0] = localTokenCapture[0];
            }
            count++;

            if (ctx.pos() == beforeLoc.offset()) {
                break;
            }
        }

        if (count < rep.min()) {
            ctx.restoreLocation(startLoc);
            return ParseResult.Failure.at(ctx.location(),
                "at least " + rep.min() + " repetitions");
        }

        var span = ctx.spanFrom(startLoc);
        if (children.size() == 1) {
            return ParseResult.Success.of(children.getFirst(), ctx.location());
        }
        var node = new CstNode.NonTerminal(span, ruleName, children, List.of(), List.of());
        return ParseResult.Success.of(node, ctx.location());
    }

    private ParseResult parseTokenBoundaryWithActions(ParsingContext ctx, Expression.TokenBoundary tb,
                                                       String ruleName, List<Object> values, String[] tokenCapture) {
        var startLoc = ctx.location();
        var startPos = ctx.pos();

        // Disable whitespace skipping inside token boundary
        ctx.enterTokenBoundary();
        try {
            // Token boundary inner expressions don't propagate token capture
            var innerTokenCapture = new String[1];
            var result = parseExpressionWithActions(ctx, tb.expression(), ruleName, values, innerTokenCapture);
            if (result.isFailure()) {
                return result;
            }

            var endPos = ctx.pos();
            var text = ctx.substring(startPos, endPos);
            // Set token capture so $0 returns this captured text
            tokenCapture[0] = text;
            var span = ctx.spanFrom(startLoc);
            var node = new CstNode.Token(span, ruleName, text, List.of(), List.of());
            return ParseResult.Success.of(node, ctx.location());
        } finally {
            ctx.exitTokenBoundary();
        }
    }

    private ParseResult parseCaptureWithActions(ParsingContext ctx, Expression.Capture cap,
                                                 String ruleName, List<Object> values, String[] tokenCapture) {
        var startPos = ctx.pos();
        var result = parseExpressionWithActions(ctx, cap.expression(), ruleName, values, tokenCapture);

        if (result.isSuccess()) {
            var text = ctx.substring(startPos, ctx.pos());
            ctx.setCapture(cap.name(), text);
        }

        return result;
    }

    /**
     * Parse capture scope with actions - isolates captures within the expression.
     */
    private ParseResult parseCaptureScopeWithActions(ParsingContext ctx, Expression.CaptureScope cs,
                                                      String ruleName, List<Object> values, String[] tokenCapture) {
        var savedCaptures = ctx.saveCaptures();
        var result = parseExpressionWithActions(ctx, cs.expression(), ruleName, values, tokenCapture);
        ctx.restoreCaptures(savedCaptures);
        return result;
    }

    // === Expression Parsing ===

    private ParseResult parseExpression(ParsingContext ctx, Expression expr, String ruleName) {
        return switch (expr) {
            case Expression.Literal lit -> parseLiteral(ctx, lit);
            case Expression.CharClass cc -> parseCharClass(ctx, cc);
            case Expression.Any any -> parseAny(ctx, any);
            case Expression.Reference ref -> parseReference(ctx, ref);
            case Expression.Sequence seq -> parseSequence(ctx, seq, ruleName);
            case Expression.Choice choice -> parseChoice(ctx, choice, ruleName);
            case Expression.ZeroOrMore zom -> parseZeroOrMore(ctx, zom, ruleName);
            case Expression.OneOrMore oom -> parseOneOrMore(ctx, oom, ruleName);
            case Expression.Optional opt -> parseOptional(ctx, opt, ruleName);
            case Expression.Repetition rep -> parseRepetition(ctx, rep, ruleName);
            case Expression.And and -> parseAnd(ctx, and, ruleName);
            case Expression.Not not -> parseNot(ctx, not, ruleName);
            case Expression.TokenBoundary tb -> parseTokenBoundary(ctx, tb, ruleName);
            case Expression.Ignore ign -> parseIgnore(ctx, ign, ruleName);
            case Expression.Capture cap -> parseCapture(ctx, cap, ruleName);
            case Expression.CaptureScope cs -> parseCaptureScope(ctx, cs, ruleName);
            case Expression.Dictionary dict -> parseDictionary(ctx, dict);
            case Expression.BackReference br -> parseBackReference(ctx, br);
            case Expression.Cut cut -> parseCut(ctx, cut);
            case Expression.Group grp -> parseExpression(ctx, grp.expression(), ruleName);
        };
    }

    // === Terminal Parsers ===

    private ParseResult parseLiteral(ParsingContext ctx, Expression.Literal lit) {
        var text = lit.text();
        if (ctx.remaining() < text.length()) {
            ctx.updateFurthest("'" + text + "'");
            return ParseResult.Failure.at(ctx.location(), "'" + text + "'");
        }

        var startLoc = ctx.location();
        for (int i = 0; i < text.length(); i++) {
            char expected = text.charAt(i);
            char actual = ctx.peek(i);
            if (lit.caseInsensitive()) {
                if (Character.toLowerCase(expected) != Character.toLowerCase(actual)) {
                    ctx.updateFurthest("'" + text + "'");
                    return ParseResult.Failure.at(ctx.location(), "'" + text + "'");
                }
            } else {
                if (expected != actual) {
                    ctx.updateFurthest("'" + text + "'");
                    return ParseResult.Failure.at(ctx.location(), "'" + text + "'");
                }
            }
        }

        // Consume the matched text
        for (int i = 0; i < text.length(); i++) {
            ctx.advance();
        }

        var span = ctx.spanFrom(startLoc);
        var node = new CstNode.Terminal(span, "", text, List.of(), List.of());
        return ParseResult.Success.of(node, ctx.location());
    }

    /**
     * Parse dictionary using Trie-based longest match.
     */
    private ParseResult parseDictionary(ParsingContext ctx, Expression.Dictionary dict) {
        var startLoc = ctx.location();
        var words = dict.words();
        var caseInsensitive = dict.caseInsensitive();

        // Build Trie and find longest match
        String longestMatch = null;
        int longestLen = 0;

        for (var word : words) {
            if (matchesWord(ctx, word, caseInsensitive)) {
                if (word.length() > longestLen) {
                    longestMatch = word;
                    longestLen = word.length();
                }
            }
        }

        if (longestMatch == null) {
            var expected = String.join(" | ", words.stream().map(w -> "'" + w + "'").toList());
            ctx.updateFurthest(expected);
            return ParseResult.Failure.at(ctx.location(), expected);
        }

        // Consume the matched text
        for (int i = 0; i < longestLen; i++) {
            ctx.advance();
        }

        var span = ctx.spanFrom(startLoc);
        var node = new CstNode.Terminal(span, "", longestMatch, List.of(), List.of());
        return ParseResult.Success.of(node, ctx.location());
    }

    /**
     * Check if word matches at current position.
     */
    private boolean matchesWord(ParsingContext ctx, String word, boolean caseInsensitive) {
        if (ctx.remaining() < word.length()) {
            return false;
        }
        for (int i = 0; i < word.length(); i++) {
            char expected = word.charAt(i);
            char actual = ctx.peek(i);
            if (caseInsensitive) {
                if (Character.toLowerCase(expected) != Character.toLowerCase(actual)) {
                    return false;
                }
            } else {
                if (expected != actual) {
                    return false;
                }
            }
        }
        return true;
    }

    private ParseResult parseCharClass(ParsingContext ctx, Expression.CharClass cc) {
        if (ctx.isAtEnd()) {
            ctx.updateFurthest("[" + cc.pattern() + "]");
            return ParseResult.Failure.at(ctx.location(), "character");
        }

        var startLoc = ctx.location();
        char c = ctx.peek();
        boolean matches = matchesCharClass(c, cc.pattern(), cc.caseInsensitive());

        if (cc.negated()) {
            matches = !matches;
        }

        if (!matches) {
            ctx.updateFurthest("[" + (cc.negated() ? "^" : "") + cc.pattern() + "]");
            return ParseResult.Failure.at(ctx.location(), "character class");
        }

        ctx.advance();
        var span = ctx.spanFrom(startLoc);
        var node = new CstNode.Terminal(span, "", String.valueOf(c), List.of(), List.of());
        return ParseResult.Success.of(node, ctx.location());
    }

    private boolean matchesCharClass(char c, String pattern, boolean caseInsensitive) {
        char testChar = caseInsensitive ? Character.toLowerCase(c) : c;
        int i = 0;
        while (i < pattern.length()) {
            char start = pattern.charAt(i);
            if (start == '\\' && i + 1 < pattern.length()) {
                // Escape sequence
                char escaped = pattern.charAt(i + 1);
                int consumed = 2;
                char expected = switch (escaped) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '\\' -> '\\';
                    case ']' -> ']';
                    case '-' -> '-';
                    case 'x' -> {
                        // Hex escape (2 hex digits)
                        if (i + 4 <= pattern.length()) {
                            try {
                                var hex = pattern.substring(i + 2, i + 4);
                                consumed = 4;
                                yield (char) Integer.parseInt(hex, 16);
                            } catch (NumberFormatException e) {
                                yield 'x';
                            }
                        }
                        yield 'x';
                    }
                    case 'u' -> {
                        // Unicode escape (4 hex digits)
                        if (i + 6 <= pattern.length()) {
                            try {
                                var hex = pattern.substring(i + 2, i + 6);
                                consumed = 6;
                                yield (char) Integer.parseInt(hex, 16);
                            } catch (NumberFormatException e) {
                                yield 'u';
                            }
                        }
                        yield 'u';
                    }
                    default -> escaped;
                };
                if (caseInsensitive) {
                    expected = Character.toLowerCase(expected);
                }
                if (testChar == expected) {
                    return true;
                }
                i += consumed;
                continue;
            }

            // Check for range
            if (i + 2 < pattern.length() && pattern.charAt(i + 1) == '-') {
                char end = pattern.charAt(i + 2);
                if (caseInsensitive) {
                    start = Character.toLowerCase(start);
                    end = Character.toLowerCase(end);
                }
                if (testChar >= start && testChar <= end) {
                    return true;
                }
                i += 3;
            } else {
                if (caseInsensitive) {
                    start = Character.toLowerCase(start);
                }
                if (testChar == start) {
                    return true;
                }
                i++;
            }
        }
        return false;
    }

    private ParseResult parseAny(ParsingContext ctx, Expression.Any any) {
        if (ctx.isAtEnd()) {
            ctx.updateFurthest("any character");
            return ParseResult.Failure.at(ctx.location(), "any character");
        }

        var startLoc = ctx.location();
        char c = ctx.advance();
        var span = ctx.spanFrom(startLoc);
        var node = new CstNode.Terminal(span, "", String.valueOf(c), List.of(), List.of());
        return ParseResult.Success.of(node, ctx.location());
    }

    // === Combinator Parsers ===

    private ParseResult parseReference(ParsingContext ctx, Expression.Reference ref) {
        var ruleOpt = grammar.rule(ref.ruleName());
        if (ruleOpt.isEmpty()) {
            return ParseResult.Failure.at(ctx.location(), "rule '" + ref.ruleName() + "'");
        }
        return parseRule(ctx, ruleOpt.unwrap());
    }

    private ParseResult parseSequence(ParsingContext ctx, Expression.Sequence seq, String ruleName) {
        var startLoc = ctx.location();
        var children = new ArrayList<CstNode>();

        for (var element : seq.elements()) {
            // Skip whitespace between elements, but NOT before predicates
            // Predicates are used for boundary checking and must see actual characters
            if (!isPredicate(element)) {
                skipWhitespace(ctx);
            }

            var result = parseExpression(ctx, element, ruleName);
            if (result.isFailure()) {
                ctx.restoreLocation(startLoc);
                return result;
            }

            if (result instanceof ParseResult.Success success) {
                children.add(success.node());
            }
            // Ignored and PredicateSuccess don't add nodes
        }

        var span = ctx.spanFrom(startLoc);
        var node = new CstNode.NonTerminal(span, ruleName, children, List.of(), List.of());
        return ParseResult.Success.of(node, ctx.location());
    }

    private boolean isPredicate(Expression expr) {
        return switch (expr) {
            case Expression.And ignored -> true;
            case Expression.Not ignored -> true;
            case Expression.Group grp -> isPredicate(grp.expression());
            default -> false;
        };
    }

    private ParseResult parseChoice(ParsingContext ctx, Expression.Choice choice, String ruleName) {
        var startLoc = ctx.location();
        ParseResult lastFailure = null;

        for (var alt : choice.alternatives()) {
            var result = parseExpression(ctx, alt, ruleName);
            if (result.isSuccess()) {
                return result;
            }
            lastFailure = result;
            ctx.restoreLocation(startLoc);
        }

        return lastFailure != null
            ? lastFailure
            : ParseResult.Failure.at(ctx.location(), "one of alternatives");
    }

    private ParseResult parseZeroOrMore(ParsingContext ctx, Expression.ZeroOrMore zom, String ruleName) {
        var startLoc = ctx.location();
        var children = new ArrayList<CstNode>();

        while (true) {
            var beforeLoc = ctx.location();
            skipWhitespace(ctx);
            var result = parseExpression(ctx, zom.expression(), ruleName);

            if (result.isFailure()) {
                ctx.restoreLocation(beforeLoc);
                break;
            }

            if (result instanceof ParseResult.Success success) {
                children.add(success.node());
            }

            // Prevent infinite loop on zero-width match
            if (ctx.pos() == beforeLoc.offset()) {
                break;
            }
        }

        var span = ctx.spanFrom(startLoc);
        if (children.size() == 1) {
            return ParseResult.Success.of(children.getFirst(), ctx.location());
        }
        var node = new CstNode.NonTerminal(span, ruleName, children, List.of(), List.of());
        return ParseResult.Success.of(node, ctx.location());
    }

    private ParseResult parseOneOrMore(ParsingContext ctx, Expression.OneOrMore oom, String ruleName) {
        var startLoc = ctx.location();
        var children = new ArrayList<CstNode>();

        // First match is required
        var first = parseExpression(ctx, oom.expression(), ruleName);
        if (first.isFailure()) {
            return first;
        }
        if (first instanceof ParseResult.Success success) {
            children.add(success.node());
        }

        // Subsequent matches are optional
        while (true) {
            var beforeLoc = ctx.location();
            skipWhitespace(ctx);
            var result = parseExpression(ctx, oom.expression(), ruleName);

            if (result.isFailure()) {
                ctx.restoreLocation(beforeLoc);
                break;
            }

            if (result instanceof ParseResult.Success success) {
                children.add(success.node());
            }

            if (ctx.pos() == beforeLoc.offset()) {
                break;
            }
        }

        var span = ctx.spanFrom(startLoc);
        if (children.size() == 1) {
            return ParseResult.Success.of(children.getFirst(), ctx.location());
        }
        var node = new CstNode.NonTerminal(span, ruleName, children, List.of(), List.of());
        return ParseResult.Success.of(node, ctx.location());
    }

    private ParseResult parseOptional(ParsingContext ctx, Expression.Optional opt, String ruleName) {
        var startLoc = ctx.location();
        var result = parseExpression(ctx, opt.expression(), ruleName);

        if (result.isSuccess()) {
            return result;
        }

        // Optional always succeeds - return empty node on no match
        ctx.restoreLocation(startLoc);
        var span = SourceSpan.at(startLoc);
        var node = new CstNode.NonTerminal(span, ruleName, List.of(), List.of(), List.of());
        return ParseResult.Success.of(node, ctx.location());
    }

    private ParseResult parseRepetition(ParsingContext ctx, Expression.Repetition rep, String ruleName) {
        var startLoc = ctx.location();
        var children = new ArrayList<CstNode>();

        int count = 0;
        int max = rep.max().isPresent() ? rep.max().unwrap() : Integer.MAX_VALUE;

        while (count < max) {
            var beforeLoc = ctx.location();
            if (count > 0) {
                skipWhitespace(ctx);
            }
            var result = parseExpression(ctx, rep.expression(), ruleName);

            if (result.isFailure()) {
                ctx.restoreLocation(beforeLoc);
                break;
            }

            if (result instanceof ParseResult.Success success) {
                children.add(success.node());
            }
            count++;

            if (ctx.pos() == beforeLoc.offset()) {
                break;
            }
        }

        if (count < rep.min()) {
            ctx.restoreLocation(startLoc);
            return ParseResult.Failure.at(ctx.location(),
                "at least " + rep.min() + " repetitions");
        }

        var span = ctx.spanFrom(startLoc);
        if (children.size() == 1) {
            return ParseResult.Success.of(children.getFirst(), ctx.location());
        }
        var node = new CstNode.NonTerminal(span, ruleName, children, List.of(), List.of());
        return ParseResult.Success.of(node, ctx.location());
    }

    // === Predicate Parsers ===

    private ParseResult parseAnd(ParsingContext ctx, Expression.And and, String ruleName) {
        var startLoc = ctx.location();
        var result = parseExpression(ctx, and.expression(), ruleName);
        ctx.restoreLocation(startLoc); // Always restore - predicates don't consume

        if (result.isSuccess()) {
            return new ParseResult.PredicateSuccess(startLoc);
        }
        return result;
    }

    private ParseResult parseNot(ParsingContext ctx, Expression.Not not, String ruleName) {
        var startLoc = ctx.location();
        var result = parseExpression(ctx, not.expression(), ruleName);
        ctx.restoreLocation(startLoc); // Always restore - predicates don't consume

        if (result.isSuccess()) {
            return ParseResult.Failure.at(startLoc, "not " + describeExpression(not.expression()));
        }
        return new ParseResult.PredicateSuccess(startLoc);
    }

    // === Special Parsers ===

    private ParseResult parseTokenBoundary(ParsingContext ctx, Expression.TokenBoundary tb, String ruleName) {
        var startLoc = ctx.location();
        var startPos = ctx.pos();

        // Disable whitespace skipping inside token boundary
        ctx.enterTokenBoundary();
        try {
            var result = parseExpression(ctx, tb.expression(), ruleName);
            if (result.isFailure()) {
                return result;
            }

            var endPos = ctx.pos();
            var text = ctx.substring(startPos, endPos);
            var span = ctx.spanFrom(startLoc);
            var node = new CstNode.Token(span, ruleName, text, List.of(), List.of());
            return ParseResult.Success.of(node, ctx.location());
        } finally {
            ctx.exitTokenBoundary();
        }
    }

    private ParseResult parseIgnore(ParsingContext ctx, Expression.Ignore ign, String ruleName) {
        var startLoc = ctx.location();
        var startPos = ctx.pos();

        var result = parseExpression(ctx, ign.expression(), ruleName);
        if (result.isFailure()) {
            return result;
        }

        var text = ctx.substring(startPos, ctx.pos());
        return new ParseResult.Ignored(ctx.location(), text);
    }

    private ParseResult parseCapture(ParsingContext ctx, Expression.Capture cap, String ruleName) {
        var startPos = ctx.pos();
        var result = parseExpression(ctx, cap.expression(), ruleName);

        if (result.isSuccess()) {
            var text = ctx.substring(startPos, ctx.pos());
            ctx.setCapture(cap.name(), text);
        }

        return result;
    }

    /**
     * Parse capture scope - isolates captures within the expression.
     */
    private ParseResult parseCaptureScope(ParsingContext ctx, Expression.CaptureScope cs, String ruleName) {
        var savedCaptures = ctx.saveCaptures();
        var result = parseExpression(ctx, cs.expression(), ruleName);
        ctx.restoreCaptures(savedCaptures);
        return result;
    }

    private ParseResult parseBackReference(ParsingContext ctx, Expression.BackReference br) {
        var captured = ctx.getCapture(br.name());
        if (captured.isEmpty()) {
            return ParseResult.Failure.at(ctx.location(), "capture '" + br.name() + "'");
        }

        var text = captured.unwrap();
        if (ctx.remaining() < text.length()) {
            return ParseResult.Failure.at(ctx.location(), "'" + text + "'");
        }

        var startLoc = ctx.location();
        for (int i = 0; i < text.length(); i++) {
            if (ctx.peek(i) != text.charAt(i)) {
                return ParseResult.Failure.at(ctx.location(), "'" + text + "'");
            }
        }

        for (int i = 0; i < text.length(); i++) {
            ctx.advance();
        }

        var span = ctx.spanFrom(startLoc);
        var node = new CstNode.Terminal(span, "", text, List.of(), List.of());
        return ParseResult.Success.of(node, ctx.location());
    }

    private ParseResult parseCut(ParsingContext ctx, Expression.Cut cut) {
        // Cut commits to current choice - for now just succeed
        return new ParseResult.PredicateSuccess(ctx.location());
    }

    // === Helpers ===

    // Flag to prevent recursive whitespace skipping
    private boolean skippingWhitespace = false;

    private List<Trivia> skipWhitespace(ParsingContext ctx) {
        var trivia = new ArrayList<Trivia>();
        // Don't skip whitespace inside token boundaries or during whitespace parsing
        if (grammar.whitespace().isEmpty() || skippingWhitespace || ctx.inTokenBoundary()) {
            return trivia;
        }

        skippingWhitespace = true;
        try {
            var wsExpr = grammar.whitespace().unwrap();
            // Extract inner expression from ZeroOrMore/OneOrMore to match one element at a time
            var innerExpr = extractInnerExpression(wsExpr);

            while (!ctx.isAtEnd()) {
                var startLoc = ctx.location();
                var startPos = ctx.pos();
                var result = parseExpressionNoWs(ctx, innerExpr, "%whitespace");
                if (result.isFailure() || ctx.pos() == startPos) {
                    break;
                }
                // Collect trivia if enabled
                if (config.captureTrivia()) {
                    var text = ctx.substring(startPos, ctx.pos());
                    var span = ctx.spanFrom(startLoc);
                    trivia.add(classifyTrivia(span, text));
                }
            }
        } finally {
            skippingWhitespace = false;
        }
        return trivia;
    }

    /**
     * Extract inner expression from repetition operators (ZeroOrMore, OneOrMore).
     * This allows matching whitespace one element at a time for proper trivia collection.
     */
    private Expression extractInnerExpression(Expression expr) {
        return switch (expr) {
            case Expression.ZeroOrMore zom -> zom.expression();
            case Expression.OneOrMore oom -> oom.expression();
            case Expression.Optional opt -> opt.expression();
            default -> expr;
        };
    }

    /**
     * Classify trivia based on its content.
     */
    private Trivia classifyTrivia(SourceSpan span, String text) {
        if (text.startsWith("//")) {
            return new Trivia.LineComment(span, text);
        } else if (text.startsWith("/*")) {
            return new Trivia.BlockComment(span, text);
        } else {
            return new Trivia.Whitespace(span, text);
        }
    }

    // Parse expression without whitespace skipping (for whitespace rule itself)
    private ParseResult parseExpressionNoWs(ParsingContext ctx, Expression expr, String ruleName) {
        return switch (expr) {
            case Expression.Literal lit -> parseLiteral(ctx, lit);
            case Expression.CharClass cc -> parseCharClass(ctx, cc);
            case Expression.Any any -> parseAny(ctx, any);
            case Expression.Reference ref -> parseReference(ctx, ref);
            case Expression.Sequence seq -> parseSequenceNoWs(ctx, seq, ruleName);
            case Expression.Choice choice -> parseChoiceNoWs(ctx, choice, ruleName);
            case Expression.ZeroOrMore zom -> parseZeroOrMoreNoWs(ctx, zom, ruleName);
            case Expression.OneOrMore oom -> parseOneOrMoreNoWs(ctx, oom, ruleName);
            case Expression.Optional opt -> parseOptionalNoWs(ctx, opt, ruleName);
            case Expression.Repetition rep -> parseRepetitionNoWs(ctx, rep, ruleName);
            case Expression.And and -> parseAnd(ctx, and, ruleName);
            case Expression.Not not -> parseNot(ctx, not, ruleName);
            case Expression.TokenBoundary tb -> parseTokenBoundary(ctx, tb, ruleName);
            case Expression.Ignore ign -> parseIgnore(ctx, ign, ruleName);
            case Expression.Capture cap -> parseCapture(ctx, cap, ruleName);
            case Expression.CaptureScope cs -> parseCaptureScope(ctx, cs, ruleName);
            case Expression.Dictionary dict -> parseDictionary(ctx, dict);
            case Expression.BackReference br -> parseBackReference(ctx, br);
            case Expression.Cut cut -> parseCut(ctx, cut);
            case Expression.Group grp -> parseExpressionNoWs(ctx, grp.expression(), ruleName);
        };
    }

    private ParseResult parseSequenceNoWs(ParsingContext ctx, Expression.Sequence seq, String ruleName) {
        var startLoc = ctx.location();
        var children = new ArrayList<CstNode>();

        for (var element : seq.elements()) {
            var result = parseExpressionNoWs(ctx, element, ruleName);
            if (result.isFailure()) {
                ctx.restoreLocation(startLoc);
                return result;
            }
            if (result instanceof ParseResult.Success success) {
                children.add(success.node());
            }
        }

        var span = ctx.spanFrom(startLoc);
        var node = new CstNode.NonTerminal(span, ruleName, children, List.of(), List.of());
        return ParseResult.Success.of(node, ctx.location());
    }

    private ParseResult parseChoiceNoWs(ParsingContext ctx, Expression.Choice choice, String ruleName) {
        var startLoc = ctx.location();
        ParseResult lastFailure = null;

        for (var alt : choice.alternatives()) {
            var result = parseExpressionNoWs(ctx, alt, ruleName);
            if (result.isSuccess()) {
                return result;
            }
            lastFailure = result;
            ctx.restoreLocation(startLoc);
        }

        return lastFailure != null
            ? lastFailure
            : ParseResult.Failure.at(ctx.location(), "one of alternatives");
    }

    private ParseResult parseZeroOrMoreNoWs(ParsingContext ctx, Expression.ZeroOrMore zom, String ruleName) {
        var startLoc = ctx.location();
        var children = new ArrayList<CstNode>();

        while (true) {
            var beforeLoc = ctx.location();
            var result = parseExpressionNoWs(ctx, zom.expression(), ruleName);

            if (result.isFailure()) {
                ctx.restoreLocation(beforeLoc);
                break;
            }

            if (result instanceof ParseResult.Success success) {
                children.add(success.node());
            }

            if (ctx.pos() == beforeLoc.offset()) {
                break;
            }
        }

        var span = ctx.spanFrom(startLoc);
        if (children.size() == 1) {
            return ParseResult.Success.of(children.getFirst(), ctx.location());
        }
        var node = new CstNode.NonTerminal(span, ruleName, children, List.of(), List.of());
        return ParseResult.Success.of(node, ctx.location());
    }

    private ParseResult parseOneOrMoreNoWs(ParsingContext ctx, Expression.OneOrMore oom, String ruleName) {
        var startLoc = ctx.location();
        var children = new ArrayList<CstNode>();

        var first = parseExpressionNoWs(ctx, oom.expression(), ruleName);
        if (first.isFailure()) {
            return first;
        }
        if (first instanceof ParseResult.Success success) {
            children.add(success.node());
        }

        while (true) {
            var beforeLoc = ctx.location();
            var result = parseExpressionNoWs(ctx, oom.expression(), ruleName);

            if (result.isFailure()) {
                ctx.restoreLocation(beforeLoc);
                break;
            }

            if (result instanceof ParseResult.Success success) {
                children.add(success.node());
            }

            if (ctx.pos() == beforeLoc.offset()) {
                break;
            }
        }

        var span = ctx.spanFrom(startLoc);
        if (children.size() == 1) {
            return ParseResult.Success.of(children.getFirst(), ctx.location());
        }
        var node = new CstNode.NonTerminal(span, ruleName, children, List.of(), List.of());
        return ParseResult.Success.of(node, ctx.location());
    }

    private ParseResult parseOptionalNoWs(ParsingContext ctx, Expression.Optional opt, String ruleName) {
        var startLoc = ctx.location();
        var result = parseExpressionNoWs(ctx, opt.expression(), ruleName);

        if (result.isSuccess()) {
            return result;
        }

        ctx.restoreLocation(startLoc);
        var span = SourceSpan.at(startLoc);
        var node = new CstNode.NonTerminal(span, ruleName, List.of(), List.of(), List.of());
        return ParseResult.Success.of(node, ctx.location());
    }

    private ParseResult parseRepetitionNoWs(ParsingContext ctx, Expression.Repetition rep, String ruleName) {
        var startLoc = ctx.location();
        var children = new ArrayList<CstNode>();

        int count = 0;
        int max = rep.max().isPresent() ? rep.max().unwrap() : Integer.MAX_VALUE;

        while (count < max) {
            var beforeLoc = ctx.location();
            var result = parseExpressionNoWs(ctx, rep.expression(), ruleName);

            if (result.isFailure()) {
                ctx.restoreLocation(beforeLoc);
                break;
            }

            if (result instanceof ParseResult.Success success) {
                children.add(success.node());
            }
            count++;

            if (ctx.pos() == beforeLoc.offset()) {
                break;
            }
        }

        if (count < rep.min()) {
            ctx.restoreLocation(startLoc);
            return ParseResult.Failure.at(ctx.location(),
                "at least " + rep.min() + " repetitions");
        }

        var span = ctx.spanFrom(startLoc);
        if (children.size() == 1) {
            return ParseResult.Success.of(children.getFirst(), ctx.location());
        }
        var node = new CstNode.NonTerminal(span, ruleName, children, List.of(), List.of());
        return ParseResult.Success.of(node, ctx.location());
    }

    private CstNode wrapWithRuleName(CstNode node, String ruleName, List<Trivia> leadingTrivia) {
        return switch (node) {
            case CstNode.Terminal t -> new CstNode.Terminal(
                t.span(), ruleName, t.text(), leadingTrivia, t.trailingTrivia()
            );
            case CstNode.NonTerminal nt -> new CstNode.NonTerminal(
                nt.span(), ruleName, nt.children(), leadingTrivia, nt.trailingTrivia()
            );
            case CstNode.Token tok -> new CstNode.Token(
                tok.span(), ruleName, tok.text(), leadingTrivia, tok.trailingTrivia()
            );
        };
    }

    /**
     * Attach trailing trivia to a node.
     */
    private CstNode attachTrailingTrivia(CstNode node, List<Trivia> trailingTrivia) {
        if (trailingTrivia.isEmpty()) {
            return node;
        }
        return switch (node) {
            case CstNode.Terminal t -> new CstNode.Terminal(
                t.span(), t.rule(), t.text(), t.leadingTrivia(), trailingTrivia
            );
            case CstNode.NonTerminal nt -> new CstNode.NonTerminal(
                nt.span(), nt.rule(), nt.children(), nt.leadingTrivia(), trailingTrivia
            );
            case CstNode.Token tok -> new CstNode.Token(
                tok.span(), tok.rule(), tok.text(), tok.leadingTrivia(), trailingTrivia
            );
        };
    }

    private String describeExpression(Expression expr) {
        return switch (expr) {
            case Expression.Literal lit -> "'" + lit.text() + "'";
            case Expression.CharClass cc -> "[" + cc.pattern() + "]";
            case Expression.Any any -> ".";
            case Expression.Reference ref -> ref.ruleName();
            default -> "expression";
        };
    }

    private AstNode toAst(CstNode cst) {
        return switch (cst) {
            case CstNode.Terminal t -> new AstNode.Terminal(
                t.span(), t.rule(), t.text(), Option.none()
            );
            case CstNode.Token tok -> new AstNode.Terminal(
                tok.span(), tok.rule(), tok.text(), Option.none()
            );
            case CstNode.NonTerminal nt -> new AstNode.NonTerminal(
                nt.span(), nt.rule(),
                nt.children().stream().map(this::toAst).toList(),
                Option.none()
            );
        };
    }
}
