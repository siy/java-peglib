package org.pragmatica.peg.grammar;

/**
 * 0.7.3 — Grammar-level {@code %nest '<open>' '<close>'} directive: one delimiter pair whose
 * occurrences nest, lexed by a depth-counting scanner instead of a DFA path.
 *
 * <p>Form: {@code %nest '/*' '*&#47;'}. Both delimiters are string literals and neither may be
 * empty. The directive may appear more than once; each occurrence contributes an independent
 * pair.
 *
 * <h2>Why a delimiter pair and not a rule name</h2>
 *
 * <p>The natural spelling for a nesting comment is a recursive rule —
 * {@code BlockComment <- '/*' (BlockComment / !'*&#47;' .)* '*&#47;'} — and it cannot work
 * here for two independent reasons. The analyzer refuses it as
 * {@code grammar.whitespace-cycle} because the rule is reachable from {@code %whitespace} and
 * transitively references itself; and were it accepted, a nested comment is not a regular
 * language, so the DFA could not express it either. Naming a rule instead would therefore mean
 * recovering the delimiters from a rule body that cannot be written. The directive states them
 * outright.
 *
 * <p>Consequently a {@code %nest} pair stands alone: it does not require, and is not required
 * by, a matching {@code %whitespace} alternative. When both are present the counting scanner
 * takes precedence at a token start and the DFA alternative remains as the reading for input
 * the scanner declines (see {@code NestingScanner} on unterminated input).
 *
 * <h2>Trivia kind</h2>
 *
 * <p>The emitted token's kind is decided from the open delimiter by the same leading-literal
 * rule that assigns kinds to {@code %whitespace} alternatives, so {@code '/*'} yields a block
 * comment and the doc-variant refinement still applies to the matched text. An open delimiter
 * that resembles no comment prefix — Haskell's <code>{-</code>, ML's {@code (*} — yields plain
 * whitespace trivia, which is the correct classification for a language whose block comments
 * carry no doc convention peglib knows about.
 */
public record NestingPair(String open, String close) {}
