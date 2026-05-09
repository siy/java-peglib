package org.pragmatica.peg.parser;

import org.pragmatica.lang.Option;
import org.pragmatica.peg.error.Diagnostic;
import org.pragmatica.peg.error.RecoveryStrategy;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.tree.IdGenerator;
import org.pragmatica.peg.tree.SourceLocation;
import org.pragmatica.peg.tree.SourceSpan;
import org.pragmatica.peg.tree.StringSpan;
import org.pragmatica.peg.tree.Trivia;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Mutable parsing context that tracks state during parsing.
 */
public final class ParsingContext {
    private static final int MAX_CACHE_SIZE = 10_000;

    private final String input;
    private final Grammar grammar;
    private final ParserConfig config;
    private final Option<Map<Long, CacheEntry>> packratCache;
    private final Option<Map<String, Integer>> ruleIds;
    private final Map<String, String> captures;

    // Phase 1.2 (v0.5.0): per-session ID generator. Allocated once per parse;
    // every CstNode constructed by PegEngine pulls its id from here. Spec
    // §2 Lever A; see docs/incremental/ARCHITECTURE-0.5.0.md.
    private final IdGenerator idGen;

    private int pos;
    private int line;
    private int column;
    private int furthestPos;
    private int furthestLine;
    private int furthestColumn;
    private final LinkedHashSet<String> furthestExpected = new LinkedHashSet<>();
    private String furthestExpectedJoined;

    // null = stale, must rebuild from set
    private int tokenBoundaryDepth;

    // Error recovery state
    private final List<Diagnostic> diagnostics;
    private boolean inRecovery;
    private Option<Integer> recoveryStartPos;

    // Whitespace skipping guard (prevents recursive whitespace parsing)
    private boolean skippingWhitespace;

    // Pending leading trivia — trivia matched between sibling sequence elements
    // that should attach to the following sibling's leadingTrivia. See
    // docs/TRIVIA-ATTRIBUTION.md for the attribution rule. Backtracking
    // combinators (Choice/Optional/And/Not) must save/restore snapshots of
    // this buffer around each attempt so failed alternatives do not leak
    // trivia forward.
    private final List<Trivia> pendingLeadingTrivia = new ArrayList<>();

    // 0.2.4: suggestion vocabulary derived once from rules marked with
    // %suggest. Shared list, never recomputed. When empty, no hint logic
    // runs. Stored on the context so incremental-parse sessions can carry it
    // forward without recomputation.
    private List<String> suggestionVocabulary = List.of();

    // 0.2.4: stack of per-rule recovery terminators in scope. The top of
    // the stack wins; an empty stack falls back to the global recovery
    // point set. Rules pushing a %recover terminator must pop on exit.
    private final java.util.ArrayDeque<String> recoveryOverrideStack = new java.util.ArrayDeque<>();

    // 0.3.5 (Phase 5): pending recovery override captured at the moment a
    // rule with %recover fails. Required because parseRule pops the stack in
    // its finally block before parseWithRecovery consults skipToRecoveryPoint;
    // without this field the stack is always empty at recovery time and the
    // directive is a silent no-op. Set by the deepest failing rule with
    // %recover (parseRule variants record before popping); consumed and
    // cleared by skipToRecoveryPoint. Cleared on rule success so that a
    // backtracked alternative's override does not leak forward.
    private Option<String> pendingFailureRecoveryOverride = Option.none();

    private ParsingContext(String input, Grammar grammar, ParserConfig config, IdGenerator idGen) {
        if (idGen == null) {
            throw new IllegalArgumentException("idGen must not be null");
        }
        this.input = input;
        this.grammar = grammar;
        this.config = config;
        this.packratCache = config.packratEnabled()
                            ? Option.some(createBoundedCache())
                            : Option.none();
        this.ruleIds = config.packratEnabled()
                       ? Option.some(new HashMap<>())
                       : Option.none();
        this.captures = new HashMap<>();
        this.diagnostics = new ArrayList<>();
        this.idGen = idGen;
        this.pos = 0;
        this.line = 1;
        this.column = 1;
        this.furthestPos = 0;
        this.furthestLine = 1;
        this.furthestColumn = 1;
        this.furthestExpectedJoined = null;
        this.inRecovery = false;
        this.recoveryStartPos = Option.none();
    }

