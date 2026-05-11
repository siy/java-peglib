package org.pragmatica.peg.v6.lexer;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.v6.PegParser;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.6.0 — regression tests for non-ASCII handling and block-comment routing.
 *
 * <h2>Fix 1: Non-ASCII transitions</h2>
 *
 * <p>Before 0.6.0 the DFA driver hard-stopped on any character {@code >= 256}.
 * Now {@code .} (Any) and negated CharClass {@code [^...]} also produce a
 * per-state non-ASCII edge consumed via {@link Dfa#nonAsciiTransition(int)}.
 * Positive classes like {@code [a-z]} remain ASCII-only by definition.
 *
 * <h2>Fix 2: Asymmetric delimited blocks</h2>
 *
 * <p>{@code compileDelimitedBlock} previously required {@code open.equals(close)}
 * (good for {@code """ ... """}). For block comments {@code  slash-star ... star-slash}
 * the open and close differ, so the special path was skipped and the rule fell
 * back to the standard sequence path, which failed on the body's Not-predicate.
 * The fix allows asymmetric delimiters; the KMP body loop tracks the CLOSE
 * delimiter only, since that's what we have to detect.
 */
class UnicodeAndCommentsTest {

    private static org.pragmatica.peg.v6.Parser java25Parser() throws Exception {
        var grammar = Files.readString(Path.of("src/test/resources/java25.peg"));
        return PegParser.fromGrammar(grammar).unwrap();
    }

    private static void assertClean(org.pragmatica.peg.v6.Parser parser, String input) {
        var result = parser.parse(input);
        assertThat(result.diagnostics())
        .as("expected clean parse, got %d diagnostics; first: %s",
            result.diagnostics().size(),
            result.diagnostics().isEmpty() ? "<none>" : result.diagnostics().get(0))
        .isEmpty();
    }

    @Test
    void blockComment_pureAscii_parsesClean() throws Exception {
        var parser = java25Parser();
        assertClean(parser, "public class C { /* ascii comment */ void m() {} }");
    }

    @Test
    void blockComment_withEmDash_parsesClean() throws Exception {
        var parser = java25Parser();
        // U+2014 em-dash inside a /* ... */ block. Pre-fix, the lexer's DFA
        // stalled on the em-dash because the body-of-comment NFA construction
        // didn't accept non-ASCII bytes; with Fix 1 the per-state non-ASCII
        // transition keeps the body-loop alive.
        assertClean(parser, "public class C { /* block with em-dash — */ void m() {} }");
    }

    @Test
    void stringLiteral_withEmDash_parsesClean() throws Exception {
        var parser = java25Parser();
        // String literal body uses '.' (Any) for content chars; non-ASCII bytes
        // must transition correctly.
        assertClean(parser, "public class C { String s = \"em-dash —\"; }");
    }

    @Test
    void stringLiteral_withSmartQuotes_parsesClean() throws Exception {
        var parser = java25Parser();
        // U+201C / U+201D smart double quotes inside a Java string literal.
        assertClean(parser, "public class C { String s = \"“hello”\"; }");
    }

    @Test
    void stringLiteral_withCjk_parsesClean() throws Exception {
        var parser = java25Parser();
        // CJK ideographs — well into the BMP-plus range.
        assertClean(parser, "public class C { String s = \"你好\"; }");
    }

    @Test
    void lineComment_withEmDash_parsesClean() throws Exception {
        var parser = java25Parser();
        assertClean(parser, "public class C { // line with em-dash —\n }");
    }

    @Test
    void tripleSlashMarkdownComment_withEmDash_parsesClean() throws Exception {
        var parser = java25Parser();
        // /// JavaDoc-style line comment containing non-ASCII.
        assertClean(parser, "public class C { /// markdown — text\n }");
    }

    @Test
    void blockComment_asStandaloneRule_recognisedAsSingleToken() {
        // Direct test of compileDelimitedBlock on an asymmetric /* ... */ shape.
        // Pre-fix: the rule body's Not-predicate caused the DFA build to fail
        // (open != close so compileDelimitedBlock returned None). Post-fix:
        // it builds a KMP scanner. Verify the resulting lexer emits a single
        // token covering the entire block.
        var grammar = """
                      BlockComment <- '/*' (!'*/' .)* '*/'
                      Ident <- < [a-zA-Z_]+ >
                      %whitespace <- [ \\t\\r\\n]*
                      """;
        var parsed = GrammarParser.parse(grammar).unwrap();
        var classification = RuleClassifier.classify(parsed).unwrap();
        var built = DfaBuilder.build(parsed, classification).unwrap();
        // The build must have succeeded — pre-fix it would have appeared in
        // skipped[] with an UnsupportedExpression(Not).
        assertThat(built.skipped())
        .as("BlockComment rule must NOT be skipped — compileDelimitedBlock should recognise it")
        .isEmpty();
        var engine = new LexerEngine(built.dfa(),
                                     built.kinds().kindNameTable(),
                                     DfaBuilder.KIND_WHITESPACE,
                                     built.kinds().keywordResolutions());
        var input = "x /* hi */ y";
        var tokens = engine.lex(input);
        boolean sawBlockComment = false;
        for (int i = 0; i < tokens.count(); i++) {
            if (tokens.textAt(i).toString().equals("/* hi */")) {
                sawBlockComment = true;
                break;
            }
        }
        assertThat(sawBlockComment)
        .as("lexer must emit a token spanning '/* hi */' exactly")
        .isTrue();
    }

    @Test
    void blockComment_asStandaloneRule_withEmDashBody_recognised() {
        // Same as above; body contains a non-ASCII em-dash. Pre-fix the body
        // loop hard-stopped at the em-dash; post-fix Fix 1 wires up a non-ASCII
        // edge from each body state back to state[0] so the loop continues.
        var grammar = """
                      BlockComment <- '/*' (!'*/' .)* '*/'
                      Ident <- < [a-zA-Z_]+ >
                      %whitespace <- [ \\t\\r\\n]*
                      """;
        var parsed = GrammarParser.parse(grammar).unwrap();
        var classification = RuleClassifier.classify(parsed).unwrap();
        var built = DfaBuilder.build(parsed, classification).unwrap();
        var engine = new LexerEngine(built.dfa(),
                                     built.kinds().kindNameTable(),
                                     DfaBuilder.KIND_WHITESPACE,
                                     built.kinds().keywordResolutions());
        var input = "x /* em-dash — */ y";
        var tokens = engine.lex(input);
        boolean sawBlockComment = false;
        for (int i = 0; i < tokens.count(); i++) {
            var text = tokens.textAt(i).toString();
            if (text.startsWith("/*") && text.endsWith("*/") && text.indexOf('—') >= 0) {
                sawBlockComment = true;
                break;
            }
        }
        assertThat(sawBlockComment)
        .as("lexer must emit a single token spanning the em-dash-bearing block comment")
        .isTrue();
    }

    @Test
    void dfa_nonAsciiTransition_anyAcceptsBmpPlus() {
        // A rule whose body is '.' must accept any character including non-ASCII.
        var grammar = "Wild <- .\n";
        var parsed = GrammarParser.parse(grammar).unwrap();
        var classification = RuleClassifier.classify(parsed).unwrap();
        var built = DfaBuilder.build(parsed, classification).unwrap();
        var dfa = built.dfa();
        // After a non-ASCII char, the lexer should have followed a non-ASCII
        // transition rather than stalled.
        var engine = new LexerEngine(dfa,
                                     built.kinds().kindNameTable(),
                                     -1,
                                     built.kinds().keywordResolutions());
        var tokens = engine.lex("—");
        assertThat(tokens.count())
        .as("Any rule should match a single em-dash as one token")
        .isEqualTo(1);
        assertThat(tokens.textAt(0).toString())
        .isEqualTo("—");
    }

    @Test
    void dfa_nonAsciiTransition_positiveClassRejectsBmpPlus() {
        // A positive class [a-z] must NOT match non-ASCII. The lexer falls
        // back to the 1-char WHITESPACE synthetic token.
        var grammar = "Letter <- [a-z]\n";
        var parsed = GrammarParser.parse(grammar).unwrap();
        var classification = RuleClassifier.classify(parsed).unwrap();
        var built = DfaBuilder.build(parsed, classification).unwrap();
        var engine = new LexerEngine(built.dfa(),
                                     built.kinds().kindNameTable(),
                                     -1,
                                     built.kinds().keywordResolutions());
        var tokens = engine.lex("—");
        // The non-ASCII byte couldn't match [a-z] so the engine emits a
        // synthetic WHITESPACE-recovery token. The point: no crash, no infinite
        // loop, positive class still ASCII-only.
        assertThat(tokens.count())
        .isEqualTo(1);
        assertThat(tokens.kindAt(0))
        .isEqualTo(org.pragmatica.peg.v6.token.TokenArray.KIND_WHITESPACE);
    }

    @Test
    void dfa_nonAsciiTransition_negatedClassAcceptsBmpPlus() {
        // Negated class [^x] must accept non-ASCII.
        var grammar = "NotX <- [^x]\n";
        var parsed = GrammarParser.parse(grammar).unwrap();
        var classification = RuleClassifier.classify(parsed).unwrap();
        var built = DfaBuilder.build(parsed, classification).unwrap();
        var engine = new LexerEngine(built.dfa(),
                                     built.kinds().kindNameTable(),
                                     -1,
                                     built.kinds().keywordResolutions());
        var tokens = engine.lex("—");
        assertThat(tokens.count())
        .isEqualTo(1);
        assertThat(tokens.textAt(0).toString())
        .isEqualTo("—");
    }

    @Test
    void factoryClassGeneratorFixture_parsesClean() throws Exception {
        // Anchor regression: the FactoryClassGenerator.java.txt fixture used to
        // produce 13,529 diagnostics. With Fix 1 + Fix 2 it must parse cleanly.
        var parser = java25Parser();
        var input = Files.readString(Path.of("src/test/resources/perf-corpus/large/FactoryClassGenerator.java.txt"));
        var result = parser.parse(input);
        assertThat(result.diagnostics())
        .as("FactoryClassGenerator.java.txt must parse with zero diagnostics; got %d",
            result.diagnostics().size())
        .isEmpty();
    }
}
