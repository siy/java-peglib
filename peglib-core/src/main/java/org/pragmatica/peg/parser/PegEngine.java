package org.pragmatica.peg.parser;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.action.Action;
import org.pragmatica.peg.action.ActionCompiler;
import org.pragmatica.peg.action.Actions;
import org.pragmatica.peg.action.RuleId;
import org.pragmatica.peg.action.SemanticValues;
import org.pragmatica.peg.error.Diagnostic;
import org.pragmatica.peg.error.ParseError;
import org.pragmatica.peg.error.RecoveryStrategy;
import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.Rule;
import org.pragmatica.peg.grammar.analysis.ExpressionShape;
import org.pragmatica.peg.grammar.analysis.FirstCharAnalysis;
import org.pragmatica.peg.tree.AstNode;
import org.pragmatica.peg.tree.CstNode;
import org.pragmatica.peg.tree.SourceLocation;
import org.pragmatica.peg.tree.SourceSpan;
import org.pragmatica.peg.tree.Trivia;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PEG parsing engine - interprets Grammar to parse input text.
 */
public final class PegEngine implements Parser {
    private final Grammar grammar;
    private final ParserConfig config;
    private final Map<String, Action> actions;

    // Phase-1 optimization: cached first-char set for skipWhitespace fast-path (§6.6).
    // Present iff the grammar has a %whitespace rule whose shape is analyzable.
    // Unconditional-on for the interpreter; no flag gates this.
    private final Option<Set<Character>> whitespaceFirstChars;

    // 0.2.9: direct-left-recursive rule names. Empty for grammars without LR
    // (hot path: no extra work per rule entry). When non-empty, parseRule()
    // dispatches into the Warth seed-and-grow loop for these rules instead of
    // the regular single-pass path.
    private final Set<String> leftRecursiveRules;

    // Phase-1 optimization: per-engine caches for the quoted "'text'" and "[...]" / "[^...]"
    // expected-message strings (§6.5). The String concatenation "'" + text + "'" allocates a
    // StringBuilder + final String on every literal/char-class failure — in the hot backtracking
    // path this happens ~150k times parsing the 1,900-LOC Java fixture. The set of distinct keys
    // is bounded by the grammar. Caching only the string (not a full Failure record) keeps
    // Failure.location() accurate per call site; the allocation win is eliding the concat.
    private final Map<String, String> literalFailureMessageCache = new ConcurrentHashMap<>();
    private final Map<String, String> charClassFailureMessageCache = new ConcurrentHashMap<>();

    // 0.2.4: suggestion vocabulary computed once at engine construction from
    // grammar rules listed under %suggest. Empty when the directive is absent
    // so hot paths can short-circuit.
    private final List<String> suggestionVocabulary;

    private PegEngine(Grammar grammar, ParserConfig config, Map<String, Action> actions) {
        this.grammar = grammar;
        this.config = config;
        this.actions = Map.copyOf(actions);
        this.whitespaceFirstChars = computeWhitespaceFirstChars(grammar);
        this.suggestionVocabulary = computeSuggestionVocabulary(grammar);
        this.leftRecursiveRules = grammar.leftRecursiveRules();
    }

    private static List<String> computeSuggestionVocabulary(Grammar grammar) {
        if (grammar.suggestRules()
                   .isEmpty()) {
            return List.of();
        }
        var vocab = new ArrayList<String>();
        for (var ruleName : grammar.suggestRules()) {
            grammar.rule(ruleName)
                   .onPresent(rule -> collectLiterals(rule.expression(),
                                                      vocab));
        }
        return List.copyOf(vocab);
    }

    /**
     * Recursively collect all {@link Expression.Literal} alternatives reachable
     * from the given expression. Walks choice/sequence/group/optional/etc. so a
     * rule like {@code Keyword <- 'class' / 'interface' / 'enum'} yields
     * {@code [class, interface, enum]}. Each word is added at most once.
     */
    private static void collectLiterals(Expression expr, List<String> out) {
        switch (expr) {
            case Expression.Literal lit -> {
                if (!out.contains(lit.text())) {
                    out.add(lit.text());
                }
            }
            case Expression.Dictionary dict -> {
                for (var w : dict.words()) {
                    if (!out.contains(w)) {
                        out.add(w);
                    }
                }
            }
            case Expression.Choice c -> c.alternatives()
                                         .forEach(e -> collectLiterals(e, out));
            case Expression.Sequence s -> s.elements()
                                           .forEach(e -> collectLiterals(e, out));
            case Expression.Group g -> collectLiterals(g.expression(), out);
            case Expression.Optional o -> collectLiterals(o.expression(), out);
            case Expression.ZeroOrMore zm -> collectLiterals(zm.expression(), out);
            case Expression.OneOrMore om -> collectLiterals(om.expression(), out);
            case Expression.Repetition r -> collectLiterals(r.expression(), out);
            case Expression.TokenBoundary tb -> collectLiterals(tb.expression(), out);
            case Expression.Ignore ig -> collectLiterals(ig.expression(), out);
            case Expression.Capture cap -> collectLiterals(cap.expression(), out);
            case Expression.CaptureScope cs -> collectLiterals(cs.expression(), out);
            default -> {}
        }
    }

    private static Option<Set<Character>> computeWhitespaceFirstChars(Grammar grammar) {
        if (grammar.whitespace()
                   .isEmpty()) {
            return Option.none();
        }
        var inner = ExpressionShape.extractInnerExpression(grammar.whitespace()
                                                                  .unwrap());
        return FirstCharAnalysis.whitespaceFirstChars(grammar, inner);
    }

    public static Result<PegEngine> create(Grammar grammar, ParserConfig config) {
        var configCheck = validateConfig(grammar, config);
        if (configCheck.isFailure()) {
            return configCheck.map(unused -> (PegEngine) null);
        }
        // Compile all actions in the grammar
        var compiler = ActionCompiler.actionCompiler();
        return compiler.compileGrammar(grammar)
                       .map(actions -> new PegEngine(grammar, config, actions));
    }

    /**
     * 0.2.6 — create a parser whose rule-action dispatch table is built from
     * programmatically attached lambdas in {@link Actions}, overlaid on top of
     * any inline grammar actions. Lambda-attached rules override their inline
     * counterparts.
     */
    public static Result<PegEngine> create(Grammar grammar, ParserConfig config, Actions lambdaActions) {
        var configCheck = validateConfig(grammar, config);
        if (configCheck.isFailure()) {
            return configCheck.map(unused -> (PegEngine) null);
        }
        var compiler = ActionCompiler.actionCompiler();
        return compiler.compileGrammar(grammar)
                       .map(inlineActions -> new PegEngine(grammar,
                                                           config,
                                                           mergeActions(grammar, inlineActions, lambdaActions)));
    }

    /**
     * 0.4.0 — symmetric with {@link #create(Grammar, ParserConfig)}: returns
     * {@code Result<PegEngine>} so configuration errors flow through the same
     * monadic channel instead of throwing. Used by CST-only callers that
     * deliberately skip action compilation.
     */
    public static Result<PegEngine> createWithoutActions(Grammar grammar, ParserConfig config) {
        return validateConfig(grammar, config)
               .map(g -> new PegEngine(g,
                                       config,
                                       Map.of()));
    }

    /**
     * 0.2.9 — validate the runtime configuration against the grammar. Currently
     * enforces the rule that a left-recursive rule cannot appear in
     * {@link ParserConfig#packratSkipRules()} when
     * {@link ParserConfig#selectivePackrat()} is enabled: LR rules require the
     * packrat cache to hold the Warth seed during growth, so skipping the
     * cache would break correctness.
     */
    private static Result<Grammar> validateConfig(Grammar grammar, ParserConfig config) {
        if (!config.selectivePackrat() || config.packratSkipRules()
                                                .isEmpty()) {
            return Result.success(grammar);
        }
        var lrRules = grammar.leftRecursiveRules();
        if (lrRules.isEmpty()) {
            return Result.success(grammar);
        }
        for (var skip : config.packratSkipRules()) {
            if (lrRules.contains(skip)) {
                return new ParseError.SemanticError(
                SourceLocation.START, "rule '" + skip + "' is left-recursive; cannot be in packratSkipRules").result();
            }
        }
        return Result.success(grammar);
    }

    /**
     * Merge inline-compiled actions with programmatic lambda actions. Lambdas
     * win when both are attached for the same rule. The lambda is wrapped in an
     * {@link Action} adapter — {@code Action} is a {@code @FunctionalInterface}
     * over {@code SemanticValues}, same as {@code Function<SemanticValues,Object>}.
     */
    private static Map<String, Action> mergeActions(Grammar grammar,
                                                    Map<String, Action> inlineActions,
                                                    Actions lambdaActions) {
        if (lambdaActions.isEmpty()) {
            return inlineActions;
        }
        var merged = new HashMap<>(inlineActions);
        for (var rule : grammar.rules()) {
            lambdaActions.get(rule.name())
                         .onPresent(lambda -> merged.put(rule.name(),
                                                         lambda::apply));
        }
        return merged;
    }

    @Override
    public Result<CstNode> parseCst(String input) {
        var startRule = grammar.effectiveStartRule();
        if (startRule.isEmpty()) {
            return new ParseError.SemanticError(
            SourceLocation.START, "No start rule defined in grammar").result();
        }
        return parseCst(input,
                        startRule.unwrap()
                                 .name());
    }

    @Override
    public Result<CstNode> parseCst(String input, String startRule) {
        var ruleOpt = grammar.rule(startRule);
        if (ruleOpt.isEmpty()) {
            return new ParseError.SemanticError(
            SourceLocation.START, "Unknown rule: " + startRule).result();
        }
        var ctx = ParsingContext.create(input, grammar, config);
        ctx.setSuggestionVocabulary(suggestionVocabulary);
        var result = parseRule(ctx, ruleOpt.unwrap());
        if (result.isFailure()) {
            return buildParseError(result, ctx, input)
                   .result();
        }
        // Capture trailing trivia
        var trailingTrivia = skipWhitespace(ctx);
        // Check if we consumed all input
        if (!ctx.isAtEnd()) {
            return new ParseError.UnexpectedInput(
            ctx.location(),
            String.valueOf(ctx.peek()),
            "end of input").result();
        }
        var success = (ParseResult.Success) result;
        // Attach trailing trivia to root node
        var rootNode = attachTrailingTrivia(success.node(), trailingTrivia);
        return Result.success(rootNode);
    }