    /**
     * Default factory: allocates a fresh per-session ID counter. Used by
     * one-shot parses ({@link PegEngine#parseCst}, etc.).
     */
    public static ParsingContext create(String input, Grammar grammar, ParserConfig config) {
        return new ParsingContext(input, grammar, config, new IdGenerator.PerSessionCounter());
    }

    /**
     * Phase 1.5 (v0.5.0): Session-aware factory. {@link IncrementalSession} owns
     * a single {@link IdGenerator.PerSessionCounter} for the lifetime of a
     * Session lineage and threads it through every reparse so node IDs remain
     * stable across edits — a hard precondition for the Path D optimized
     * {@code NodeIndex.applyIncremental}.
     */
    public static ParsingContext create(String input, Grammar grammar, ParserConfig config, IdGenerator idGen) {
        return new ParsingContext(input, grammar, config, idGen);
    }

    // === Position Management ===
    public int pos() {
        return pos;
    }

    public void setPos(int pos) {
        this.pos = pos;
    }

    public SourceLocation location() {
        return SourceLocation.sourceLocation(line, column, pos);
    }

    public void restoreLocation(SourceLocation loc) {
        this.pos = loc.offset();
        this.line = loc.line();
        this.column = loc.column();
    }

    public boolean isAtEnd() {
        return pos >= input.length();
    }

    public int remaining() {
        return input.length() - pos;
    }

    // === Character Access ===
    public char peek() {
        return input.charAt(pos);
    }

    public char peek(int offset) {
        return input.charAt(pos + offset);
    }

    public char advance() {
        char c = input.charAt(pos++ );
        if (c == '\n') {
            line++ ;
            column = 1;
        }else {
            column++ ;
        }
        return c;
    }

    /**
     * Phase-1 optimization (§6.4): bulk-advance {@code count} characters when the
     * caller has pre-verified the matched text contains no newline. Updates
     * {@code pos} and {@code column} in O(1) without the per-char newline check.
     * Caller's responsibility to ensure {@code count} chars from the current
     * position contain no {@code '\n'}.
     */
    public void bulkAdvanceNoNewline(int count) {
        pos += count;
        column += count;
    }

    public String substring(int start, int end) {
        return input.substring(start, end);
    }

    /**
     * Cleanup E (v0.5.1): produce a {@link StringSpan} view over a slice of the
     * input without materializing a {@link String}. Used by Terminal/Token
     * construction to defer substring allocation.
     */
    public StringSpan substringSpan(int start, int end) {
        return new StringSpan(input, start, end);
    }

    public String remainingInput() {
        return input.substring(pos);
    }

    // === Error Tracking ===
    public void updateFurthest(String expected) {
        if (pos > furthestPos) {
            furthestPos = pos;
            furthestLine = line;
            furthestColumn = column;
            furthestExpected.clear();
            furthestExpected.add(expected);
            furthestExpectedJoined = null;
        }else if (pos == furthestPos && furthestExpected.add(expected)) {
            furthestExpectedJoined = null;
        }
    }

    public int furthestPos() {
        return furthestPos;
    }

    public SourceLocation furthestLocation() {
        return SourceLocation.sourceLocation(furthestLine, furthestColumn, furthestPos);
    }

    public String furthestExpected() {
        if (furthestExpectedJoined == null) {
            furthestExpectedJoined = String.join(" or ", furthestExpected);
        }
        return furthestExpectedJoined;
    }

