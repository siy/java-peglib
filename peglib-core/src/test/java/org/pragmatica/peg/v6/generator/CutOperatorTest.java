package org.pragmatica.peg.v6.generator;

import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.v6.cst.ParseResult;
import org.pragmatica.peg.v6.lexer.DfaBuilder;
import org.pragmatica.peg.v6.lexer.LexerEngine;
import org.pragmatica.peg.v6.lexer.RuleClassifier;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase #6 — Cut operator semantics in the generated parser.
 *
 * <p>Cut ({@code ^} or {@code ↑}) commits to the current Choice alternative.
 * Once a Cut is encountered, subsequent failures inside the same alternative
 * cause the enclosing Choice to fail rather than backtracking to try the next
 * alternative.
 *
 * <p>Without Cut, ordered choice's natural behaviour is to try the next
 * alternative after a partial-match failure. With Cut, that backtracking is
 * suppressed for the committed alternative.
 *
 * <p>Note: each grammar's start rule mixes a literal with a rule reference so
 * that {@link RuleClassifier} marks it PARSER (a body of pure literals would
 * be classified LEXER, which the parser generator rejects).
 */
class CutOperatorTest {
    private record Compiled(LexerEngine lexer, ParserCompiler.CompiledParser parser) {}

    private static Compiled compile(String grammarSrc, String pkg, String cls) {
        Grammar grammar = GrammarParser.parse(grammarSrc)
                                       .unwrap();
        var classification = RuleClassifier.classify(grammar)
                                           .unwrap();
        var built = DfaBuilder.build(grammar, classification)
                              .unwrap();
        int wsKind = grammar.whitespace()
                            .isPresent()
                     ? DfaBuilder.KIND_WHITESPACE
                     : - 1;
        var lexer = new LexerEngine(built.dfa(),
                                    built.kinds()
                                         .kindNameTable(),
                                    wsKind,
                                    built.kinds()
                                         .keywordResolutions());
        var generated = ParserGenerator.generate(grammar,
                                                 classification,
                                                 built.kinds(),
                                                 pkg,
                                                 cls)
                                       .unwrap();
        var parser = ParserCompiler.compile(generated)
                                   .unwrap();
        return new Compiled(lexer, parser);
    }

    /**
     * Baseline grammar without Cut: backtracking from the first alternative to
     * the second succeeds because PEG's ordered choice naturally retries.
     * <pre>
     *   R <- 'foo' Bar / 'foo' Baz
     *   Bar <- 'bar'
     *   Baz <- 'baz'
     * </pre>
     * Parsing "foo baz" must succeed: the first alt matches 'foo' then fails
     * on 'baz' != 'bar', so PEG backtracks and the second alt matches.
     */
    @Test
    void withoutCut_backtrackingTriesNextAlternative() {
        var c = compile("""
            R <- 'foo' Bar / 'foo' Baz
            Bar <- 'bar'
            Baz <- 'baz'
            %whitespace <- [ \\t]*
            """,
                        "test.gen.parser.cut",
                        "NoCutParser");
        var tokens = c.lexer()
                      .lex("foo baz");
        ParseResult result = c.parser()
                              .parse(tokens);
        assertThat(result.diagnostics())
        .isEmpty();
        assertThat(result.isSuccess())
        .isTrue();
    }

    /**
     * Same grammar shape but with Cut after the first 'foo': backtracking is
     * suppressed once the first alternative commits.
     * <pre>
     *   R <- 'foo' ^ Bar / 'foo' Baz
     * </pre>
     * Parsing "foo baz" must fail (the first alt commits at Cut, then 'baz'
     * != 'bar' fails the entire Choice — no retry of the second alt).
     */
    @Test
    void withCut_blocksBacktrackingAfterCommit() {
        var c = compile("""
            R <- 'foo' ^ Bar / 'foo' Baz
            Bar <- 'bar'
            Baz <- 'baz'
            %whitespace <- [ \\t]*
            """,
                        "test.gen.parser.cut",
                        "WithCutFailParser");
        var tokens = c.lexer()
                      .lex("foo baz");
        ParseResult result = c.parser()
                              .parse(tokens);
        // Cut prevented backtracking: the parse should NOT succeed cleanly.
        assertThat(result.hasErrors())
        .isTrue();
        assertThat(result.diagnostics())
        .isNotEmpty();
    }