    @Override
    public Result<AstNode> parseAst(String input) {
        return parseCst(input)
               .map(this::toAst);
    }

    @Override
    public Result<AstNode> parseAst(String input, String startRule) {
        return parseCst(input, startRule)
               .map(this::toAst);
    }

    @Override
    public Result<Object> parse(String input) {
        var startRule = grammar.effectiveStartRule();
        if (startRule.isEmpty()) {
            return new ParseError.SemanticError(
            SourceLocation.START, "No start rule defined in grammar").result();
        }
        return parse(input,
                     startRule.unwrap()
                              .name());
    }

    @Override
    public Result<Object> parse(String input, String startRule) {
        var ruleOpt = grammar.rule(startRule);
        if (ruleOpt.isEmpty()) {
            return new ParseError.SemanticError(
            SourceLocation.START, "Unknown rule: " + startRule).result();
        }
        var ctx = ParsingContext.create(input, grammar, config);
        ctx.setSuggestionVocabulary(suggestionVocabulary);
        var result = parseRuleWithActions(ctx, ruleOpt.unwrap());
        if (result.isFailure()) {
            return buildParseError(result, ctx, input)
                   .result();
        }
        // Skip trailing whitespace before checking end
        skipWhitespace(ctx);
        if (!ctx.isAtEnd()) {
            return new ParseError.UnexpectedInput(
            ctx.location(),
            String.valueOf(ctx.peek()),
            "end of input").result();
        }
        var success = (ParseResult.Success) result;
        return Result.success(success.semanticValueOpt()
                                     .isPresent()
                              ? success.semanticValueOpt()
                                       .unwrap()
                              : success.node());
    }

    @Override
    public ParseResultWithDiagnostics parseCstWithDiagnostics(String input) {
        var startRule = grammar.effectiveStartRule();
        if (startRule.isEmpty()) {
            var diag = Diagnostic.error("no start rule defined in grammar",
                                        SourceSpan.sourceSpan(SourceLocation.START))
                                 .withTag("error.unexpected-input");
            return ParseResultWithDiagnostics.withErrors(Option.none(), List.of(diag), input);
        }
        return parseCstWithDiagnostics(input,
                                       startRule.unwrap()
                                                .name());
    }

    @Override
    public ParseResultWithDiagnostics parseCstWithDiagnostics(String input, String startRule) {
        var ruleOpt = grammar.rule(startRule);
        if (ruleOpt.isEmpty()) {
            var diag = Diagnostic.error("unknown rule: " + startRule,
                                        SourceSpan.sourceSpan(SourceLocation.START))
                                 .withTag("error.unexpected-input");
            return ParseResultWithDiagnostics.withErrors(Option.none(), List.of(diag), input);
        }
        var ctx = ParsingContext.create(input, grammar, config);
        ctx.setSuggestionVocabulary(suggestionVocabulary);
        // If not using advanced recovery, delegate to normal parsing
        if (config.recoveryStrategy() != RecoveryStrategy.ADVANCED) {
            var result = parseCst(input, startRule);
            return result.fold(
            cause -> toDiagnosticsResult((ParseError) cause, input),
            node -> ParseResultWithDiagnostics.success(node, input));
        }
        // Advanced recovery: try to parse fragments with error collection
        return parseWithRecovery(ctx, ruleOpt.unwrap(), input);
    }

    /**
     * 0.3.0 — parse a specific rule starting at a given offset. See
     * {@link Parser#parseRuleAt(Class, String, int)}.
     *
     * <p>The rule is resolved from {@code ruleId.name()} (defaults to
     * {@link Class#getSimpleName()}, matching the sanitized rule-name
     * convention used by {@link org.pragmatica.peg.generator.ParserGenerator}).
     * The parsing context is positioned at {@code offset} with a computed
     * line/column, then {@link #parseRule(ParsingContext, Rule)} is invoked.
     * Trailing whitespace after the rule is not consumed here — the
     * returned {@link PartialParse#endOffset()} is the absolute offset
     * immediately after the rule's matched span (including trailing trivia
     * attached by the rule itself).
     */
    @Override
    public Result<PartialParse> parseRuleAt(Class< ? extends RuleId> ruleId, String input, int offset) {
        if (ruleId == null) {
            return new ParseError.SemanticError(
            SourceLocation.START, "Rule id class is null").result();
        }
        if (input == null) {
            return new ParseError.SemanticError(
            SourceLocation.START, "Input is null").result();
        }
        if (offset < 0 || offset > input.length()) {
            return new ParseError.SemanticError(
            SourceLocation.START, "Offset " + offset + " out of range [0, " + input.length() + "]").result();
        }
        var ruleName = resolveRuleName(ruleId);
        var ruleOpt = grammar.rule(ruleName);
        if (ruleOpt.isEmpty()) {
            return new ParseError.SemanticError(
            SourceLocation.START, "Unknown rule for class " + ruleId.getSimpleName() + ": " + ruleName).result();
        }
        var ctx = ParsingContext.create(input, grammar, config);
        ctx.setSuggestionVocabulary(suggestionVocabulary);
        ctx.restoreLocation(computeLocation(input, offset));
        var result = parseRule(ctx, ruleOpt.unwrap());
        if (result.isFailure()) {
            return buildParseError(result, ctx, input)
                   .result();
        }
        var success = (ParseResult.Success) result;
        return Result.success(new PartialParse(success.node(), ctx.pos()));
    }

    /**
     * Resolve the sanitized rule name associated with a {@link RuleId} class.
     * Instantiates the class when it is a parameterless record so
     * {@link RuleId#name()} can override the default {@code Class#getSimpleName()}
     * lookup. Falls back to the simple class name when no zero-arg constructor
     * is available.
     */
    private static String resolveRuleName(Class< ? extends RuleId> ruleId) {
        try{
            var ctor = ruleId.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance()
                       .name();
        }catch (ReflectiveOperationException ignored) {
            return ruleId.getSimpleName();
        }
    }

    /**
     * Compute the {@link SourceLocation} at {@code offset} in {@code input} by
     * walking the prefix. Matches the line/column convention used elsewhere in
     * the engine (1-based, {@code '\n'} advances to the next line, column
     * resets to 1).
     */
    private static SourceLocation computeLocation(String input, int offset) {
        int line = 1;
        int column = 1;
        for (int i = 0; i < offset; i++ ) {
            if (input.charAt(i) == '\n') {
                line++ ;
                column = 1;
            }else {
                column++ ;
            }
        }
        return SourceLocation.sourceLocation(line, column, offset);
    }

    private ParseResultWithDiagnostics toDiagnosticsResult(ParseError parseError, String input) {
        var loc = parseError.location();
        var span = SourceSpan.sourceSpan(loc);
        var diag = Diagnostic.error("parse error", span)
                             .withLabel(parseError.message())
                             .withTag("error.expected");
        return ParseResultWithDiagnostics.withErrors(Option.none(), List.of(diag), input);
    }

    /**
     * 0.2.4 — attach a "did you mean 'X'?" help note when the token at the
     * failure position is identifier-like and the suggestion vocabulary has a
     * close match (Levenshtein distance ≤ 2). Returns the diagnostic unchanged
     * when no match is found or the vocabulary is empty (the common case).
     */
    private Diagnostic attachSuggestionHint(Diagnostic diag, String input, SourceLocation loc, ParsingContext ctx) {
        var vocab = ctx.suggestionVocabulary();
        if (vocab.isEmpty()) {
            return diag;
        }
        var word = readIdentifierLike(input, loc.offset());
        if (word.isEmpty()) {
            return diag;
        }
        var best = findBestSuggestion(word, vocab);
        return best.map(s -> diag.withHelp("did you mean '" + s + "'?"))
                   .or(diag);
    }