    // === Diagnostic Collection (for advanced error recovery) ===
    /**
     * Add a diagnostic error and continue parsing if recovery is enabled.
     */
    public void addDiagnostic(Diagnostic diagnostic) {
        diagnostics.add(diagnostic);
    }

    /**
     * Create and add an error diagnostic at current position.
     */
    public void addError(String message, SourceSpan span, String label) {
        var diag = Diagnostic.error(message, span)
                             .withLabel(label);
        diagnostics.add(diag);
    }

    /**
     * Create and add an error diagnostic with expected token info.
     */
    public void addUnexpectedError(String found, String expected) {
        var span = SourceSpan.sourceSpan(location());
        var message = found.isEmpty()
                      ? "unexpected end of input"
                      : "unexpected input";
        var diag = Diagnostic.error(message, span)
                             .withLabel("found '" + (found.isEmpty()
                                                     ? "EOF"
                                                     : found) + "'")
                             .withHelp("expected " + expected)
                             .withTag("error.unexpected-input");
        diagnostics.add(diag);
    }

    /**
     * Get all accumulated diagnostics.
     */
    public List<Diagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    /**
     * Check if any errors were recorded.
     */
    public boolean hasErrors() {
        return !diagnostics.isEmpty();
    }

    /**
     * Clear all diagnostics (used when retrying with different strategy).
     */
    public void clearDiagnostics() {
        diagnostics.clear();
    }

    // === Error Recovery State ===
    /**
     * Check if advanced recovery is enabled.
     */
    public boolean isRecoveryEnabled() {
        return config.recoveryStrategy() == RecoveryStrategy.ADVANCED;
    }

    /**
     * Enter recovery mode - skip input until we find something parseable.
     */
    public void enterRecovery() {
        if (!inRecovery) {
            inRecovery = true;
            recoveryStartPos = Option.some(pos);
        }
    }

    /**
     * Exit recovery mode after successfully parsing something.
     */
    public void exitRecovery() {
        inRecovery = false;
        recoveryStartPos = Option.none();
    }

    /**
     * Check if currently in recovery mode.
     */
    public boolean isInRecovery() {
        return inRecovery;
    }

    /**
     * Skip one character during recovery.
     * Returns the skipped character span for error node creation.
     */
    public SourceSpan skipForRecovery() {
        var start = location();
        if (!isAtEnd()) {
            advance();
        }
        return spanFrom(start);
    }

    /**
     * Skip characters until a recovery point is found.
     * Recovery points are typically: newlines, semicolons, closing braces.
     *
     * <p>0.2.4: when a rule has a {@code %recover '<term>'} directive active
     * (via {@link #pushRecoveryOverride}), only the specified terminator
     * string stops recovery; the global char-set fallback is suppressed.
     */
    public SourceSpan skipToRecoveryPoint() {
        var start = location();
        // 0.3.5 (Phase 5) — fix: consult the pending failure override first.
        // parseRule's finally block pops the stack before this method is
        // reached, so the live stack is empty during top-level recovery; the
        // pending field carries the override of the deepest %recover-equipped
        // rule whose body failure triggered recovery. Falls through to the
        // live stack (still useful for nested-recovery scenarios where push
        // outlives the failure) and finally to the global char-set.
        String override = null;
        if (pendingFailureRecoveryOverride.isPresent()) {
            override = pendingFailureRecoveryOverride.unwrap();
            pendingFailureRecoveryOverride = Option.none();
        }else {
            override = recoveryOverrideStack.peek();
        }
        if (override != null && !override.isEmpty()) {
            while (!isAtEnd() && !matchesOverrideAt(override)) {
                advance();
            }
            return spanFrom(start);
        }
        while (!isAtEnd()) {
            char c = peek();
            // Stop at common synchronization points
            if (c == '\n' || c == ';' || c == ',' || c == '}' || c == ')' || c == ']') {
                break;
            }
            advance();
        }
        return spanFrom(start);
    }

