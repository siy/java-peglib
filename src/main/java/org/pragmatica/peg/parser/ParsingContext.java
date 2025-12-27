package org.pragmatica.peg.parser;

import org.pragmatica.lang.Option;
import org.pragmatica.peg.error.Diagnostic;
import org.pragmatica.peg.error.RecoveryStrategy;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.tree.SourceLocation;
import org.pragmatica.peg.tree.SourceSpan;
import org.pragmatica.peg.tree.Trivia;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable parsing context that tracks state during parsing.
 */
public final class ParsingContext {

    private final String input;
    private final Grammar grammar;
    private final ParserConfig config;
    private final Map<Long, ParseResult> packratCache;
    private final Map<String, Integer> ruleIds;
    private final Map<String, String> captures;

    private int pos;
    private int line;
    private int column;
    private int furthestPos;
    private int furthestLine;
    private int furthestColumn;
    private String furthestExpected;
    private int tokenBoundaryDepth;

    // Error recovery state
    private final List<Diagnostic> diagnostics;
    private boolean inRecovery;
    private int recoveryStartPos;

    // Whitespace skipping guard (prevents recursive whitespace parsing)
    private boolean skippingWhitespace;

    private ParsingContext(String input, Grammar grammar, ParserConfig config) {
        this.input = input;
        this.grammar = grammar;
        this.config = config;
        this.packratCache = config.packratEnabled() ? new HashMap<>() : null;
        this.ruleIds = config.packratEnabled() ? new HashMap<>() : null;
        this.captures = new HashMap<>();
        this.diagnostics = new ArrayList<>();
        this.pos = 0;
        this.line = 1;
        this.column = 1;
        this.furthestPos = 0;
        this.furthestLine = 1;
        this.furthestColumn = 1;
        this.furthestExpected = "";
        this.inRecovery = false;
        this.recoveryStartPos = -1;
    }

    public static ParsingContext create(String input, Grammar grammar, ParserConfig config) {
        return new ParsingContext(input, grammar, config);
    }

    // === Position Management ===

    public int pos() {
        return pos;
    }

    public void setPos(int pos) {
        this.pos = pos;
    }

    public SourceLocation location() {
        return SourceLocation.at(line, column, pos);
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
        char c = input.charAt(pos++);
        if (c == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        return c;
    }

    public String substring(int start, int end) {
        return input.substring(start, end);
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
            furthestExpected = expected;
        } else if (pos == furthestPos && !furthestExpected.contains(expected)) {
            furthestExpected = furthestExpected.isEmpty()
                ? expected
                : furthestExpected + " or " + expected;
        }
    }

    public int furthestPos() {
        return furthestPos;
    }

    public SourceLocation furthestLocation() {
        return SourceLocation.at(furthestLine, furthestColumn, furthestPos);
    }

    public String furthestExpected() {
        return furthestExpected;
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
        var diag = Diagnostic.error(message, span).withLabel(label);
        diagnostics.add(diag);
    }

    /**
     * Create and add an error diagnostic with expected token info.
     */
    public void addUnexpectedError(String found, String expected) {
        var span = SourceSpan.at(location());
        var message = found.isEmpty() ? "unexpected end of input" : "unexpected input";
        var diag = Diagnostic.error(message, span)
            .withLabel("found '" + (found.isEmpty() ? "EOF" : found) + "'")
            .withHelp("expected " + expected);
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
            recoveryStartPos = pos;
        }
    }

    /**
     * Exit recovery mode after successfully parsing something.
     */
    public void exitRecovery() {
        inRecovery = false;
        recoveryStartPos = -1;
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
     */
    public SourceSpan skipToRecoveryPoint() {
        var start = location();
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

    // === Token Boundary Tracking ===

    public void enterTokenBoundary() {
        tokenBoundaryDepth++;
    }

    public void exitTokenBoundary() {
        tokenBoundaryDepth--;
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

    public Option<ParseResult> getCached(String ruleName) {
        return getCachedAt(ruleName, pos);
    }

    public Option<ParseResult> getCachedAt(String ruleName, int position) {
        if (packratCache == null) {
            return Option.none();
        }
        long key = packratKey(ruleName, position);
        return Option.option(packratCache.get(key));
    }

    public void cache(String ruleName, ParseResult result) {
        cacheAt(ruleName, pos, result);
    }

    public void cacheAt(String ruleName, int position, ParseResult result) {
        if (packratCache != null) {
            long key = packratKey(ruleName, position);
            packratCache.put(key, result);
        }
    }

    private long packratKey(String ruleName, int position) {
        int ruleId = ruleIds.computeIfAbsent(ruleName, k -> ruleIds.size());
        return ((long) ruleId << 32) | (position & 0xFFFFFFFFL);
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

    // === Span Creation ===

    public SourceSpan spanFrom(SourceLocation start) {
        return SourceSpan.of(start, location());
    }

    public SourceSpan spanFrom(int startOffset) {
        // Reconstruct start location (simplified - doesn't track line/column for offset)
        var start = SourceLocation.at(1, 1, startOffset);
        return SourceSpan.of(start, location());
    }
}
