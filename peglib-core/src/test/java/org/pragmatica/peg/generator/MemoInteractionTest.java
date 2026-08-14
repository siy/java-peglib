package org.pragmatica.peg.generator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.cst.CstArray;
import org.pragmatica.peg.incremental.IncrementalParser;

/**
 * 0.7.1 — {@code %memo} against the two engine features it shares state with.
 *
 * <p>{@link MemoReplayParserTest} proves replay is observationally identical on
 * clean input under the default sync set. Two interactions were argued to be
 * safe rather than tested, and this closes both:
 *
 * <ol>
 *   <li><b>Per-rule {@code %recover}.</b> A memoised subtree can contain an
 *       {@code Error} node produced by panic-mode recovery. Replay re-splices
 *       those nodes but does not re-record the diagnostic, so a divergence here
 *       would show up as a differing diagnostic count or CST shape.</li>
 *   <li><b>Incremental reparse.</b> {@code CstArrayBuilder} is constructed fresh
 *       per parse, so memo state should not survive into a later reparse. The
 *       failure mode if it did is stale node indices pointing into a spliced
 *       tree, so an incremental edit is compared against a full reparse of the
 *       same final text.</li>
 * </ol>
 *
 * <p>Every case is asserted against the SAME grammar without the directive —
 * the directive is an optimisation, so any observable difference is a bug.
 */
class MemoInteractionTest {

    private static final String RECOVER_MEMO = """
        %whitespace <- [ \\t\\r\\n]*
        Start <- Stmt+
        Stmt <- Call '=' Num ';' / Call ';'
        Call <- Ident '(' Args? ')'
        Args <- Item (',' Item)*
        Item <- Ident '(' Args? ')' / Ident
        Num <- [0-9]+
        Ident <- [a-z]+
        %recover [;] Args
        %memo Args
        """;

    private static final String RECOVER_PLAIN = RECOVER_MEMO.replace("%memo Args\n", "");

    private static final String INCREMENTAL_MEMO = """
        %whitespace <- [ \\t\\r\\n]*
        Start <- Stmt+
        Stmt <- Call '=' Num ';' / Call ';'
        Call <- Ident '(' Args? ')'
        Args <- Item (',' Item)*
        Item <- Ident '(' Args? ')' / Ident
        Num <- [0-9]+
        Ident <- [a-z]+
        %checkpoint Stmt
        %memo Args
        """;

    @Test
    void perRuleRecoverSyncSet_memoMatchesPlain_onMalformedArgs() {
        assertSameParse("foo(a,); bar(b);");
        assertSameParse("foo(a b); bar(c);");
        assertSameParse("foo(a,,b) = 1; baz(c);");
        assertSameParse("foo(bar(a,), c) = 1; qux(d);");
    }

    @Test
    void perRuleRecoverSyncSet_stillReportsDiagnostics() {
        // Guards the assertion above from passing vacuously: if recovery stopped
        // firing entirely both sides would agree at zero diagnostics and prove
        // nothing about replaying an Error-bearing subtree.
        var result = PegParser.fromGrammar(RECOVER_MEMO)
                              .unwrap()
                              .parse("foo(a,); bar(b);");

        assertThat(result.diagnostics())
        .as("malformed argument list must still produce diagnostics under %%memo")
        .isNotEmpty();
    }

    @Test
    void incrementalEdit_withMemoActive_matchesFullReparse() {
        var parser = PegParser.fromGrammar(INCREMENTAL_MEMO)
                              .unwrap();
        var initial = "foo(a,b); bar(c) = 1; baz(d,e,f);";
        var incremental = new IncrementalParser(parser, initial);
        // Rewrite 'c' -> 'zz' inside the middle statement's argument list, which
        // is exactly the memoised rule and sits in the double-parse shape.
        var offset = initial.indexOf("bar(c)") + 4;

        incremental.edit(offset, 1, "zz");
        var edited = initial.replace("bar(c)", "bar(zz)");

        assertThat(incremental.input())
        .isEqualTo(edited);

        var full = parser.parse(edited);

        assertThat(incremental.diagnostics()
                              .size())
        .as("incremental diagnostics must match a full reparse")
        .isEqualTo(full.diagnostics()
                       .size());
        assertThat(incremental.current()
                              .reconstruct())
        .as("incremental reconstruct must match the edited source")
        .isEqualTo(edited);
        assertThat(signature(incremental.current()))
        .as("incremental CST with memo active must match a full reparse")
        .isEqualTo(signature(full.cst()));
    }

    @Test
    void repeatedIncrementalEdits_withMemoActive_stayConsistent() {
        var parser = PegParser.fromGrammar(INCREMENTAL_MEMO)
                              .unwrap();
        var incremental = new IncrementalParser(parser, "foo(a,b); bar(c); baz(d);");

        incremental.edit(4, 1, "xx");
        incremental.edit(incremental.input()
                                    .indexOf("bar(c)") + 4, 1, "yy");
        incremental.edit(incremental.input()
                                    .indexOf("baz(d)") + 4, 1, "zz");
        var finalText = incremental.input();
        var full = parser.parse(finalText);

        assertThat(finalText)
        .isEqualTo("foo(xx,b); bar(yy); baz(zz);");
        assertThat(signature(incremental.current()))
        .as("memo state must not survive across successive incremental reparses")
        .isEqualTo(signature(full.cst()));
    }

    private void assertSameParse(String input) {
        var memo = PegParser.fromGrammar(RECOVER_MEMO)
                            .unwrap()
                            .parse(input);
        var plain = PegParser.fromGrammar(RECOVER_PLAIN)
                             .unwrap()
                             .parse(input);

        assertThat(memo.diagnostics()
                       .size())
        .as("diagnostic count for %s", input)
        .isEqualTo(plain.diagnostics()
                        .size());
        assertThat(memo.cst()
                       .reconstruct())
        .as("round-trip for %s", input)
        .isEqualTo(input);
        assertThat(memo.cst()
                       .nodeCount())
        .as("node count for %s", input)
        .isEqualTo(plain.cst()
                        .nodeCount());
        assertThat(signature(memo.cst()))
        .as("replayed CST must be structurally identical to the re-parsed CST for %s", input)
        .isEqualTo(signature(plain.cst()));
    }

    private String signature(CstArray cst) {
        var sb = new StringBuilder();

        appendSignature(cst, cst.rootIndex(), sb);

        return sb.toString();
    }

    private void appendSignature(CstArray cst, int nodeIdx, StringBuilder sb) {
        sb.append('(')
          .append(cst.kindAt(nodeIdx))
          .append(':')
          .append(cst.firstTokenAt(nodeIdx))
          .append('-')
          .append(cst.lastTokenAt(nodeIdx));
        cst.children(nodeIdx)
           .forEach(child -> appendSignature(cst, child, sb));
        sb.append(')');
    }
}
