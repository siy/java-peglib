package org.pragmatica.peg.grammar;

import org.pragmatica.peg.tree.SourceLocation;
import org.pragmatica.peg.tree.SourceSpan;

import java.util.ArrayList;
import java.util.List;

/**
 * Lexer for PEG grammar syntax.
 */
public final class GrammarLexer {
    private static final int MAX_INPUT_SIZE = 1_000_000;
    private static final int DEFAULT_TOKEN_CAPACITY = 32;

    private final String input;
    private int pos;
    private int line;
    private int column;

    private GrammarLexer(String input) {
        this.input = input;
        this.pos = 0;
        this.line = 1;
        this.column = 1;
    }

    public static List<GrammarToken> tokenize(String input) {
        if (input.length() > MAX_INPUT_SIZE) {
            throw new IllegalArgumentException(
            "Grammar input exceeds maximum size of " + MAX_INPUT_SIZE + " characters");
        }
        return new GrammarLexer(input).tokenizeAll();
    }

    private List<GrammarToken> tokenizeAll() {
        var tokens = new ArrayList<GrammarToken>();
        while (!isAtEnd()) {
            skipWhitespaceAndComments();
            if (!isAtEnd()) {
                tokens.add(nextToken());
            }
        }
        tokens.add(new GrammarToken.Eof(currentSpan()));
        return tokens;
    }

    private GrammarToken nextToken() {
        var start = currentLocation();
        char c = peek();
        // Identifiers
        if (isIdentifierStart(c)) {
            return scanIdentifier(start);
        }
        // Directives %name
        if (c == '%') {
            return scanDirective(start);
        }
        // String literals
        if (c == '\'' || c == '"') {
            return scanStringLiteral(start);
        }
        // Character class
        if (c == '[') {
            return scanCharClass(start);
        }
        // Action code or repetition
        if (c == '{') {
            // Look ahead to distinguish {n} from { code }
            if (isRepetitionBrace()) {
                advance();
                return new GrammarToken.LBrace(span(start));
            }
            return scanActionCode(start);
        }
        // Numbers (for repetition)
        if (isDigit(c)) {
            return scanNumber(start);
        }
        // Operators and delimiters
        return scanOperator(start);
    }

    private GrammarToken scanIdentifier(SourceLocation start) {
        var sb = new StringBuilder(DEFAULT_TOKEN_CAPACITY);
        while (!isAtEnd() && isIdentifierPart(peek())) {
            sb.append(advance());
        }
        return new GrammarToken.Identifier(span(start), sb.toString());
    }

    private GrammarToken scanDirective(SourceLocation start) {
        advance();
        // skip %
        var sb = new StringBuilder(DEFAULT_TOKEN_CAPACITY);
        while (!isAtEnd() && isIdentifierPart(peek())) {
            sb.append(advance());
        }
        return new GrammarToken.Directive(span(start), sb.toString());
    }

    private GrammarToken scanStringLiteral(SourceLocation start) {
        char quote = advance();
        var sb = new StringBuilder(DEFAULT_TOKEN_CAPACITY);
        while (!isAtEnd() && peek() != quote) {
            if (peek() == '\\' && pos + 1 < input.length()) {
                advance();
                // skip backslash
                sb.append(scanEscapeSequence());
            }else {
                sb.append(advance());
            }
        }
        if (isAtEnd()) {
            return new GrammarToken.Error(span(start), "Unterminated string literal");
        }
        advance();
        // skip closing quote
        // Check for case-insensitive suffix
        boolean caseInsensitive = !isAtEnd() && peek() == 'i';
        if (caseInsensitive) {
            advance();
        }
        return new GrammarToken.StringLiteral(span(start), sb.toString(), caseInsensitive);
    }

    private GrammarToken scanCharClass(SourceLocation start) {
        advance();
        // skip [
        boolean negated = false;
        if (!isAtEnd() && peek() == '^') {
            negated = true;
            advance();
        }
        var sb = new StringBuilder(DEFAULT_TOKEN_CAPACITY);
        while (!isAtEnd() && peek() != ']') {
            if (peek() == '\\' && pos + 1 < input.length()) {
                advance();
                // skip backslash
                sb.append('\\');
                char escaped = advance();
                sb.append(escaped);
                // Preserve full hex/unicode escape sequences
                if (escaped == 'x' && pos + 2 <= input.length()) {
                    sb.append(advance());
                    sb.append(advance());
                }else if (escaped == 'u' && pos + 4 <= input.length()) {
                    sb.append(advance());
                    sb.append(advance());
                    sb.append(advance());
                    sb.append(advance());
                }
            }else {
                sb.append(advance());
            }
        }
        if (isAtEnd()) {
            return new GrammarToken.Error(span(start), "Unterminated character class");
        }
        advance();
        // skip ]
        // Check for case-insensitive suffix
        boolean caseInsensitive = !isAtEnd() && peek() == 'i';
        if (caseInsensitive) {
            advance();
        }
        return new GrammarToken.CharClassLiteral(span(start), sb.toString(), negated, caseInsensitive);
    }

