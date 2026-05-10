package org.pragmatica.peg.v6.lexer;

/**
 * Classification of a grammar rule for the 0.6.0 lex-then-parse pipeline.
 *
 * <ul>
 *   <li>{@link #LEXER} — purely lexical: literals, character classes, quantifiers,
 *       ordered choice over the same; no references to non-lexer rules. Compiles
 *       into the DFA token producer.</li>
 *   <li>{@link #PARSER} — combinator over rule references. Compiles to recursive
 *       descent over the {@code TokenArray}.</li>
 *   <li>{@link #MIXED} — parser-shaped (uses references) but also exercises
 *       character-level constructs ({@code .}, {@code [..]}, char-level
 *       {@code &}/{@code !}). Emits a per-rule char fallback path; warning
 *       suggests refactoring.</li>
 * </ul>
 */
public enum RuleKind {
    LEXER,
    PARSER,
    MIXED
}
