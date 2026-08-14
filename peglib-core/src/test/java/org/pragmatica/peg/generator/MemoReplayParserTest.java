package org.pragmatica.peg.generator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.cst.CstArray;

/**
 * 0.7.1 — end-to-end {@code %memo} directive: the generated parser replays a
 * memoised subtree instead of re-parsing it.
 *
 * <p>The grammar mirrors the JLS 14.8 statement-expression shape that motivated
 * the feature: {@code Stmt} first tries {@code Call '=' Num ';'}, and when the
 * {@code '='} fails after a fully parsed call, backtracks and re-parses the same
 * call through the second alternative. With {@code %memo Args} the argument
 * list — parsed at the same token position both times — is salvaged on the
 * backtrack and replayed.
 *
 * <p>Replay must be observationally identical to re-parsing, so every case is
 * asserted by comparing the full CST (preorder kind/token-span signature)
 * against the SAME grammar without the directive. {@code Item} is recursive to
 * force PARSER classification — a non-recursive rule referencing only lexer
 * rules would be folded into the lexer.
 */
class MemoReplayParserTest {

    private static final String MEMO_GRAMMAR = """
        %whitespace <- [ \\t\\r\\n]*
        Start <- Stmt+
        Stmt <- Call '=' Num ';' / Call ';'
        Call <- Ident '(' Args? ')'
        Args <- Item (',' Item)*
        Item <- Ident '(' Args? ')' / Ident
        Num <- [0-9]+
        Ident <- [a-z]+
        %memo Args
        """;

    private static final String PLAIN_GRAMMAR = MEMO_GRAMMAR.replace("%memo Args\n", "");

    @Test
    void callStatement_replayPath_parsesCleanly() {
        assertSameParse("foo(a,b);");
    }

    @Test
    void assignmentStatement_noReplay_parsesCleanly() {
        assertSameParse("foo(a,b)=1;");
    }

    @Test
    void nestedCallArguments_replayCoversNestedSubtree() {
        assertSameParse("foo(bar(a,b),c);");
    }

    @Test
    void mixedStatementSequence_replaysPerStatement() {
        assertSameParse("foo(a,bar(c),d)=1; baz(e); qux();");
    }

    @Test
    void grammarWithNamedCaptures_memoSilentlyDisabled_stillParses() {
        // Replay would skip capture-map registration, so the generator ignores
        // %memo entirely for capture-bearing grammars. Behaviour must be
        // unchanged: the capture still constrains the parse.
        var grammar = """
            %whitespace <- [ \\t]*
            %memo Args
            Start <- Stmt+
            Stmt <- Call '=' $v<Num> ';' / Call ';'
            Call <- Ident '(' Args? ')'
            Args <- Item (',' Item)*
            Item <- Ident '(' Args? ')' / Ident
            Num <- [0-9]+
            Ident <- [a-z]+
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();
        var result = parser.parse("foo(a); bar(b)=1;");

        assertThat(result.diagnostics())
        .isEmpty();
    }

    private void assertSameParse(String input) {
        var memoResult = PegParser.fromGrammar(MEMO_GRAMMAR).unwrap().parse(input);
        var plainResult = PegParser.fromGrammar(PLAIN_GRAMMAR).unwrap().parse(input);

        assertThat(memoResult.diagnostics())
        .as("memoised parse of %s", input)
        .isEmpty();
        assertThat(plainResult.diagnostics())
        .as("plain parse of %s", input)
        .isEmpty();
        assertThat(memoResult.cst().reconstruct())
        .isEqualTo(input);
        assertThat(signature(memoResult.cst(), memoResult.cst().rootIndex()))
        .as("replayed CST must be structurally identical to the re-parsed CST")
        .isEqualTo(signature(plainResult.cst(), plainResult.cst().rootIndex()));
    }

    private String signature(CstArray cst, int nodeIdx) {
        var sb = new StringBuilder();

        sb.append('(')
          .append(cst.kindAt(nodeIdx))
          .append(':')
          .append(cst.firstTokenAt(nodeIdx))
          .append('-')
          .append(cst.lastTokenAt(nodeIdx));
        cst.children(nodeIdx)
           .forEach(child -> sb.append(signature(cst, child)));

        return sb.append(')')
                 .toString();
    }
}
