package org.pragmatica.peg.error;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.tree.CstNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5/6 (0.3.5) — proof regression test for the {@code %recover '<term>'}
 * rule-level directive (introduced 0.2.4).
 *
 * <p>The audit recorded in {@code docs/HANDOVER.md} §8.2 observed that the
 * earlier proof attempt ({@link RuleRecoveryTest#recoverDirective_shiftsRecoveryLandingPoint})
 * could not distinguish override-vs-default behaviour: on input {@code "{x@@@}"}
 * with grammar {@code Block <- '{' Stmt '}' %recover "}"}, both parsers produced
 * identical diagnostics. The hypothesis: either the override is never consulted,
 * or the test scenario doesn't exercise a path where it can take effect.
 *
 * <p>This test discriminates on a recovery character ({@code ':'}) that is
 * <em>not</em> in the default recovery char-set {@code (',' ';' '}' ')' ']' '\n')}.
 * Two grammars differ only by the {@code %recover} directive on the start rule;
 * on the same malformed input they MUST produce different recovery spans —
 * default recovery overshoots to EOF (no default char in input), override
 * recovery stops at {@code ':'}.
 */
class RecoverDirectiveProofTest {

    /**
     * Grammar without {@code %recover}: default recovery scans for
     * {@code , ; } ) ] \n}. Input contains none of these, so recovery skips
     * the entire remainder to EOF.
     *
     * <p>Grammar with {@code %recover ":"}: recovery stops at the first
     * {@code ':'} character, leaving the suffix {@code ":rest"} unparsed.
     *
     * <p>Asserting on the {@code skippedText} of the emitted Error node makes
     * the difference visible: a no-op directive would produce identical
     * skipped-text in both branches.
     */
    @Test
    void recoverDirectiveShiftsLandingPointToOverrideTerminator() {
        var defaultGrammar = """
            Doc <- 'a' Body
            Body <- 'x' 'y' 'z'
            """;
        var overrideGrammar = """
            Doc <- 'a' Body %recover ":"
            Body <- 'x' 'y' 'z'
            """;

        // Position 0 = 'a' matches; position 1 = Body fails on 'Q'. Doc fails,
        // pos restored to 0. Recovery starts at offset 0. Default skips to EOF
        // (no recovery char). Override skips to ':' at offset 4.
        var input = "aQQQ:rest";

        var defaultParser = PegParser.builder(defaultGrammar)
                                     .recovery(RecoveryStrategy.ADVANCED)
                                     .build()
                                     .onFailure(c -> Assertions.fail(c.message()))
                                     .unwrap();
        var overrideParser = PegParser.builder(overrideGrammar)
                                      .recovery(RecoveryStrategy.ADVANCED)
                                      .build()
                                      .onFailure(c -> Assertions.fail(c.message()))
                                      .unwrap();

        var defaultResult = defaultParser.parseCstWithDiagnostics(input);
        var overrideResult = overrideParser.parseCstWithDiagnostics(input);

        assertThat(defaultResult.hasErrors())
            .as("default grammar must report errors")
            .isTrue();
        assertThat(overrideResult.hasErrors())
            .as("override grammar must report errors")
            .isTrue();

        var defaultErrors = collectErrors(defaultResult.node()
                                                        .unwrap());
        var overrideErrors = collectErrors(overrideResult.node()
                                                          .unwrap());

        // Default recovery: no recovery char in input, so a single Error node
        // spans the entire remainder.
        assertThat(defaultErrors)
            .as("default recovery should produce one Error spanning to EOF")
            .hasSize(1);
        assertThat(defaultErrors.get(0)
                                .skippedText())
            .as("default recovery skipped text == full input (no recovery char in default set)")
            .isEqualTo("aQQQ:rest");

        // Override recovery: stops at ':' for the first cycle. parseWithRecovery
        // then advances past ':' and loops; the remainder 'rest' fails to parse
        // and produces a second Error. The first error's skipped text is the
        // proof that the override took effect.
        assertThat(overrideErrors)
            .as("override recovery stops at ':' on first cycle — should produce >= 1 Error")
            .isNotEmpty();
        assertThat(overrideErrors.get(0)
                                  .skippedText())
            .as("first Error's skippedText must end at the ':' override terminator")
            .isEqualTo("aQQQ");

        // The crisp differentiator: first-error skipped text differs between
        // default and override, proving the directive shifts behaviour.
        assertThat(overrideErrors.get(0)
                                  .skippedText())
            .as("override and default must produce DIFFERENT first-error spans")
            .isNotEqualTo(defaultErrors.get(0)
                                        .skippedText());
    }

    /**
     * Walk the CST and return every {@link CstNode.Error} in document order.
     */
    private static java.util.List<CstNode.Error> collectErrors(CstNode node) {
        var out = new java.util.ArrayList<CstNode.Error>();
        collectErrorsInto(node, out);
        return out;
    }

    private static void collectErrorsInto(CstNode node, java.util.List<CstNode.Error> out) {
        switch (node) {
            case CstNode.Error err -> out.add(err);
            case CstNode.NonTerminal nt -> nt.children()
                                             .forEach(child -> collectErrorsInto(child, out));
            default -> {}
        }
    }
}
