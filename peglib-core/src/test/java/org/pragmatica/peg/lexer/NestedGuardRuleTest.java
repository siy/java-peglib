package org.pragmatica.peg.lexer;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.grammar.GrammarParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.7.3 — a guarded rule whose body names another guarded rule.
 *
 * <p>{@code WindowName <- !PartitionKW ColId} where {@code ColId <- !ReservedKeyword (…)}. Both
 * the handover and CLAUDE.md carried this for two releases as an unsolved limitation, on the
 * strength of a mechanism that is real — {@code resolveSkipBody} does refuse the body, because
 * inlining {@code ColId} drags its own {@code !ReservedKeyword} in and the DFA cannot compile
 * lookahead — but whose consequence had never been measured.
 *
 * <p>The consequence is a silent fall back to PARSER, and the PARSER reading is CORRECT. These
 * tests pin that, because the recorded conclusion was that this shape was broken and the fix was
 * to promote the rule to LEXER by composing the guard sets. That promotion was attempted twice
 * and reverted twice; the second attempt was diagnosed (2026-08-22) and the finding is that it
 * breaks 36 tests through exactly one rule — {@code PlainTypeName <- !RestrictedTypeName
 * Identifier} in {@code java25.peg}, which is this same shape. Promoted to LEXER it
 * out-prioritises {@code Identifier}, every identifier in Java lexes as {@code PlainTypeName},
 * and the grammar fails at offset 0.
 *
 * <p>So these tests exist to stop the "fix" being attempted a third time. What they assert is
 * that the current behaviour is already right.
 */
class NestedGuardRuleTest {
    private static final String GRAMMAR = """
        Doc             <- WindowName ';'
        WindowName      <- !PartitionKW ColId
        ColId           <- !ReservedKeyword < [a-z]+ >
        PartitionKW     <- 'partition' ![a-z0-9_]
        ReservedKeyword <- ('select' / 'from') ![a-z0-9_]
        %whitespace     <- [ \\t\\r\\n]*
        """;

    private static boolean accepts(String input) {
        return PegParser.fromGrammar(GRAMMAR)
                        .unwrap()
                        .parse(input)
                        .diagnostics()
                        .isEmpty();
    }

    @Test
    void theGrammarCompiles() {
        assertThat(PegParser.fromGrammar(GRAMMAR)
                            .isSuccess())
        .withFailMessage("a guarded rule naming another guarded rule must still compile")
        .isTrue();
    }

    @Test
    void theOuterGuardFires() {
        // !PartitionKW, applied at the token level because WindowName is a PARSER rule.
        assertThat(accepts("partition ;")).isFalse();
    }

    @Test
    void theInnerGuardFires() {
        // !ReservedKeyword, carried by ColId. This is the guard the "limitation" claimed was lost.
        assertThat(accepts("select ;")).isFalse();
        assertThat(accepts("from ;")).isFalse();
    }

    @Test
    void anOrdinaryIdentifierIsStillAccepted() {
        // The control: guards that reject everything would pass all three tests above.
        assertThat(accepts("hello ;")).isTrue();
    }

    @Test
    void theRuleFallsBackToParserRatherThanFusing() {
        // Pins the MECHANISM, not just the outcome. If someone promotes this shape to LEXER, the
        // behaviour tests above may still pass on this small grammar while java25 collapses —
        // that is exactly how the change got attempted twice. Fail here instead, at the cause.
        var classification = RuleClassifier.classify(GrammarParser.parse(GRAMMAR)
                                                                  .unwrap())
                                           .unwrap();

        assertThat(classification.kinds())
        .withFailMessage("WindowName must stay PARSER: promoting this shape to LEXER makes it "
                        + "out-prioritise the guarded rule it names, which is what breaks java25 "
                        + "(PlainTypeName shadowing Identifier). See CLAUDE.md.")
        .containsEntry("WindowName", RuleKind.PARSER);
        assertThat(classification.kinds()).containsEntry("ColId", RuleKind.LEXER);
    }
}
