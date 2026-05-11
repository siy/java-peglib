package org.pragmatica.peg.v6.cst;

import org.pragmatica.peg.v6.diagnostic.Diagnostic;
import org.pragmatica.peg.v6.diagnostic.Severity;
import org.pragmatica.peg.v6.token.TokenArrayBuilder;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pragmatica.peg.v6.token.TokenArray.FIRST_USER_KIND;

class ParseResultTest {
    private static final int KIND_ROOT = 0;

    private static final String[] RULE_TABLE = {"Root"};

    private static final int TOK_X = FIRST_USER_KIND;

    private static final String[] TOKEN_NAMES = {"WHITESPACE", "LINE_COMMENT", "BLOCK_COMMENT", "X"};

    @Test
    void isSuccess_returnsTrue_whenDiagnosticsEmpty() {
        var result = new ParseResult(emptyCst(), List.of());
        assertThat(result.isSuccess())
        .isTrue();
        assertThat(result.hasErrors())
        .isFalse();
    }

    @Test
    void isSuccess_returnsFalse_whenAnyDiagnosticPresent() {
        var info = new Diagnostic(Severity.INFO, 0, 1, "note", "", "");
        var result = new ParseResult(emptyCst(), List.of(info));
        assertThat(result.isSuccess())
        .isFalse();
    }

    @Test
    void hasErrors_returnsTrue_whenAnyErrorSeverityPresent() {
        var warning = new Diagnostic(Severity.WARNING, 0, 1, "warn", "", "");
        var error = Diagnostic.error(2, 1, "boom");
        var result = new ParseResult(emptyCst(), List.of(warning, error));
        assertThat(result.hasErrors())
        .isTrue();
    }

    @Test
    void hasErrors_returnsFalse_whenOnlyWarningOrInfoDiagnostics() {
        var warning = new Diagnostic(Severity.WARNING, 0, 1, "warn", "", "");
        var info = new Diagnostic(Severity.INFO, 0, 1, "note", "", "");
        var result = new ParseResult(emptyCst(), List.of(warning, info));
        assertThat(result.hasErrors())
        .isFalse();
        assertThat(result.isSuccess())
        .isFalse();
    }

    @Test
    void diagnostics_areDefensivelyCopied_andUnmodifiable() {
        var mutable = new ArrayList<Diagnostic>();
        mutable.add(Diagnostic.error(0, 1, "first"));
        var result = new ParseResult(emptyCst(), mutable);
        mutable.add(Diagnostic.error(1, 1, "added after construction"));
        assertThat(result.diagnostics())
        .hasSize(1);
        assertThatThrownBy(() -> result.diagnostics()
                                       .add(Diagnostic.error(2, 1, "x")))
        .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void diagnostics_preserveOrder() {
        var a = Diagnostic.error(0, 1, "a");
        var b = new Diagnostic(Severity.WARNING, 1, 1, "b", "", "");
        var c = new Diagnostic(Severity.INFO, 2, 1, "c", "", "");
        var result = new ParseResult(emptyCst(), List.of(a, b, c));
        assertThat(result.diagnostics())
        .containsExactly(a, b, c);
    }

    @Test
    void nullCst_isRejected() {
        assertThatThrownBy(() -> new ParseResult(null,
                                                 List.of()))
        .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullDiagnostics_isRejected() {
        assertThatThrownBy(() -> new ParseResult(emptyCst(),
                                                 null))
        .isInstanceOf(NullPointerException.class);
    }

    private static CstArray emptyCst() {
        var input = "x";
        var tb = new TokenArrayBuilder(input);
        tb.append(TOK_X, 0, 1);
        var tokens = tb.build(TOKEN_NAMES);
        var b = new CstArrayBuilder(input, tokens, RULE_TABLE);
        var root = b.beginNode(KIND_ROOT, 0, CstArray.NO_NODE);
        b.endNode(root, 0);
        return b.build(root);
    }
}
