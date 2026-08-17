package org.pragmatica.peg.generator;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.lexer.RuleClassifier;
import org.pragmatica.peg.lexer.DfaBuilder;
import org.pragmatica.peg.grammar.GrammarParser;

/**
 * 0.7.2 — two generator defects reported from a 753-line PostgreSQL grammar. Both produced
 * source that {@code mvn generate-sources} emitted happily and {@code javac} then rejected.
 */
class LargeGrammarScalingTest {

    /**
     * Case-insensitive literals spelled differently must resolve to ONE token kind.
     *
     * <p>Two bugs in one: the generated constants collided
     * ({@code variable KIND_INLINE_TIME_CI is already defined}), and even uniquely named they
     * would have been two kinds for identical input — the lexer tags that text with one of
     * them, so every parser site testing the other is permanently dead.
     */
    @Test
    void caseInsensitiveLiteralsDifferingOnlyInCase_shareOneKind() {
        var grammar = """
            Root     <- Item (',' Item)*
            Item     <- TimeType / TimeKW
            TimeType <- < 'time'i ![a-zA-Z0-9_$] > ('(' Digits ')')?
            TimeKW   <- < 'TIME'i ![a-zA-Z0-9_$] >
            Digits   <- < [0-9]+ >
            %whitespace <- [ \\t\\r\\n]*
            """;
        var parsed = GrammarParser.parse(grammar)
                                  .unwrap();
        var classification = RuleClassifier.classify(parsed)
                                           .unwrap();
        var built = DfaBuilder.build(parsed, classification)
                              .unwrap();
        var inlineTimeKinds = java.util.Arrays.stream(built.kinds()
                                                           .kindNameTable())
                                              .filter(name -> name.toLowerCase(java.util.Locale.ROOT)
                                                                  .startsWith("inline_time"))
                                              .toList();

        // The heart of it: 'time'i and 'TIME'i match identical input, so they must be ONE kind.
        // Two kinds means the lexer can only ever tag that text with one of them, leaving every
        // parser site testing the other permanently dead — a silent correctness bug, not just
        // the duplicate-constant compile error it happened to surface as.
        assertThat(inlineTimeKinds)
        .as("both spellings must collapse onto a single token kind")
        .hasSize(1);
    }

    /** Case-SENSITIVE literals differing only in case remain distinct tokens. */
    @Test
    void caseSensitiveLiteralsDifferingOnlyInCase_stayDistinct() {
        // The inline ',' / '!' / '?' literals keep Root and Item PARSER-classified; a grammar
        // built purely from lexical rules would be folded into the lexer and rejected as a
        // start rule, which is a separate constraint and not what this test is about.
        var grammar = """
            Root  <- Item (',' Item)*
            Item  <- Lower '!' / Upper '?'
            Lower <- < 'time' ![a-zA-Z0-9_$] >
            Upper <- < 'TIME' ![a-zA-Z0-9_$] >
            %whitespace <- [ \\t\\r\\n]*
            """;
        var parser = PegParser.fromGrammar(grammar)
                              .unwrap();

        assertThat(parser.parse("time!, TIME?")
                         .diagnostics())
        .as("'time' and 'TIME' are different tokens and must both match")
        .isEmpty();
    }

    /**
     * A grammar far larger than the old ceiling must still produce compilable sources.
     *
     * <p>The transition table used to be emitted as inline {@code t[i]=v;} assignments, so every
     * index and value beyond {@code sipush} range took a constant-pool slot. The pool caps at
     * 65535, which bounded grammars at roughly 1100 DFA states — beyond that
     * {@code error: too many constants}. {@code fromGrammar} compiles in memory, so this test
     * fails outright if the ceiling returns.
     */
    @Test
    void grammarWellBeyondTheOldStateCeiling_stillCompiles() {
        var keywords = IntStream.range(0, 600)
                                .mapToObj(LargeGrammarScalingTest::keyword)
                                .collect(Collectors.joining(" / "));
        // The choice is inlined into Root: a separate `Item <- Kw / Ident` rule is purely
        // lexical and gets demoted into the lexer, which the start rule cannot reference.
        var grammar = "Root <- (Kw / Ident) (';' (Kw / Ident))*\n"
                     + "Kw <- < (" + keywords + ") ![a-zA-Z0-9_$] >\n"
                     + "Ident <- < [a-zA-Z_][a-zA-Z0-9_]* >\n"
                     + "%whitespace <- [ \\t\\r\\n]*\n";
        var parser = PegParser.fromGrammar(grammar)
                              .unwrap();
        var input = unquoted(0) + "; " + unquoted(599) + "; someIdentifier";

        assertThat(parser.parse(input)
                         .diagnostics())
        .as("a grammar several times the old ceiling must compile and lex")
        .isEmpty();
    }

    /** Deterministic distinct lowercase words, long enough to force many DFA states. */
    private static String unquoted(int i) {
        var q = keyword(i);

        return q.substring(1, q.length() - 1);
    }

    private static String keyword(int i) {
        var sb = new StringBuilder("kw");

        for (var n = i; ; n /= 26) {
            sb.append((char) ('a' + (n % 26)));

            if (n < 26) {
                break;
            }
        }

        return "'" + sb + "'";
    }
}