    private boolean matchesOverrideAt(String term) {
        if (remaining() < term.length()) {
            return false;
        }
        for (int i = 0; i < term.length(); i++ ) {
            if (peek(i) != term.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    // === 0.2.4: %recover rule-scope terminator stack ===
    /**
     * Push a per-rule recovery terminator onto the active stack. The rule
     * body that follows will recover by skipping until this literal is
     * seen, instead of the global char-set. Must be paired with
     * {@link #popRecoveryOverride()}.
     */
    public void pushRecoveryOverride(String terminator) {
        recoveryOverrideStack.push(terminator);
    }

    /**
     * Pop the top rule-scope recovery terminator. No-op if the stack is
     * empty (defensive — shouldn't happen with balanced push/pop).
     */
    public void popRecoveryOverride() {
        if (!recoveryOverrideStack.isEmpty()) {
            recoveryOverrideStack.pop();
        }
    }

    /**
     * Check whether any rule-scope recovery override is currently active.
     */
    public boolean hasRecoveryOverride() {
        return !recoveryOverrideStack.isEmpty();
    }

    /**
     * 0.3.5 (Phase 5) — record the override of a rule whose body has just
     * failed. Called from parseRule variants on the failure path BEFORE the
     * stack-pop in {@code finally}, so the override survives stack unwinding
     * and is available to {@link #skipToRecoveryPoint()} at the top-level
     * recovery loop.
     *
     * <p>Idempotent w.r.t. the deepest-wins policy: only sets the field when
     * empty. As failures bubble up, the innermost (deepest) failing rule
     * with {@code %recover} populates the field first; outer rules — even if
     * they also have {@code %recover} — do not overwrite. The most-specific
     * scope wins, which matches user intent: a per-statement recovery
     * terminator should not be replaced by an enclosing block's terminator.
     */
    public void recordFailureRecoveryOverride(String terminator) {
        if (terminator != null && !terminator.isEmpty() && pendingFailureRecoveryOverride.isEmpty()) {
            pendingFailureRecoveryOverride = Option.some(terminator);
        }
    }

    /**
     * 0.3.5 (Phase 5) — clear any pending failure override. Called on rule
     * SUCCESS so that an override recorded by a failed inner alternative of
     * a backtracking combinator (Choice/Optional/etc.) does not leak forward
     * into a later, unrelated recovery cycle.
     */
    public void clearPendingRecoveryOverride() {
        pendingFailureRecoveryOverride = Option.none();
    }

    // === 0.2.4: Suggestion Vocabulary ===
    /**
     * Install the suggestion vocabulary computed once from
     * {@code %suggest}-designated rules. Called by the engine at context
     * creation or by a Session when carrying state forward. The list is
     * shared; callers must not mutate it.
     */
    public void setSuggestionVocabulary(List<String> vocabulary) {
        this.suggestionVocabulary = vocabulary;
    }

    /**
     * Access the installed suggestion vocabulary. Returns an empty list
     * when no {@code %suggest} directive designated any rule; callers use
     * that as the zero-cost signal that no hint logic should run.
     */
    public List<String> suggestionVocabulary() {
        return suggestionVocabulary;
    }

    // === Token Boundary Tracking ===
    public void enterTokenBoundary() {
        tokenBoundaryDepth++ ;
    }

    public void exitTokenBoundary() {
        tokenBoundaryDepth-- ;
    }

    public boolean inTokenBoundary() {
        return tokenBoundaryDepth > 0;
    }

    // === Whitespace Skipping Guard ===
    public boolean isSkippingWhitespace() {
        return skippingWhitespace;
    }

    public void enterWhitespaceSkip() {
        skippingWhitespace = true;
    }

    public void exitWhitespaceSkip() {
        skippingWhitespace = false;
    }

    // === Pending Leading Trivia ===
    /**
     * Append captured trivia to the pending-leading buffer. Called at sites
     * between sibling sequence elements (sequence, zero-or-more, one-or-more,
     * repetition) so the following sibling's leaf or rule can claim it.
     *
     * <p>Step 4 commit 3: when {@code config.triviaPostPass()} is true, the
     * post-pass overrides all leading/trailing attribution after the main
     * parse, so any work done by this buffer is discarded. Early-out to
     * eliminate the wasted CPU.
     */
    public void appendPendingLeadingTrivia(List<Trivia> captured) {
        if (config.triviaPostPass()) {
            return;
        }
        if (!captured.isEmpty()) {
            pendingLeadingTrivia.addAll(captured);
        }
    }

    /**
     * Take and clear the pending-leading buffer. Called when constructing a
     * CstNode that owns {@code leadingTrivia} — leaf terminals, token
     * boundaries, or rule wrappers. Returns the previously-pending list; the
     * buffer is reset to empty.
     *
     * <p>Step 4 commit 3: under {@code config.triviaPostPass()=true}, returns
     * an empty list without touching state — the post-pass will reattribute
     * trivia regardless.
     */
    public List<Trivia> takePendingLeadingTrivia() {
        if (config.triviaPostPass()) {
            return List.of();
        }
        if (pendingLeadingTrivia.isEmpty()) {
            return List.of();
        }
        var snapshot = List.copyOf(pendingLeadingTrivia);
        pendingLeadingTrivia.clear();
        return snapshot;
    }

    /**
     * Capture the full contents of the pending-leading buffer for later
     * restoration. Backtracking combinators (Choice/Optional/And/Not/Sequence)
     * must call this before attempting each alternative and call
     * {@link #restorePendingLeadingTrivia(List)} on failure so that the
     * pending-leading state is fully reverted — including items that were
     * consumed (drained) by inner operations inside the failed attempt.
     *
     * <p>A size-only snapshot is insufficient: if an inner operation drains
     * pending trivia (via {@link #takePendingLeadingTrivia()}) and the outer
     * branch later fails, restoring just the size cannot recover the consumed
     * items. The full-list snapshot guarantees byte-identical buffer state
     * regardless of intermediate drains.</p>
     *
     * <p>Step 4 commit 3: under {@code config.triviaPostPass()=true}, returns
     * an empty sentinel list — the matching {@code restorePendingLeadingTrivia}
     * is also a no-op, so the snapshot value is irrelevant.
     */
    public List<Trivia> savePendingLeadingTrivia() {
        if (config.triviaPostPass()) {
            return List.of();
        }
        return List.copyOf(pendingLeadingTrivia);
    }

    /**
     * Restore the pending-leading buffer to a previously-captured snapshot.
     * Replaces all current contents with those from {@code snapshot},
     * recovering items that may have been drained by inner operations
     * inside a now-failed branch.
     *
     * <p>Step 4 commit 3: under {@code config.triviaPostPass()=true}, no-op —
     * the buffer is never written under the flag, so there is nothing to
     * restore.
     */
    public void restorePendingLeadingTrivia(List<Trivia> snapshot) {
        if (config.triviaPostPass()) {
            return;
        }
        pendingLeadingTrivia.clear();
        pendingLeadingTrivia.addAll(snapshot);
    }

    // === Captures (for back-references) ===
    public void setCapture(String name, String value) {
        captures.put(name, value);
    }

    public Option<String> getCapture(String name) {
        return Option.option(captures.get(name));
    }

    public void clearCaptures() {
        captures.clear();
    }

    /**
     * Save current captures for later restoration (used by capture scope).
     */
    public Map<String, String> saveCaptures() {
        return new HashMap<>(captures);
    }

    /**
     * Restore captures from a previously saved state.
     */
    public void restoreCaptures(Map<String, String> saved) {
        captures.clear();
        captures.putAll(saved);
    }

    // === Packrat Cache ===
    /**
     * 0.2.9 — a cache entry carries the memoized {@link ParseResult} along with
     * Warth-style growing-state. For non-LR rules, entries are always
     * {@code growing=false} with generation {@code 0} and behave exactly like
     * the pre-0.2.9 cached results. LR rules seed the cache with a growing
     * entry whose {@link #result()} is updated on each reparse iteration until
     * the seed stabilizes.
     *
     * @param result          the memoized parse result
     * @param growing         {@code true} during the seed-and-grow loop for a
     *                        left-recursive rule
     * @param seedGeneration  Warth-style generation counter; incremented on
     *                        every iteration that extends the seed. Used by
     *                        the interpreter to distinguish stale lookups from
     *                        fresh ones during nested invocations.
     */
    public record CacheEntry(ParseResult result, boolean growing, int seedGeneration) {
        public static CacheEntry settled(ParseResult result) {
            return new CacheEntry(result, false, 0);
        }

        public static CacheEntry seed(ParseResult result, int generation) {
            return new CacheEntry(result, true, generation);
        }
    }

    public Option<ParseResult> getCached(String ruleName) {
        return getCachedAt(ruleName, pos);
    }

    public Option<ParseResult> getCachedAt(String ruleName, int position) {
        if (packratCache.isEmpty()) {
            return Option.none();
        }
        long key = packratKey(ruleName, position);
        return Option.option(packratCache.unwrap()
                                         .get(key))
                     .map(CacheEntry::result);
    }

    /**
     * 0.2.9 — look up the full cache entry (including growing state) at
     * {@code position}. Used by the LR seed-and-grow loop to detect an
     * in-progress growing entry and return the current seed immediately
     * instead of re-entering the rule body.
     */
    public Option<CacheEntry> getCachedEntryAt(String ruleName, int position) {
        if (packratCache.isEmpty()) {
            return Option.none();
        }
        long key = packratKey(ruleName, position);
        return Option.option(packratCache.unwrap()
                                         .get(key));
    }

    public void cache(String ruleName, ParseResult result) {
        cacheAt(ruleName, pos, result);
    }

    public void cacheAt(String ruleName, int position, ParseResult result) {
        cacheEntryAt(ruleName, position, CacheEntry.settled(result));
    }

    /**
     * 0.2.9 — store a complete {@link CacheEntry} at {@code position}. Used by
     * the LR seed-and-grow loop to publish the current growing seed so
     * recursive self-invocations observe it via {@link #getCachedEntryAt}.
     */
    public void cacheEntryAt(String ruleName, int position, CacheEntry entry) {
        if (packratCache.isPresent()) {
            long key = packratKey(ruleName, position);
            packratCache.unwrap()
                        .put(key, entry);
        }
    }

    private long packratKey(String ruleName, int position) {
        int ruleId = ruleIds.unwrap()
                            .computeIfAbsent(ruleName,
                                             k -> ruleIds.unwrap()
                                                         .size());
        // Encode rule ID in upper 32 bits, position in lower 32 bits
        return ((long) ruleId<< 32) | (position & 0xFFFFFFFFL);
    }

    private static Map<Long, CacheEntry> createBoundedCache() {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, CacheEntry> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        };
    }

    // === Accessors ===
    public String input() {
        return input;
    }

    public Grammar grammar() {
        return grammar;
    }

    public ParserConfig config() {
        return config;
    }

    /**
     * Phase 1.2 (v0.5.0): the per-session ID generator. PegEngine pulls a
     * fresh id from this for every {@code CstNode} it constructs.
     */
    public IdGenerator idGen() {
        return idGen;
    }

    // === Span Creation ===
    public SourceSpan spanFrom(SourceLocation start) {
        return SourceSpan.sourceSpan(start, location());
    }

    public SourceSpan spanFrom(int startOffset) {
        // Reconstruct start location (simplified - doesn't track line/column for offset)
        var start = SourceLocation.sourceLocation(1, 1, startOffset);
        return SourceSpan.sourceSpan(start, location());
    }
}