    /**
     * The committed alternative still succeeds when its body matches:
     * Cut only suppresses backtracking on failure.
     */
    @Test
    void withCut_committedAlternativeStillSucceedsOnMatch() {
        var c = compile("""
            R <- 'foo' ^ Bar / 'foo' Baz
            Bar <- 'bar'
            Baz <- 'baz'
            %whitespace <- [ \\t]*
            """,
                        "test.gen.parser.cut",
                        "WithCutOkParser");
        var tokens = c.lexer()
                      .lex("foo bar");
        ParseResult result = c.parser()
                              .parse(tokens);
        assertThat(result.diagnostics())
        .isEmpty();
        assertThat(result.isSuccess())
        .isTrue();
    }

    /**
     * The first alt fails BEFORE Cut (at 'foo' != 'qux'), so backtracking to
     * the second alt is still permitted.
     */
    @Test
    void withCut_failureBeforeCutAllowsBacktracking() {
        var c = compile("""
            R <- 'foo' ^ Bar / 'qux' Baz
            Bar <- 'bar'
            Baz <- 'baz'
            %whitespace <- [ \\t]*
            """,
                        "test.gen.parser.cut",
                        "WithCutBeforeParser");
        var tokens = c.lexer()
                      .lex("qux baz");
        ParseResult result = c.parser()
                              .parse(tokens);
        assertThat(result.diagnostics())
        .isEmpty();
        assertThat(result.isSuccess())
        .isTrue();
    }

    /**
     * Cut nested inside an inner Choice should only affect the inner Choice,
     * leaving the outer Choice's backtracking intact. Grammar:
     * <pre>
     *   R <- ('foo' ^ Bar / 'qux' Zed) / 'foo' Baz
     * </pre>
     * Parsing "foo baz": the inner Choice's first alt commits at Cut and
     * fails on 'baz' != 'bar'; this suppresses the inner Choice's second alt
     * (so the inner Choice fails). The OUTER Choice should still try its
     * second alternative ('foo' Baz), which matches.
     */
    @Test
    void cutScopedToEnclosingChoice_outerChoiceStillBacktracks() {
        var c = compile("""
            R <- ('foo' ^ Bar / 'qux' Zed) / 'foo' Baz
            Bar <- 'bar'
            Baz <- 'baz'
            Zed <- 'zed'
            %whitespace <- [ \\t]*
            """,
                        "test.gen.parser.cut",
                        "WithNestedCutParser");
        var tokens = c.lexer()
                      .lex("foo baz");
        ParseResult result = c.parser()
                              .parse(tokens);
        assertThat(result.diagnostics())
        .isEmpty();
        assertThat(result.isSuccess())
        .isTrue();
    }

    /**
     * Cut at top level (no enclosing Choice) is a no-op — failures simply
     * propagate as normal. This exercises the "no enclosing Choice" branch
     * of {@code emitCut}. Parsing valid input should succeed.
     */
    @Test
    void cutWithoutEnclosingChoice_isNoOp() {
        var c = compile("""
            R <- 'if' ^ '(' Cond ')'
            Cond <- 'cond'
            %whitespace <- [ \\t]*
            """,
                        "test.gen.parser.cut",
                        "TopLevelCutParser");
        var tokens = c.lexer()
                      .lex("if (cond)");
        ParseResult result = c.parser()
                              .parse(tokens);
        assertThat(result.diagnostics())
        .isEmpty();
        assertThat(result.isSuccess())
        .isTrue();
    }

    /**
     * Top-level Cut on bad input still produces recovery diagnostics — the
     * Cut is a no-op so the failure propagates to the recovery loop normally.
     */
    @Test
    void cutWithoutEnclosingChoice_failuresStillReported() {
        var c = compile("""
            R <- 'if' ^ '(' Cond ')'
            Cond <- 'cond'
            %whitespace <- [ \\t]*
            """,
                        "test.gen.parser.cut",
                        "TopLevelCutFailParser");
        var tokens = c.lexer()
                      .lex("if cond)");
        ParseResult result = c.parser()
                              .parse(tokens);
        assertThat(result.hasErrors())
        .isTrue();
        assertThat(result.diagnostics())
        .isNotEmpty();
    }
}