    private GrammarToken scanActionCode(SourceLocation start) {
        advance();
        // skip {
        var sb = new StringBuilder(DEFAULT_TOKEN_CAPACITY);
        int braceDepth = 1;
        while (!isAtEnd() && braceDepth > 0) {
            char c = peek();
            if (c == '{') {
                braceDepth++ ;
            }else if (c == '}') {
                braceDepth-- ;
                if (braceDepth == 0) {
                    break;
                }
            }else if (c == '\'' || c == '"') {
                // Skip string literals in action code
                sb.append(scanJavaString());
                continue;
            }
            sb.append(advance());
        }
        if (isAtEnd()) {
            return new GrammarToken.Error(span(start), "Unterminated action code");
        }
        advance();
        // skip }
        return new GrammarToken.ActionCode(span(start),
                                           sb.toString()
                                             .trim());
    }

    private String scanJavaString() {
        var sb = new StringBuilder(DEFAULT_TOKEN_CAPACITY);
        char quote = advance();
        sb.append(quote);
        while (!isAtEnd() && peek() != quote) {
            if (peek() == '\\' && pos + 1 < input.length()) {
                sb.append(advance());
            }
            sb.append(advance());
        }
        if (!isAtEnd()) {
            sb.append(advance());
        }
        return sb.toString();
    }

    private GrammarToken scanNumber(SourceLocation start) {
        var sb = new StringBuilder(DEFAULT_TOKEN_CAPACITY);
        while (!isAtEnd() && isDigit(peek())) {
            sb.append(advance());
        }
        return new GrammarToken.Number(span(start),
                                       Integer.parseInt(sb.toString()));
    }

    private GrammarToken scanOperator(SourceLocation start) {
        char c = advance();
        return switch (c) {
            case'<' -> {
                if (!isAtEnd() && peek() == '-') {
                    advance();
                    yield new GrammarToken.LeftArrow(span(start));
                }
                yield new GrammarToken.LAngle(span(start));
            }
            case'←' -> new GrammarToken.LeftArrow(span(start));
            case'/' -> new GrammarToken.Slash(span(start));
            case'&' -> new GrammarToken.Ampersand(span(start));
            case'!' -> new GrammarToken.Exclamation(span(start));
            case'?' -> new GrammarToken.Question(span(start));
            case'*' -> new GrammarToken.Star(span(start));
            case'+' -> new GrammarToken.Plus(span(start));
            case'.' -> new GrammarToken.Dot(span(start));
            case'~' -> new GrammarToken.Tilde(span(start));
            case'↑', '^' -> new GrammarToken.Cut(span(start));
            case'(' -> new GrammarToken.LParen(span(start));
            case')' -> new GrammarToken.RParen(span(start));
            case'>' -> new GrammarToken.RAngle(span(start));
            case'{' -> new GrammarToken.LBrace(span(start));
            case'}' -> new GrammarToken.RBrace(span(start));
            case',' -> new GrammarToken.Comma(span(start));
            case'$' -> new GrammarToken.Dollar(span(start));
            case'|' -> new GrammarToken.Pipe(span(start));
            default -> new GrammarToken.Error(span(start), "Unexpected character: " + c);
        };
    }

    private char scanEscapeSequence() {
        if (isAtEnd()) return '\\';
        char c = advance();
        return switch (c) {
            case'n' -> '\n';
            case'r' -> '\r';
            case't' -> '\t';
            case'\\' -> '\\';
            case'\'' -> '\'';
            case'"' -> '"';
            case'0' -> '\0';
            case'x' -> scanHexEscape(2);
            // hex escape
            case'u' -> scanHexEscape(4);
            // unicode escape
            default -> c;
        };
    }

    private char scanHexEscape(int digits) {
        if (pos + digits > input.length()) {
            return (digits == 2)
                   ? 'x'
                   : 'u';
        }
        var hex = input.substring(pos, pos + digits);
        try{
            var value = Integer.parseInt(hex, 16);
            pos += digits;
            column += digits;
            return (char) value;
        } catch (NumberFormatException e) {
            return (digits == 2)
                   ? 'x'
                   : 'u';
        }
    }

    private void skipWhitespaceAndComments() {
        while (!isAtEnd()) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\r') {
                advance();
            }else if (c == '\n') {
                advance();
                line++ ;
                column = 1;
            }else if (c == '#') {
                // Line comment
                while (!isAtEnd() && peek() != '\n') {
                    advance();
                }
            }else {
                break;
            }
        }
    }

    private boolean isAtEnd() {
        return pos >= input.length();
    }

    private char peek() {
        return input.charAt(pos);
    }

    private char advance() {
        char c = input.charAt(pos++ );
        if (c == '\n') {
            line++ ;
            column = 1;
        }else {
            column++ ;
        }
        return c;
    }

    private SourceLocation currentLocation() {
        return SourceLocation.at(line, column, pos);
    }

    private SourceSpan currentSpan() {
        var loc = currentLocation();
        return SourceSpan.at(loc);
    }

    private SourceSpan span(SourceLocation start) {
        return SourceSpan.of(start, currentLocation());
    }

    private boolean isIdentifierStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private boolean isIdentifierPart(char c) {
        return isIdentifierStart(c) || isDigit(c);
    }

    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * Look ahead to determine if { starts a repetition {n}, {n,}, {n,m} or an action { code }.
     */
    private boolean isRepetitionBrace() {
        int lookahead = pos + 1;
        // Skip initial digits
        while (lookahead < input.length() && isDigit(input.charAt(lookahead))) {
            lookahead++ ;
        }
        if (lookahead == pos + 1) {
            // No digits after {, it's action code
            return false;
        }
        if (lookahead >= input.length()) {
            return false;
        }
        char c = input.charAt(lookahead);
        // After digits, must be } or , to be repetition
        return c == '}' || c == ',';
    }
}
