package org.pragmatica.peg.grammar;

import org.pragmatica.peg.tree.SourceSpan;

/**
 * Token types for grammar lexer.
 */
public sealed interface GrammarToken {
    SourceSpan span();

    // Identifiers and literals
    record Identifier(SourceSpan span, String name) implements GrammarToken {}

    record StringLiteral(SourceSpan span, String value, boolean caseInsensitive) implements GrammarToken {}

    record CharClassLiteral(SourceSpan span, String pattern, boolean negated, boolean caseInsensitive) implements GrammarToken {}

    record ActionCode(SourceSpan span, String code) implements GrammarToken {}

    record Number(SourceSpan span, int value) implements GrammarToken {}

    // Operators
    record LeftArrow(SourceSpan span) implements GrammarToken {}

    // <-
    record Slash(SourceSpan span) implements GrammarToken {}

    // /
    record Ampersand(SourceSpan span) implements GrammarToken {}

    // &
    record Exclamation(SourceSpan span) implements GrammarToken {}

    // !
    record Question(SourceSpan span) implements GrammarToken {}

    // ?
    record Star(SourceSpan span) implements GrammarToken {}

    // *
    record Plus(SourceSpan span) implements GrammarToken {}

    // +
    record Dot(SourceSpan span) implements GrammarToken {}

    // .
    record Tilde(SourceSpan span) implements GrammarToken {}

    // ~
    record Cut(SourceSpan span) implements GrammarToken {}

    // ^ or ↑
    record Pipe(SourceSpan span) implements GrammarToken {}

    // | (dictionary)
    // Delimiters
    record LParen(SourceSpan span) implements GrammarToken {}

    // (
    record RParen(SourceSpan span) implements GrammarToken {}

    // )
    record LAngle(SourceSpan span) implements GrammarToken {}

    // <
    record RAngle(SourceSpan span) implements GrammarToken {}

    // >
    record LBrace(SourceSpan span) implements GrammarToken {}

    // {
    record RBrace(SourceSpan span) implements GrammarToken {}

    // }
    record Comma(SourceSpan span) implements GrammarToken {}

    // ,
    record Dollar(SourceSpan span) implements GrammarToken {}

    // $
    // Special
    record Directive(SourceSpan span, String name) implements GrammarToken {}

    // %name
    record Eof(SourceSpan span) implements GrammarToken {}

    record Error(SourceSpan span, String message) implements GrammarToken {}
}