    private static String readIdentifierLike(String input, int offset) {
        var sb = new StringBuilder();
        int i = offset;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                sb.append(c);
                i++ ;
            }else {
                break;
            }
        }
        return sb.toString();
    }

    private static Option<String> findBestSuggestion(String word, List<String> vocab) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (var candidate : vocab) {
            // Skip exact matches — no hint needed (would be redundant).
            if (candidate.equals(word)) {
                return Option.none();
            }
            int d = levenshtein(word, candidate);
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        if (best != null && bestDistance <= 2) {
            return Option.some(best);
        }
        return Option.none();
    }

    /**
     * Iterative Levenshtein distance between two strings. Small-string
     * implementation — vocabularies here are expected to be grammar-scoped
     * and tiny (order of tens), so naive O(m*n) suffices.
     */
    private static int levenshtein(String a, String b) {
        int m = a.length();
        int n = b.length();
        if (m == 0) return n;
        if (n == 0) return m;
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++ ) {
            prev[j] = j;
        }
        for (int i = 1; i <= m; i++ ) {
            curr[0] = i;
            for (int j = 1; j <= n; j++ ) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1)
                           ? 0
                           : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            var tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[n];
    }

    /**
     * Parse with error recovery - continues after errors to collect multiple diagnostics.
     */
    private ParseResultWithDiagnostics parseWithRecovery(ParsingContext ctx, Rule startRule, String input) {
        var fragments = new ArrayList<CstNode>();
        while (!ctx.isAtEnd()) {
            // Skip leading whitespace
            var leadingTrivia = skipWhitespace(ctx);
            if (ctx.isAtEnd()) break;
            var startLoc = ctx.location();
            var result = parseRule(ctx, startRule);
            if (result instanceof ParseResult.Success success) {
                // Successfully parsed a fragment
                var node = success.node();
                if (!leadingTrivia.isEmpty()) {
                    node = wrapWithRuleName(node, startRule.name(), leadingTrivia);
                }
                fragments.add(node);
                ctx.exitRecovery();
            }else {
                // Parse failed - record error and skip to recovery point
                var failureExpected = switch (result) {
                    case ParseResult.Failure f -> f.expected();
                    case ParseResult.CutFailure cf -> cf.expected();
                    default -> "unknown";
                };
                var found = ctx.isAtEnd()
                            ? "EOF"
                            : String.valueOf(ctx.peek());
                var errorSpan = SourceSpan.sourceSpan(startLoc);
                // 0.2.4: tag every recovery diagnostic. CutFailure means a
                // commit point was passed — use the "unclosed" tag family so
                // tooling can distinguish it from plain unexpected input.
                boolean wasCut = result instanceof ParseResult.CutFailure;
                var tag = startRule.hasTag()
                          ? startRule.tag()
                                     .unwrap()
                          : (wasCut
                             ? "error.unclosed"
                             : "error.unexpected-input");
                var diag = Diagnostic.error("unexpected input", errorSpan)
                                     .withLabel("found '" + found + "'")
                                     .withHelp("expected " + failureExpected)
                                     .withTag(tag);
                // 0.2.4: suggestion vocabulary — if the token at failure is
                // identifier-like, Levenshtein-match against the vocab and
                // add a "did you mean" hint when distance ≤ 2.
                diag = attachSuggestionHint(diag, input, startLoc, ctx);
                ctx.addDiagnostic(diag);
                // Skip to recovery point
                ctx.enterRecovery();
                var skippedSpan = ctx.skipToRecoveryPoint();
                if (skippedSpan.length() > 0) {
                    var skippedText = input.substring(skippedSpan.start()
                                                                 .offset(),
                                                      skippedSpan.end()
                                                                 .offset());
                    var errorNode = new CstNode.Error(
                    skippedSpan, skippedText, failureExpected, leadingTrivia, List.of());
                    fragments.add(errorNode);
                }
                // Skip the recovery character itself (;, }, newline, etc.)
                if (!ctx.isAtEnd()) {
                    ctx.advance();
                }
            }
        }
        // Capture trailing trivia
        var trailingTrivia = skipWhitespace(ctx);
        // Build result
        Option<CstNode> rootNode;
        if (fragments.isEmpty()) {
            rootNode = Option.none();
        }else if (fragments.size() == 1) {
            rootNode = Option.some(attachTrailingTrivia(fragments.get(0), trailingTrivia));
        }else {
            // Multiple fragments - wrap in a root node
            var firstSpan = fragments.get(0)
                                     .span();
            var lastSpan = fragments.get(fragments.size() - 1)
                                    .span();
            var fullSpan = SourceSpan.sourceSpan(firstSpan.start(), lastSpan.end());
            rootNode = Option.some(new CstNode.NonTerminal(
            fullSpan, startRule.name(), fragments, List.of(), trailingTrivia));
        }
        return ParseResultWithDiagnostics.withErrors(rootNode, ctx.diagnostics(), input);
    }

    // === Rule Parsing ===
    private ParseResult parseRule(ParsingContext ctx, Rule rule) {
        // 0.2.9: dispatch to Warth seed-and-grow for direct left-recursive rules.
        // Non-LR rules take the original fast path unchanged.
        if (leftRecursiveRules.contains(rule.name())) {
            return parseRuleWithLeftRecursion(ctx, rule);
        }
        var startPos = ctx.pos();
        var startLoc = ctx.location();
        // Check packrat cache at START position. On a hit the cached BODY result
        // carries an empty leading-trivia list (set when the body was first
        // wrapped at line 667). Bug B fix: rebuild leading trivia on the hit
        // path so that the returned node is byte-for-byte equivalent to a fresh
        // parse — drain pending, run skipWhitespace at the same pre-WS position,
        // then jump pos to the cached body-end and re-attach leading trivia.
        var cachedEntry = ctx.getCachedEntryAt(rule.name(), startPos);
        if (cachedEntry.isPresent()) {
            var entry = cachedEntry.unwrap();
            var result = entry.result();
            if (result instanceof ParseResult.Success success) {
                var hitCarriedLeading = ctx.takePendingLeadingTrivia();
                var hitLocalTrivia = skipWhitespace(ctx);
                List<Trivia> hitRuleLeading = hitCarriedLeading.isEmpty()
                                              ? hitLocalTrivia
                                              : (hitLocalTrivia.isEmpty()
                                                 ? hitCarriedLeading
                                                 : concatTrivia(hitCarriedLeading, hitLocalTrivia));
                ctx.restoreLocation(success.endLocation());
                var hitNode = attachLeadingTrivia(success.node(), hitRuleLeading);
                return ParseResult.Success.success(hitNode, ctx.location());
            }
            return result;
        }
        // Claim pending-leading deposited by the caller (e.g. between-sibling
        // skipWhitespace in an enclosing sequence) and extend it with the
        // leading whitespace skipped at rule entry. This becomes the rule
        // wrapper's leadingTrivia; children of the body see an empty pending
        // buffer unless the body itself deposits more between its own siblings.
        var carriedLeading = ctx.takePendingLeadingTrivia();
        var localTrivia = skipWhitespace(ctx);
        List<Trivia> ruleLeading = carriedLeading.isEmpty()
                                   ? localTrivia
                                   : (localTrivia.isEmpty()
                                      ? carriedLeading
                                      : concatTrivia(carriedLeading, localTrivia));
        // 0.2.4: push %recover terminator so the rule's body recovers on that
        // literal rather than the global char-set. Popped in the finally block.
        boolean pushedRecover = false;
        if (rule.hasRecover()) {
            ctx.pushRecoveryOverride(rule.recover()
                                         .unwrap());
            pushedRecover = true;
        }
        ParseResult result = null;
        try{
            result = parseExpressionWithMode(ctx, rule.expression(), rule.name(), ParseMode.standard());
        } finally{
            // 0.3.5 (Phase 5) — capture failure override BEFORE pop. parseWithRecovery's
            // skipToRecoveryPoint runs after this method returns, by which point the
            // stack has been unwound; the pending field carries the override across.
            if (pushedRecover) {
                if (result == null || result.isFailure()) {
                    ctx.recordFailureRecoveryOverride(rule.recover()
                                                          .unwrap());
                }
                ctx.popRecoveryOverride();
            }
        }
        // 0.3.5 (Bug C') rule-exit trailing-trivia attribution: trivia consumed by
        // the body's inter-element skipWhitespace before a zero-width tail element
        // (empty ZoM/Optional) ends up in pending and isn't claimed by any child.
        // Attach it to the last child's trailingTrivia so reconstruction includes
        // it. Pos is NOT rewound — predicate combinators rely on pos being past
        // any whitespace already consumed.
        ParseResult resultForCache = result;
        CstNode bodyNode = null;
        if (result instanceof ParseResult.Success success) {
            bodyNode = success.node();
            var pendingAtExit = ctx.savePendingLeadingTrivia();
            if (!pendingAtExit.isEmpty()) {
                ctx.restorePendingLeadingTrivia(List.of());
                bodyNode = attachTrailingToTail(bodyNode, pendingAtExit);
                resultForCache = ParseResult.Success.success(bodyNode, success.endLocation());
            }
        }
        // Cache the result at START position
        ctx.cacheAt(rule.name(), startPos, resultForCache);
        if (result instanceof ParseResult.Success success) {
            // 0.3.5 (Phase 5) — clear pending override on success so a backtracked
            // alternative's recorded override does not leak into a later recovery cycle.
            ctx.clearPendingRecoveryOverride();
            var node = wrapWithRuleName(bodyNode, rule.name(), ruleLeading);
            return ParseResult.Success.success(node, ctx.location());
        }
        // Restore position and re-deposit the caller's pending so enclosing
        // backtracking combinators can roll it back to their own snapshots.
        ctx.restoreLocation(startLoc);
        if (!carriedLeading.isEmpty()) {
            ctx.appendPendingLeadingTrivia(carriedLeading);
        }
        // Use custom error message if available
        if (rule.hasErrorMessage()) {
            return ParseResult.Failure.failure(startLoc,
                                               rule.errorMessage()
                                                   .unwrap());
        }
        // 0.2.4: %expected semantic label — emit as the rule's failure message
        // and push into the furthest-failure tracker so diagnostics use the
        // label verbatim instead of the raw-token " or " join.
        if (rule.hasExpected()) {
            var label = rule.expected()
                            .unwrap();
            ctx.updateFurthest(label);
            return ParseResult.Failure.failure(startLoc, label);
        }
        return result;
    }

    /**
     * 0.2.9 — Warth-style seed-and-grow parsing for a directly left-recursive
     * rule. See Warth et al. 2008, "Packrat Parsers Can Support Left Recursion."
     *
     * <p>Algorithm:
     * <ol>
     *   <li>At rule entry, check the cache at pre-whitespace {@code startPos}.
     *       If an entry exists with {@code growing=true}, return the current
     *       seed (the recursive self-reference sees the current seed instead
     *       of re-entering the body).</li>
     *   <li>If no entry exists, seed the cache with a growing {@code Failure}
     *       and parse the body. On the first iteration the self-reference
     *       returns {@code Failure}, so the rule's non-recursive alternative
     *       (e.g. the base case) succeeds and becomes the initial seed.</li>
     *   <li>Iteratively reparse the body. After each successful iteration,
     *       if the new result consumed strictly more input than the previous
     *       seed, update the seed and loop; otherwise stop.</li>
     *   <li>Mark the final entry {@code growing=false} and return it.</li>
     * </ol>
     *
     * <p>Cut interaction: if a {@code ^} cut fires inside a growing iteration
     * (i.e. the body returns {@link ParseResult.CutFailure}), the current seed
     * is frozen as-is and no further growth is attempted. This matches the
     * documented 0.2.9 behaviour: cut-inside-LR forces the current seed final.
     */
    private ParseResult parseRuleWithLeftRecursion(ParsingContext ctx, Rule rule) {
        var startPos = ctx.pos();
        var startLoc = ctx.location();
        // Cache hit: either a settled entry (from a previous top-level call)
        // or a growing entry from an in-progress outer invocation (the self-
        // reference case). Bug B fix: settled-success hits rebuild leading
        // trivia (drain pending + skipWhitespace + reattach) so the returned
        // node is equivalent to a fresh parse. Growing-seed hits are the
        // self-reference path and return the seed unchanged — leading-trivia
        // is applied once at the outer-level settle path (line ~830).
        var cachedEntry = ctx.getCachedEntryAt(rule.name(), startPos);
        if (cachedEntry.isPresent()) {
            var entry = cachedEntry.unwrap();
            var result = entry.result();
            if (entry.growing()) {
                if (result.isSuccess()) {
                    var success = (ParseResult.Success) result;
                    ctx.restoreLocation(success.endLocation());
                }
                return result;
            }
            if (result instanceof ParseResult.Success success) {
                var hitCarriedLeading = ctx.takePendingLeadingTrivia();
                var hitLocalTrivia = skipWhitespace(ctx);
                List<Trivia> hitRuleLeading = hitCarriedLeading.isEmpty()
                                              ? hitLocalTrivia
                                              : (hitLocalTrivia.isEmpty()
                                                 ? hitCarriedLeading
                                                 : concatTrivia(hitCarriedLeading, hitLocalTrivia));
                ctx.restoreLocation(success.endLocation());
                var hitNode = attachLeadingTrivia(success.node(), hitRuleLeading);
                return ParseResult.Success.success(hitNode, ctx.location());
            }
            return result;
        }
        // Claim caller's pending-leading + our own leading whitespace. The
        // trivia snapshot lives across all growth iterations; each iteration
        // re-enters the body at the same post-whitespace position.
        var carriedLeading = ctx.takePendingLeadingTrivia();
        var localTrivia = skipWhitespace(ctx);
        List<Trivia> ruleLeading = carriedLeading.isEmpty()
                                   ? localTrivia
                                   : (localTrivia.isEmpty()
                                      ? carriedLeading
                                      : concatTrivia(carriedLeading, localTrivia));
        int bodyStartPos = ctx.pos();
        var bodyStartLoc = ctx.location();
        // Seed with Failure so the first recursive self-invocation sees a
        // failing base and falls through to the non-recursive alternative.
        int generation = 0;
        var seedFailure = ParseResult.Failure.failure(startLoc, "left-recursion seed");
        ctx.cacheEntryAt(rule.name(), startPos, ParsingContext.CacheEntry.seed(seedFailure, generation));
        boolean pushedRecover = false;
        if (rule.hasRecover()) {
            ctx.pushRecoveryOverride(rule.recover()
                                         .unwrap());
            pushedRecover = true;
        }
        ParseResult lastSeed = seedFailure;
        boolean cutFired = false;
        try{
            while (true) {
                // Rewind to the body-start position so each iteration parses
                // the same physical text with the latest seed in the cache.
                ctx.restoreLocation(bodyStartLoc);
                var iter = parseExpressionWithMode(ctx, rule.expression(), rule.name(), ParseMode.standard());
                // Cut-inside-LR: freeze the current seed and exit the loop.
                if (iter instanceof ParseResult.CutFailure) {
                    cutFired = true;
                    break;
                }
                if (iter.isFailure()) {
                    // Body no longer matches; keep the previous seed as final.
                    break;
                }
                var success = (ParseResult.Success) iter;
                int newEnd = success.endLocation()
                                    .offset();
                int prevEnd = lastSeed.isSuccess()
                              ? ((ParseResult.Success) lastSeed).endLocation()
                                             .offset()
                              : bodyStartPos;
                if (newEnd <= prevEnd) {
                    // Seed stabilized — stop growing.
                    break;
                }
                // Wrap the seed with the rule name so self-references observe
                // it as an Expr-named child (matching the non-LR wrapWithRuleName
                // behaviour at parseRule exit). Leading-trivia is consumed at
                // the outer level only; intermediate seeds carry an empty list.
                var wrapped = wrapWithRuleName(success.node(), rule.name(), List.of());
                var wrappedSeed = ParseResult.Success.success(wrapped, success.endLocation());
                lastSeed = wrappedSeed;
                generation++ ;
                ctx.cacheEntryAt(rule.name(), startPos, ParsingContext.CacheEntry.seed(lastSeed, generation));
            }
        } finally{
            // 0.3.5 (Phase 5) — same pattern as parseRule: capture failure override
            // before stack pop. lastSeed reflects the final LR settle state at this
            // point; treat any non-Success as a failure for recovery purposes.
            if (pushedRecover) {
                if (!lastSeed.isSuccess()) {
                    ctx.recordFailureRecoveryOverride(rule.recover()
                                                          .unwrap());
                }
                ctx.popRecoveryOverride();
            }
        }
        // Publish the settled result at the pre-whitespace position.
        ctx.cacheEntryAt(rule.name(), startPos, ParsingContext.CacheEntry.settled(lastSeed));
        if (lastSeed instanceof ParseResult.Success finalSuccess) {
            ctx.restoreLocation(finalSuccess.endLocation());
            // 0.3.5 (Phase 5) — clear pending override on success path.
            ctx.clearPendingRecoveryOverride();
            // Re-wrap at the outer level with leadingTrivia attached. The inner
            // wrap used an empty leading list for self-reference purposes.
            var outerNode = attachLeadingTrivia(finalSuccess.node(), ruleLeading);
            return ParseResult.Success.success(outerNode, ctx.location());
        }
        // Failure path — restore the original caller state and re-deposit
        // pending-leading so enclosing backtracking combinators can roll back.
        ctx.restoreLocation(startLoc);
        if (!carriedLeading.isEmpty()) {
            ctx.appendPendingLeadingTrivia(carriedLeading);
        }
        if (rule.hasErrorMessage()) {
            return ParseResult.Failure.failure(startLoc,
                                               rule.errorMessage()
                                                   .unwrap());
        }
        if (rule.hasExpected()) {
            var label = rule.expected()
                            .unwrap();
            ctx.updateFurthest(label);
            return ParseResult.Failure.failure(startLoc, label);
        }
        if (cutFired) {
            return ParseResult.CutFailure.cutFailure(startLoc, "cut inside left-recursive rule '" + rule.name() + "'");
        }
        return lastSeed;
    }

    /**
     * Parse rule with action execution.
     * Collects child semantic values and executes rule action if present.
     */
    private ParseResult parseRuleWithActions(ParsingContext ctx, Rule rule) {
        // 0.2.9: actions-on-LR is not supported in this release; delegate to
        // the CST seed-and-grow path so parsing terminates without collecting
        // child semantic values. Callers invoking parse() on a grammar with LR
        // rules will still get a CST-level result; action evaluation on LR
        // rules is scoped out.
        if (leftRecursiveRules.contains(rule.name())) {
            return parseRuleWithLeftRecursion(ctx, rule);
        }
        var startPos = ctx.pos();
        var startLoc = ctx.location();
        // Claim pending-leading (caller deposit) + this rule's leading ws. Same
        // semantics as parseRule; see TRIVIA-ATTRIBUTION.md.
        var carriedLeading = ctx.takePendingLeadingTrivia();
        var localTrivia = skipWhitespace(ctx);
        List<Trivia> ruleLeading = carriedLeading.isEmpty()
                                   ? localTrivia
                                   : (localTrivia.isEmpty()
                                      ? carriedLeading
                                      : concatTrivia(carriedLeading, localTrivia));
        var childValues = new ArrayList<Object>();
        var tokenCapture = new String[1];
        // Holder for token boundary capture
        // 0.2.4: push %recover terminator for the rule's body scope.
        boolean pushedRecover = false;
        if (rule.hasRecover()) {
            ctx.pushRecoveryOverride(rule.recover()
                                         .unwrap());
            pushedRecover = true;
        }
        ParseResult result = null;
        try{
            result = parseExpressionWithMode(ctx,
                                             rule.expression(),
                                             rule.name(),
                                             ParseMode.withActions(childValues, tokenCapture));
        } finally{
            // 0.3.5 (Phase 5) — capture failure override before stack pop. Same
            // rationale as parseRule: skipToRecoveryPoint runs after parseRule
            // returns, after the finally block has unwound the stack.
            if (pushedRecover) {
                if (result == null || result.isFailure()) {
                    ctx.recordFailureRecoveryOverride(rule.recover()
                                                          .unwrap());
                }
                ctx.popRecoveryOverride();
            }
        }
        if (result.isFailure()) {
            ctx.restoreLocation(startLoc);
            if (!carriedLeading.isEmpty()) {
                ctx.appendPendingLeadingTrivia(carriedLeading);
            }
            // Use custom error message if available
            if (rule.hasErrorMessage()) {
                return ParseResult.Failure.failure(startLoc,
                                                   rule.errorMessage()
                                                       .unwrap());
            }
            // 0.2.4: %expected semantic label — see parseRule for rationale.
            if (rule.hasExpected()) {
                var label = rule.expected()
                                .unwrap();
                ctx.updateFurthest(label);
                return ParseResult.Failure.failure(startLoc, label);
            }
            return result;
        }
        // 0.3.5 (Phase 5) — clear pending override on success path.
        ctx.clearPendingRecoveryOverride();
        var success = (ParseResult.Success) result;
        // Use token capture if available, otherwise full match
        var matchedText = Option.option(tokenCapture[0])
                                .or(ctx.substring(startPos,
                                                  ctx.pos()));
        var span = ctx.spanFrom(startLoc);
        // Execute action if present.
        // 0.4.0 — wrap action invocation in Result.lift to convert any thrown
        // exception into a ParseResult.Failure at the JBCT adapter boundary.
        var actionOpt = Option.option(actions.get(rule.name()));
        if (actionOpt.isPresent()) {
            var sv = SemanticValues.semanticValues(matchedText, span, childValues);
            return dispatchAction(actionOpt.unwrap(), sv, success.node(), rule.name(), ruleLeading, startLoc, ctx);
        }
        // No action - return node with child values as semantic value if any
        var node = wrapWithRuleName(success.node(), rule.name(), ruleLeading);
        if (!childValues.isEmpty()) {
            return ParseResult.Success.withValue(node,
                                                 ctx.location(),
                                                 childValues.size() == 1
                                                 ? childValues.getFirst()
                                                 : childValues);
        }
        return ParseResult.Success.success(node, ctx.location());
    }

    /**
     * 0.4.0 — JBCT adapter-boundary wrapper for grammar action dispatch. Any
     * exception thrown by the action body is captured by {@link Result#lift}
     * into a {@link ParseError.ActionError}, then projected into a
     * {@link ParseResult.Failure}. Success builds the wrapped node and
     * threads the action's return value as the semantic value.
     */
    private ParseResult dispatchAction(Action action,
                                       SemanticValues sv,
                                       CstNode successNode,
                                       String ruleName,
                                       List<Trivia> ruleLeading,
                                       SourceLocation startLoc,
                                       ParsingContext ctx) {
        return Result.lift(t -> (ParseError) new ParseError.ActionError(startLoc, ruleName, t),
                           () -> action.apply(sv))
                     .fold(cause -> actionFailure(startLoc,
                                                  ((ParseError.ActionError) cause).cause()),
                           value -> actionSuccess(successNode, ruleName, ruleLeading, ctx, value));
    }

    private static ParseResult actionFailure(SourceLocation startLoc, Throwable cause) {
        return ParseResult.Failure.failure(startLoc, "action error: " + cause.getMessage());
    }

    private ParseResult actionSuccess(CstNode successNode,
                                      String ruleName,
                                      List<Trivia> ruleLeading,
                                      ParsingContext ctx,
                                      Object value) {
        var node = wrapWithRuleName(successNode, ruleName, ruleLeading);
        return ParseResult.Success.withValue(node, ctx.location(), value);
    }

    private ParseResult parseReferenceWithActions(ParsingContext ctx, Expression.Reference ref, List<Object> values) {
        var ruleOpt = grammar.rule(ref.ruleName());
        if (ruleOpt.isEmpty()) {
            return ParseResult.Failure.failure(ctx.location(), "rule '" + ref.ruleName() + "'");
        }
        var result = parseRuleWithActions(ctx, ruleOpt.unwrap());
        if (result instanceof ParseResult.Success success && success.semanticValueOpt()
                                                                    .isPresent()) {
            values.add(success.semanticValueOpt()
                              .unwrap());
        }
        return result;
    }

    private ParseResult parseTokenBoundaryWithActions(ParsingContext ctx,
                                                      Expression.TokenBoundary tb,
                                                      String ruleName,
                                                      List<Object> values,
                                                      String[] tokenCapture) {
        var startLoc = ctx.location();
        var startPos = ctx.pos();
        // Disable whitespace skipping inside token boundary
        ctx.enterTokenBoundary();
        try{
            // Token boundary inner expressions don't propagate token capture
            var innerTokenCapture = new String[1];
            var result = parseExpressionWithMode(ctx,
                                                 tb.expression(),
                                                 ruleName,
                                                 ParseMode.withActions(values, innerTokenCapture));
            if (result.isFailure()) {
                return result;
            }
            var endPos = ctx.pos();
            var text = ctx.substring(startPos, endPos);
            // Set token capture so $0 returns this captured text
            tokenCapture[0] = text;
            var span = ctx.spanFrom(startLoc);
            var node = new CstNode.Token(span, ruleName, text, ctx.takePendingLeadingTrivia(), List.of());
            return ParseResult.Success.success(node, ctx.location());
        } finally{
            ctx.exitTokenBoundary();
        }
    }

    private ParseResult parseCaptureWithActions(ParsingContext ctx,
                                                Expression.Capture cap,
                                                String ruleName,
                                                List<Object> values,
                                                String[] tokenCapture) {
        var startPos = ctx.pos();
        var result = parseExpressionWithMode(ctx,
                                             cap.expression(),
                                             ruleName,
                                             ParseMode.withActions(values, tokenCapture));
        if (result.isSuccess()) {
            var text = ctx.substring(startPos, ctx.pos());
            ctx.setCapture(cap.name(), text);
        }
        return result;
    }

    /**
     * Parse capture scope with actions - isolates captures within the expression.
     */
    private ParseResult parseCaptureScopeWithActions(ParsingContext ctx,
                                                     Expression.CaptureScope cs,
                                                     String ruleName,
                                                     List<Object> values,
                                                     String[] tokenCapture) {
        var savedCaptures = ctx.saveCaptures();
        var result = parseExpressionWithMode(ctx, cs.expression(), ruleName, ParseMode.withActions(values, tokenCapture));
        ctx.restoreCaptures(savedCaptures);
        return result;
    }

    // === Terminal Parsers ===
    /**
     * Parse a literal expression. Phase-1 optimizations (unconditional in the interpreter):
     *   §6.2: hoist the quoted {@code "'text'"} message (via cached {@link ParseResult.Failure})
     *   §6.3: split match loop by caseInsensitive (no per-char branch)
     *   §6.4: bulk-advance pos/column on no-newline successful match
     *   §6.5: per-engine failure cache keyed on literal text
     *   §6.7: allocate endLoc once for both span and success
     */
    private ParseResult parseLiteral(ParsingContext ctx, Expression.Literal lit) {
        var text = lit.text();
        int len = text.length();
        if (ctx.remaining() < len) {
            return literalFailureAt(ctx, text);
        }
        var startLoc = ctx.location();
        if (lit.caseInsensitive()) {
            for (int i = 0; i < len; i++ ) {
                if (Character.toLowerCase(text.charAt(i)) != Character.toLowerCase(ctx.peek(i))) {
                    return literalFailureAt(ctx, text);
                }
            }
        }else {
            for (int i = 0; i < len; i++ ) {
                if (text.charAt(i) != ctx.peek(i)) {
                    return literalFailureAt(ctx, text);
                }
            }
        }
        advanceLiteral(ctx, text, len);
        var endLoc = ctx.location();
        var span = SourceSpan.sourceSpan(startLoc, endLoc);
        var node = new CstNode.Terminal(span, "", text, ctx.takePendingLeadingTrivia(), List.of());
        return new ParseResult.Success(node, endLoc, List.of(), Option.none());
    }

    /**
     * §6.5 — produce a literal failure for {@code text}, using the cached quoted
     * {@code "'text'"} message to avoid re-concatenating on every miss. Also calls
     * {@code updateFurthest} so diagnostics quality is unchanged.
     */
    private ParseResult literalFailureAt(ParsingContext ctx, String text) {
        var msg = literalFailureMessageCache.computeIfAbsent(text, t -> "'" + t + "'");
        ctx.updateFurthest(msg);
        return ParseResult.Failure.failure(ctx.location(), msg);
    }

    /**
     * §6.4 — advance the context by {@code len} characters. Bulk-update pos/column
     * when the matched text contains no newline; fall back to per-char advance otherwise.
     */
    private static void advanceLiteral(ParsingContext ctx, String text, int len) {
        if (text.indexOf('\n') < 0) {
            ctx.bulkAdvanceNoNewline(len);
        }else {
            for (int i = 0; i < len; i++ ) {
                ctx.advance();
            }
        }
    }

    /**
     * Parse dictionary using Trie-based longest match.
     * Phase-1 optimizations §6.4 + §6.7: bulk-advance on no-newline match; reuse endLoc.
     */
    private ParseResult parseDictionary(ParsingContext ctx, Expression.Dictionary dict) {
        var startLoc = ctx.location();
        var words = dict.words();
        var caseInsensitive = dict.caseInsensitive();
        // Find longest match
        Option<String> longestMatch = Option.none();
        int longestLen = 0;
        for (var word : words) {
            if (matchesWord(ctx, word, caseInsensitive)) {
                if (word.length() > longestLen) {
                    longestMatch = Option.some(word);
                    longestLen = word.length();
                }
            }
        }
        if (longestMatch.isEmpty()) {
            var expected = String.join(" | ",
                                       words.stream()
                                            .map(w -> "'" + w + "'")
                                            .toList());
            ctx.updateFurthest(expected);
            return ParseResult.Failure.failure(ctx.location(), expected);
        }
        var matched = longestMatch.unwrap();
        advanceLiteral(ctx, matched, longestLen);
        var endLoc = ctx.location();
        var span = SourceSpan.sourceSpan(startLoc, endLoc);
        var node = new CstNode.Terminal(span, "", matched, ctx.takePendingLeadingTrivia(), List.of());
        return new ParseResult.Success(node, endLoc, List.of(), Option.none());
    }

    /**
     * Check if word matches at current position.
     */
    private boolean matchesWord(ParsingContext ctx, String word, boolean caseInsensitive) {
        if (ctx.remaining() < word.length()) {
            return false;
        }
        for (int i = 0; i < word.length(); i++ ) {
            char expected = word.charAt(i);
            char actual = ctx.peek(i);
            if (caseInsensitive) {
                if (Character.toLowerCase(expected) != Character.toLowerCase(actual)) {
                    return false;
                }
            }else {
                if (expected != actual) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Parse a character class. Phase-1 optimizations (unconditional in the interpreter):
     *   §6.5: per-engine cache of the bracketed expected-message ({@code "[...]"} / {@code "[^...]"}),
     *         unifying the failure label (previously split between {@code "character"} /
     *         {@code "character class"} and the bracketed label on {@code updateFurthest}).
     *   §6.7: allocate endLoc once for both span and success
     */
    private ParseResult parseCharClass(ParsingContext ctx, Expression.CharClass cc) {
        if (ctx.isAtEnd()) {
            return charClassFailureAt(ctx, cc);
        }
        var startLoc = ctx.location();
        char c = ctx.peek();
        boolean matches = matchesCharClass(c, cc.pattern(), cc.caseInsensitive());
        if (cc.negated()) {
            matches = !matches;
        }
        if (!matches) {
            return charClassFailureAt(ctx, cc);
        }
        ctx.advance();
        var endLoc = ctx.location();
        var span = SourceSpan.sourceSpan(startLoc, endLoc);
        var node = new CstNode.Terminal(span, "", String.valueOf(c), ctx.takePendingLeadingTrivia(), List.of());
        return new ParseResult.Success(node, endLoc, List.of(), Option.none());
    }

    /**
     * §6.5 — produce a char-class failure with the bracketed label, using the
     * cache to elide repeat concatenation. The message is unified to the bracketed
     * label (matching the 0.2.2 generator behaviour), replacing the previous
     * {@code "character"} / {@code "character class"} split.
     */
    private ParseResult charClassFailureAt(ParsingContext ctx, Expression.CharClass cc) {
        var key = cc.negated()
                  ? "^" + cc.pattern()
                  : cc.pattern();
        var msg = charClassFailureMessageCache.computeIfAbsent(key,
                                                               k -> "[" + (cc.negated()
                                                                           ? "^"
                                                                           : "") + cc.pattern() + "]");
        ctx.updateFurthest(msg);
        return ParseResult.Failure.failure(ctx.location(), msg);
    }

    private boolean matchesCharClass(char c, String pattern, boolean caseInsensitive) {
        char testChar = caseInsensitive
                        ? Character.toLowerCase(c)
                        : c;
        int i = 0;
        while (i < pattern.length()) {
            char start = pattern.charAt(i);
            if (start == '\\' && i + 1 < pattern.length()) {
                // Escape sequence
                char escaped = pattern.charAt(i + 1);
                int consumed = 2;
                char expected = switch (escaped) {
                    case'n' -> '\n';
                    case'r' -> '\r';
                    case't' -> '\t';
                    case'\\' -> '\\';
                    case']' -> ']';
                    case'-' -> '-';
                    case'x' -> {
                        // Hex escape (2 hex digits)
                        if (i + 4 <= pattern.length()) {
                            try{
                                var hex = pattern.substring(i + 2, i + 4);
                                consumed = 4;
                                yield (char) Integer.parseInt(hex, 16);
                            } catch (NumberFormatException e) {
                                yield 'x';
                            }
                        }
                        yield 'x';
                    }
                    case'u' -> {
                        // Unicode escape (4 hex digits)
                        if (i + 6 <= pattern.length()) {
                            try{
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
            }else {
                if (caseInsensitive) {
                    start = Character.toLowerCase(start);
                }
                if (testChar == start) {
                    return true;
                }
                i++ ;
            }
        }
        return false;
    }

    /**
     * Parse the any-character expression. Phase-1 optimization §6.7: reuse endLoc.
     */
    private ParseResult parseAny(ParsingContext ctx, Expression.Any any) {
        if (ctx.isAtEnd()) {
            ctx.updateFurthest("any character");
            return ParseResult.Failure.failure(ctx.location(), "any character");
        }
        var startLoc = ctx.location();
        char c = ctx.advance();
        var endLoc = ctx.location();
        var span = SourceSpan.sourceSpan(startLoc, endLoc);
        var node = new CstNode.Terminal(span, "", String.valueOf(c), ctx.takePendingLeadingTrivia(), List.of());
        return new ParseResult.Success(node, endLoc, List.of(), Option.none());
    }

    // === Combinator Parsers ===
    private ParseResult parseReference(ParsingContext ctx, Expression.Reference ref) {
        var ruleOpt = grammar.rule(ref.ruleName());
        if (ruleOpt.isEmpty()) {
            return ParseResult.Failure.failure(ctx.location(), "rule '" + ref.ruleName() + "'");
        }
        return parseRule(ctx, ruleOpt.unwrap());
    }

    private boolean isPredicate(Expression expr) {
        return switch (expr) {
            case Expression.And ignored -> true;
            case Expression.Not ignored -> true;
            case Expression.Group grp -> isPredicate(grp.expression());
            default -> false;
        };
    }

    // === Predicate Parsers ===
    private ParseResult parseAnd(ParsingContext ctx, Expression.And and, String ruleName) {
        var startLoc = ctx.location();
        var entryPendingSnapshot = ctx.savePendingLeadingTrivia();
        var result = parseExpressionWithMode(ctx, and.expression(), ruleName, ParseMode.standard());
        ctx.restoreLocation(startLoc);
        // Always restore - predicates don't consume input nor trivia state.
        ctx.restorePendingLeadingTrivia(entryPendingSnapshot);
        if (result.isSuccess()) {
            return new ParseResult.PredicateSuccess(startLoc);
        }
        return result;
    }

    private ParseResult parseNot(ParsingContext ctx, Expression.Not not, String ruleName) {
        var startLoc = ctx.location();
        var entryPendingSnapshot = ctx.savePendingLeadingTrivia();
        var result = parseExpressionWithMode(ctx, not.expression(), ruleName, ParseMode.standard());
        ctx.restoreLocation(startLoc);
        // Always restore - predicates don't consume input nor trivia state.
        ctx.restorePendingLeadingTrivia(entryPendingSnapshot);
        if (result.isSuccess()) {
            return ParseResult.Failure.failure(startLoc, "not " + describeExpression(not.expression()));
        }
        return new ParseResult.PredicateSuccess(startLoc);
    }

    // === Special Parsers ===
    private ParseResult parseTokenBoundary(ParsingContext ctx, Expression.TokenBoundary tb, String ruleName) {
        var startLoc = ctx.location();
        var startPos = ctx.pos();
        // Disable whitespace skipping inside token boundary
        ctx.enterTokenBoundary();
        try{
            var result = parseExpressionWithMode(ctx, tb.expression(), ruleName, ParseMode.standard());
            if (result.isFailure()) {
                return result;
            }
            var endPos = ctx.pos();
            var text = ctx.substring(startPos, endPos);
            var span = ctx.spanFrom(startLoc);
            var node = new CstNode.Token(span, ruleName, text, ctx.takePendingLeadingTrivia(), List.of());
            return ParseResult.Success.success(node, ctx.location());
        } finally{
            ctx.exitTokenBoundary();
        }
    }

    private ParseResult parseIgnore(ParsingContext ctx, Expression.Ignore ign, String ruleName) {
        var startLoc = ctx.location();
        var startPos = ctx.pos();
        var result = parseExpressionWithMode(ctx, ign.expression(), ruleName, ParseMode.standard());
        if (result.isFailure()) {
            return result;
        }
        var text = ctx.substring(startPos, ctx.pos());
        return new ParseResult.Ignored(ctx.location(), text);
    }

    private ParseResult parseCapture(ParsingContext ctx, Expression.Capture cap, String ruleName) {
        var startPos = ctx.pos();
        var result = parseExpressionWithMode(ctx, cap.expression(), ruleName, ParseMode.standard());
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
        var result = parseExpressionWithMode(ctx, cs.expression(), ruleName, ParseMode.standard());
        ctx.restoreCaptures(savedCaptures);
        return result;
    }

    private ParseResult parseBackReference(ParsingContext ctx, Expression.BackReference br) {
        var captured = ctx.getCapture(br.name());
        if (captured.isEmpty()) {
            return ParseResult.Failure.failure(ctx.location(), "capture '" + br.name() + "'");
        }
        var text = captured.unwrap();
        if (ctx.remaining() < text.length()) {
            return ParseResult.Failure.failure(ctx.location(), "'" + text + "'");
        }
        var startLoc = ctx.location();
        for (int i = 0; i < text.length(); i++ ) {
            if (ctx.peek(i) != text.charAt(i)) {
                return ParseResult.Failure.failure(ctx.location(), "'" + text + "'");
            }
        }
        for (int i = 0; i < text.length(); i++ ) {
            ctx.advance();
        }
        var span = ctx.spanFrom(startLoc);
        var node = new CstNode.Terminal(span, "", text, ctx.takePendingLeadingTrivia(), List.of());
        return ParseResult.Success.success(node, ctx.location());
    }

    private ParseResult parseCut(ParsingContext ctx, Expression.Cut cut) {
        // Cut commits to current choice - for now just succeed
        return new ParseResult.PredicateSuccess(ctx.location());
    }

    // === Helpers ===
    /**
     * Skip whitespace/trivia. Phase-1 optimization §6.6: when the grammar's
     * {@code %whitespace} rule has an analyzable first-char set, short-circuit
     * when the current char cannot begin trivia. This elides the ArrayList and
     * mini-PEG loop setup on every rule entry for the common "no trivia here" case.
     */
    private List<Trivia> skipWhitespace(ParsingContext ctx) {
        // Don't skip whitespace inside token boundaries or during whitespace parsing
        if (grammar.whitespace()
                   .isEmpty() || ctx.isSkippingWhitespace() || ctx.inTokenBoundary()) {
            return List.of();
        }
        // §6.6 fast-path: if the current char cannot begin any whitespace-rule
        // alternative, return the shared empty list without any allocation.
        if (whitespaceFirstChars.isPresent() && !ctx.isAtEnd()) {
            var firstChars = whitespaceFirstChars.unwrap();
            if (!firstChars.contains(ctx.peek())) {
                return List.of();
            }
        }
        var trivia = new ArrayList<Trivia>();
        ctx.enterWhitespaceSkip();
        try{
            var wsExpr = grammar.whitespace()
                                .unwrap();
            // Extract inner expression from ZeroOrMore/OneOrMore to match one element at a time
            var innerExpr = extractInnerExpression(wsExpr);
            while (!ctx.isAtEnd()) {
                var startLoc = ctx.location();
                var startPos = ctx.pos();
                var result = parseExpressionWithMode(ctx, innerExpr, "%whitespace", ParseMode.noWhitespace());
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
        } finally{
            ctx.exitWhitespaceSkip();
        }
        return trivia;
    }

    /**
     * Extract inner expression from repetition operators (ZeroOrMore, OneOrMore,
     * Optional). Delegates to {@link ExpressionShape#extractInnerExpression} so
     * both the interpreter and the generator agree on the shape.
     */
    private Expression extractInnerExpression(Expression expr) {
        return ExpressionShape.extractInnerExpression(expr);
    }

    /**
     * Build parse error from failure result with accurate position information.
     */
    private ParseError buildParseError(ParseResult result, ParsingContext ctx, String input) {
        var failureExpected = switch (result) {
            case ParseResult.Failure f -> f.expected();
            case ParseResult.CutFailure cf -> cf.expected();
            default -> "unknown";
        };
        // Use furthest location for better error position after backtracking
        var furthestLoc = ctx.furthestLocation();
        var found = ctx.furthestPos() >= input.length()
                    ? "end of input"
                    : String.valueOf(input.charAt(ctx.furthestPos()));
        // Prefer custom error message from failure, fall back to furthest expected
        var expected = !failureExpected.startsWith("'") && !failureExpected.startsWith("[") && !failureExpected.startsWith("rule ") && !failureExpected.equals("any character") && !failureExpected.startsWith("not ") && !failureExpected.startsWith("one of")
                       ? failureExpected
                       : ctx.furthestExpected();
        return new ParseError.UnexpectedInput(furthestLoc, found, expected);
    }

    /**
     * Classify trivia based on its content.
     */
    private Trivia classifyTrivia(SourceSpan span, String text) {
        if (text.startsWith("//")) {
            return new Trivia.LineComment(span, text);
        }else if (text.startsWith("/*")) {
            return new Trivia.BlockComment(span, text);
        }else {
            return new Trivia.Whitespace(span, text);
        }
    }

    // === Unified Parsing Methods (with ParseMode) ===
    /**
     * Unified sequence parser - consolidates parseSequence, parseSequenceWithActions, parseSequenceNoWs.
     */
    private ParseResult parseSequenceWithMode(ParsingContext ctx,
                                              Expression.Sequence seq,
                                              String ruleName,
                                              ParseMode mode) {
        var startLoc = ctx.location();
        var pendingSnapshot = ctx.savePendingLeadingTrivia();
        var children = new ArrayList<CstNode>();
        boolean cutEncountered = false;
        for (var element : seq.elements()) {
            // Skip whitespace between elements, but NOT before predicates. The
            // captured trivia is appended to the pending-leading buffer so the
            // following element (leaf or rule) can claim it.
            if (mode.shouldSkipWhitespace() && !isPredicate(element)) {
                ctx.appendPendingLeadingTrivia(skipWhitespace(ctx));
            }
            var result = parseExpressionWithMode(ctx, element, ruleName, mode);
            // Handle failures
            if (result instanceof ParseResult.CutFailure) {
                // Propagate CutFailure immediately
                ctx.restoreLocation(startLoc);
                ctx.restorePendingLeadingTrivia(pendingSnapshot);
                return result;
            }
            if (result instanceof ParseResult.Failure failure) {
                ctx.restoreLocation(startLoc);
                ctx.restorePendingLeadingTrivia(pendingSnapshot);
                // If cut was encountered, return CutFailure to prevent backtracking
                if (cutEncountered) {
                    return ParseResult.CutFailure.cutFailure(failure.location(), failure.expected());
                }
                return result;
            }
            // Track if we've passed a cut point
            if (element instanceof Expression.Cut) {
                cutEncountered = true;
            }
            if (result instanceof ParseResult.Success success) {
                children.add(success.node());
            }
        }
        var span = ctx.spanFrom(startLoc);
        var node = new CstNode.NonTerminal(span, ruleName, children, List.of(), List.of());
        return ParseResult.Success.success(node, ctx.location());
    }

    /**
     * Unified choice parser - consolidates parseChoice, parseChoiceWithActions, parseChoiceNoWs.
     */
    private ParseResult parseChoiceWithMode(ParsingContext ctx,
                                            Expression.Choice choice,
                                            String ruleName,
                                            ParseMode mode) {
        var startLoc = ctx.location();
        var entryPendingSnapshot = ctx.savePendingLeadingTrivia();
        ParseResult lastFailure = null;
        for (var alt : choice.alternatives()) {
            ParseResult result;
            if (mode.shouldCollectActions()) {
                // Create local collectors - only merge on success
                var localValues = new ArrayList<Object>();
                var localTokenCapture = new String[1];
                var localMode = mode.childMode(localValues, localTokenCapture);
                result = parseExpressionWithMode(ctx, alt, ruleName, localMode);
                if (result.isSuccess()) {
                    mode.semanticValues()
                        .unwrap()
                        .addAll(localValues);
                    Option.option(localTokenCapture[0])
                          .onPresent(text -> mode.tokenCapture()
                                                 .unwrap() [0] = text);
                    return result;
                }
            }else {
                result = parseExpressionWithMode(ctx, alt, ruleName, mode);
                if (result.isSuccess()) {
                    return result;
                }
            }
            // CutFailure prevents trying other alternatives - propagate it up
            if (result instanceof ParseResult.CutFailure) {
                return result;
            }
            lastFailure = result;
            ctx.restoreLocation(startLoc);
            ctx.restorePendingLeadingTrivia(entryPendingSnapshot);
        }
        return lastFailure != null
               ? lastFailure
               : ParseResult.Failure.failure(ctx.location(), "one of alternatives");
    }

    /**
     * Unified zero-or-more parser - consolidates parseZeroOrMore, parseZeroOrMoreWithActions, parseZeroOrMoreNoWs.
     */
    private ParseResult parseZeroOrMoreWithMode(ParsingContext ctx,
                                                Expression.ZeroOrMore zom,
                                                String ruleName,
                                                ParseMode mode) {
        var startLoc = ctx.location();
        var children = new ArrayList<CstNode>();
        while (true) {
            var beforeLoc = ctx.location();
            var iterPendingSnapshot = ctx.savePendingLeadingTrivia();
            if (mode.shouldSkipWhitespace()) {
                ctx.appendPendingLeadingTrivia(skipWhitespace(ctx));
            }
            ParseResult result;
            if (mode.shouldCollectActions()) {
                var localValues = new ArrayList<Object>();
                var localTokenCapture = new String[1];
                var localMode = mode.childMode(localValues, localTokenCapture);
                result = parseExpressionWithMode(ctx, zom.expression(), ruleName, localMode);
                if (result.isSuccess()) {
                    mode.semanticValues()
                        .unwrap()
                        .addAll(localValues);
                    Option.option(localTokenCapture[0])
                          .onPresent(text -> mode.tokenCapture()
                                                 .unwrap() [0] = text);
                }
            }else {
                result = parseExpressionWithMode(ctx, zom.expression(), ruleName, mode);
            }
            // CutFailure must propagate - don't just break
            if (result instanceof ParseResult.CutFailure) {
                return result;
            }
            if (result.isFailure()) {
                ctx.restoreLocation(beforeLoc);
                ctx.restorePendingLeadingTrivia(iterPendingSnapshot);
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
            return ParseResult.Success.success(children.getFirst(), ctx.location());
        }
        var node = new CstNode.NonTerminal(span, ruleName, children, List.of(), List.of());
        return ParseResult.Success.success(node, ctx.location());
    }

    /**
     * Unified one-or-more parser - consolidates parseOneOrMore, parseOneOrMoreWithActions, parseOneOrMoreNoWs.
     */
    private ParseResult parseOneOrMoreWithMode(ParsingContext ctx,
                                               Expression.OneOrMore oom,
                                               String ruleName,
                                               ParseMode mode) {
        var startLoc = ctx.location();
        var children = new ArrayList<CstNode>();
        // First match is required
        var first = parseExpressionWithMode(ctx, oom.expression(), ruleName, mode);
        if (first.isFailure()) {
            return first;
        }
        if (first instanceof ParseResult.Success success) {
            children.add(success.node());
        }
        // Subsequent matches are optional
        while (true) {
            var beforeLoc = ctx.location();
            var iterPendingSnapshot = ctx.savePendingLeadingTrivia();
            if (mode.shouldSkipWhitespace()) {
                ctx.appendPendingLeadingTrivia(skipWhitespace(ctx));
            }
            ParseResult result;
            if (mode.shouldCollectActions()) {
                var localValues = new ArrayList<Object>();
                var localTokenCapture = new String[1];
                var localMode = mode.childMode(localValues, localTokenCapture);
                result = parseExpressionWithMode(ctx, oom.expression(), ruleName, localMode);
                if (result.isSuccess()) {
                    mode.semanticValues()
                        .unwrap()
                        .addAll(localValues);
                    Option.option(localTokenCapture[0])
                          .onPresent(text -> mode.tokenCapture()
                                                 .unwrap() [0] = text);
                }
            }else {
                result = parseExpressionWithMode(ctx, oom.expression(), ruleName, mode);
            }
            // CutFailure must propagate - don't just break
            if (result instanceof ParseResult.CutFailure) {
                return result;
            }
            if (result.isFailure()) {
                ctx.restoreLocation(beforeLoc);
                ctx.restorePendingLeadingTrivia(iterPendingSnapshot);
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
            return ParseResult.Success.success(children.getFirst(), ctx.location());
        }
        var node = new CstNode.NonTerminal(span, ruleName, children, List.of(), List.of());
        return ParseResult.Success.success(node, ctx.location());
    }

    /**
     * Unified optional parser - consolidates parseOptional, parseOptionalWithActions, parseOptionalNoWs.
     */
    private ParseResult parseOptionalWithMode(ParsingContext ctx,
                                              Expression.Optional opt,
                                              String ruleName,
                                              ParseMode mode) {
        var startLoc = ctx.location();
        var entryPendingSnapshot = ctx.savePendingLeadingTrivia();
        var result = parseExpressionWithMode(ctx, opt.expression(), ruleName, mode);
        if (result.isSuccess()) {
            return result;
        }
        // CutFailure must propagate - don't treat as success
        if (result instanceof ParseResult.CutFailure) {
            return result;
        }
        // Optional always succeeds - return empty node on no match
        ctx.restoreLocation(startLoc);
        ctx.restorePendingLeadingTrivia(entryPendingSnapshot);
        var span = SourceSpan.sourceSpan(startLoc);
        var node = new CstNode.NonTerminal(span, ruleName, List.of(), List.of(), List.of());
        return ParseResult.Success.success(node, ctx.location());
    }

    /**
     * Unified repetition parser - consolidates parseRepetition, parseRepetitionWithActions, parseRepetitionNoWs.
     */
    private ParseResult parseRepetitionWithMode(ParsingContext ctx,
                                                Expression.Repetition rep,
                                                String ruleName,
                                                ParseMode mode) {
        var startLoc = ctx.location();
        var children = new ArrayList<CstNode>();
        int count = 0;
        int max = rep.max()
                     .isPresent()
                  ? rep.max()
                       .unwrap()
                  : Integer.MAX_VALUE;
        while (count < max) {
            var beforeLoc = ctx.location();
            var iterPendingSnapshot = ctx.savePendingLeadingTrivia();
            if (count > 0 && mode.shouldSkipWhitespace()) {
                ctx.appendPendingLeadingTrivia(skipWhitespace(ctx));
            }
            ParseResult result;
            if (mode.shouldCollectActions()) {
                var localValues = new ArrayList<Object>();
                var localTokenCapture = new String[1];
                var localMode = mode.childMode(localValues, localTokenCapture);
                result = parseExpressionWithMode(ctx, rep.expression(), ruleName, localMode);
                if (result.isSuccess()) {
                    mode.semanticValues()
                        .unwrap()
                        .addAll(localValues);
                    Option.option(localTokenCapture[0])
                          .onPresent(text -> mode.tokenCapture()
                                                 .unwrap() [0] = text);
                }
            }else {
                result = parseExpressionWithMode(ctx, rep.expression(), ruleName, mode);
            }
            // CutFailure must propagate - don't just break
            if (result instanceof ParseResult.CutFailure) {
                return result;
            }
            if (result.isFailure()) {
                ctx.restoreLocation(beforeLoc);
                ctx.restorePendingLeadingTrivia(iterPendingSnapshot);
                break;
            }
            if (result instanceof ParseResult.Success success) {
                children.add(success.node());
            }
            count++ ;
            if (ctx.pos() == beforeLoc.offset()) {
                break;
            }
        }
        if (count < rep.min()) {
            ctx.restoreLocation(startLoc);
            return ParseResult.Failure.failure(ctx.location(), "at least " + rep.min() + " repetitions");
        }
        var span = ctx.spanFrom(startLoc);
        if (children.size() == 1) {
            return ParseResult.Success.success(children.getFirst(), ctx.location());
        }
        var node = new CstNode.NonTerminal(span, ruleName, children, List.of(), List.of());
        return ParseResult.Success.success(node, ctx.location());
    }

    /**
     * Parse expression with configurable mode (standard, withActions, noWhitespace).
     */
    private ParseResult parseExpressionWithMode(ParsingContext ctx,
                                                Expression expr,
                                                String ruleName,
                                                ParseMode mode) {
        return switch (expr) {
            case Expression.Literal lit -> parseLiteral(ctx, lit);
            case Expression.CharClass cc -> parseCharClass(ctx, cc);
            case Expression.Any any -> parseAny(ctx, any);
            case Expression.Reference ref -> mode.shouldCollectActions()
                                             ? parseReferenceWithActions(ctx,
                                                                         ref,
                                                                         mode.semanticValues()
                                                                             .unwrap())
                                             : parseReference(ctx, ref);
            case Expression.Sequence seq -> parseSequenceWithMode(ctx, seq, ruleName, mode);
            case Expression.Choice choice -> parseChoiceWithMode(ctx, choice, ruleName, mode);
            case Expression.ZeroOrMore zom -> parseZeroOrMoreWithMode(ctx, zom, ruleName, mode);
            case Expression.OneOrMore oom -> parseOneOrMoreWithMode(ctx, oom, ruleName, mode);
            case Expression.Optional opt -> parseOptionalWithMode(ctx, opt, ruleName, mode);
            case Expression.Repetition rep -> parseRepetitionWithMode(ctx, rep, ruleName, mode);
            case Expression.And and -> parseAnd(ctx, and, ruleName);
            case Expression.Not not -> parseNot(ctx, not, ruleName);
            case Expression.TokenBoundary tb -> mode.shouldCollectActions()
                                                ? parseTokenBoundaryWithActions(ctx,
                                                                                tb,
                                                                                ruleName,
                                                                                mode.semanticValues()
                                                                                    .unwrap(),
                                                                                mode.tokenCapture()
                                                                                    .unwrap())
                                                : parseTokenBoundary(ctx, tb, ruleName);
            case Expression.Ignore ign -> parseIgnore(ctx, ign, ruleName);
            case Expression.Capture cap -> mode.shouldCollectActions()
                                           ? parseCaptureWithActions(ctx,
                                                                     cap,
                                                                     ruleName,
                                                                     mode.semanticValues()
                                                                         .unwrap(),
                                                                     mode.tokenCapture()
                                                                         .unwrap())
                                           : parseCapture(ctx, cap, ruleName);
            case Expression.CaptureScope cs -> mode.shouldCollectActions()
                                               ? parseCaptureScopeWithActions(ctx,
                                                                              cs,
                                                                              ruleName,
                                                                              mode.semanticValues()
                                                                                  .unwrap(),
                                                                              mode.tokenCapture()
                                                                                  .unwrap())
                                               : parseCaptureScope(ctx, cs, ruleName);
            case Expression.Dictionary dict -> parseDictionary(ctx, dict);
            case Expression.BackReference br -> parseBackReference(ctx, br);
            case Expression.Cut cut -> parseCut(ctx, cut);
            case Expression.Group grp -> parseExpressionWithMode(ctx, grp.expression(), ruleName, mode);
        };
    }

    /**
     * Concatenate two non-empty trivia lists. Callers should short-circuit
     * when either list is empty to avoid unnecessary allocation.
     */
    private static List<Trivia> concatTrivia(List<Trivia> first, List<Trivia> second) {
        var combined = new ArrayList<Trivia>(first.size() + second.size());
        combined.addAll(first);
        combined.addAll(second);
        return List.copyOf(combined);
    }

    private CstNode wrapWithRuleName(CstNode node, String ruleName, List<Trivia> leadingTrivia) {
        return switch (node) {
            case CstNode.Terminal t -> new CstNode.Terminal(
            t.span(), ruleName, t.text(), leadingTrivia, t.trailingTrivia());
            case CstNode.NonTerminal nt -> new CstNode.NonTerminal(
            nt.span(), ruleName, nt.children(), leadingTrivia, nt.trailingTrivia());
            case CstNode.Token tok -> new CstNode.Token(
            tok.span(), ruleName, tok.text(), leadingTrivia, tok.trailingTrivia());
            case CstNode.Error err -> new CstNode.Error(
            err.span(), err.skippedText(), err.expected(), leadingTrivia, err.trailingTrivia());
        };
    }

    /**
     * Attach leading trivia to a node without changing its rule name. Used by
     * {@link #parseRuleWithLeftRecursion} to apply outer leading-trivia after
     * the seed-and-grow loop has stored wrapped seeds with empty leading lists.
     */
    private CstNode attachLeadingTrivia(CstNode node, List<Trivia> leadingTrivia) {
        if (leadingTrivia.isEmpty()) {
            return node;
        }
        return switch (node) {
            case CstNode.Terminal t -> new CstNode.Terminal(
            t.span(), t.rule(), t.text(), leadingTrivia, t.trailingTrivia());
            case CstNode.NonTerminal nt -> new CstNode.NonTerminal(
            nt.span(), nt.rule(), nt.children(), leadingTrivia, nt.trailingTrivia());
            case CstNode.Token tok -> new CstNode.Token(
            tok.span(), tok.rule(), tok.text(), leadingTrivia, tok.trailingTrivia());
            case CstNode.Error err -> new CstNode.Error(
            err.span(), err.skippedText(), err.expected(), leadingTrivia, err.trailingTrivia());
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
            t.span(), t.rule(), t.text(), t.leadingTrivia(), trailingTrivia);
            case CstNode.NonTerminal nt -> new CstNode.NonTerminal(
            nt.span(), nt.rule(), nt.children(), nt.leadingTrivia(), trailingTrivia);
            case CstNode.Token tok -> new CstNode.Token(
            tok.span(), tok.rule(), tok.text(), tok.leadingTrivia(), trailingTrivia);
            case CstNode.Error err -> new CstNode.Error(
            err.span(), err.skippedText(), err.expected(), err.leadingTrivia(), trailingTrivia);
        };
    }

    /**
     * 0.3.5 (Bug C') — rule-exit trailing-trivia attribution. When a rule body
     * accumulates trivia in {@code pendingLeadingTrivia} that is not claimed by
     * any child (typically: inter-element skipWhitespace before a zero-width
     * tail element such as an empty ZoM/Optional), append that trivia to the
     * last child's trailingTrivia (or the node's own trailing if it has no
     * children). This keeps reconstruction byte-equal without rewinding the
     * parser position — important because predicate combinators rely on
     * pos already being past any consumed whitespace.
     */
    private CstNode attachTrailingToTail(CstNode node, List<Trivia> trivia) {
        if (trivia.isEmpty()) return node;
        return switch (node) {
            case CstNode.NonTerminal nt -> {
                var children = nt.children();
                if (children.isEmpty()) {
                    var combined = combineTrivia(nt.trailingTrivia(), trivia);
                    yield new CstNode.NonTerminal(
                    nt.span(), nt.rule(), children, nt.leadingTrivia(), combined);
                }
                var newChildren = new ArrayList<>(children);
                var lastIdx = newChildren.size() - 1;
                var lastChild = newChildren.get(lastIdx);
                newChildren.set(lastIdx, attachTrailingToTail(lastChild, trivia));
                yield new CstNode.NonTerminal(
                nt.span(), nt.rule(), List.copyOf(newChildren), nt.leadingTrivia(), nt.trailingTrivia());
            }
            case CstNode.Terminal t -> new CstNode.Terminal(
            t.span(), t.rule(), t.text(), t.leadingTrivia(), combineTrivia(t.trailingTrivia(), trivia));
            case CstNode.Token tok -> new CstNode.Token(
            tok.span(), tok.rule(), tok.text(), tok.leadingTrivia(), combineTrivia(tok.trailingTrivia(), trivia));
            case CstNode.Error err -> new CstNode.Error(
            err.span(),
            err.skippedText(),
            err.expected(),
            err.leadingTrivia(),
            combineTrivia(err.trailingTrivia(), trivia));
        };
    }

    private static List<Trivia> combineTrivia(List<Trivia> first, List<Trivia> second) {
        if (first.isEmpty()) return second;
        if (second.isEmpty()) return first;
        var combined = new ArrayList<Trivia>(first.size() + second.size());
        combined.addAll(first);
        combined.addAll(second);
        return List.copyOf(combined);
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
            t.span(), t.rule(), t.text(), Option.none());
            case CstNode.Token tok -> new AstNode.Terminal(
            tok.span(), tok.rule(), tok.text(), Option.none());
            case CstNode.NonTerminal nt -> new AstNode.NonTerminal(
            nt.span(),
            nt.rule(),
            nt.children()
              .stream()
              .map(this::toAst)
              .toList(),
            Option.none());
            case CstNode.Error err -> new AstNode.Terminal(
            err.span(), err.rule(), err.skippedText(), Option.none());
        };
    }
}
