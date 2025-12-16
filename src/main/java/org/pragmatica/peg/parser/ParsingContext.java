package org.pragmatica.peg.parser;

import org.pragmatica.lang.Option;
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
    private final Map<String, String> captures;

    private int pos;
    private int line;
    private int column;
    private int furthestPos;
    private String furthestExpected;
    private int tokenBoundaryDepth;

    private ParsingContext(String input, Grammar grammar, ParserConfig config) {
        this.input = input;
        this.grammar = grammar;
        this.config = config;
        this.packratCache = config.packratEnabled() ? new HashMap<>() : null;
        this.captures = new HashMap<>();
        this.pos = 0;
        this.line = 1;
        this.column = 1;
        this.furthestPos = 0;
        this.furthestExpected = "";
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

    public String furthestExpected() {
        return furthestExpected;
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

    // === Whitespace Handling ===

    public List<Trivia> skipWhitespace() {
        var trivia = new ArrayList<Trivia>();
        if (grammar.whitespace().isEmpty()) {
            return trivia;
        }
        // Whitespace skipping is handled by the engine using the %whitespace rule
        // This is a placeholder - actual implementation in PegEngine
        return trivia;
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
        return ((long) ruleName.hashCode() << 32) | position;
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
