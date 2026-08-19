package org.pragmatica.peg.lexer;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.grammar.GrammarParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The identifier-fallback set of a guarded rule must be DISJOINT from the kinds its guard excludes.
 *
 * <p>{@code ColId <- !ReservedKeyword (…)} means "an identifier that is not a reserved word". If a
 * reserved word's kind is also offered as an identifier fallback, the guard is defeated and the
 * word is accepted as an identifier — silently, since the parse then succeeds down a wrong
 * alternative rather than failing.
 *
 * <p>Asserted as an invariant over kinds rather than as a parse outcome, deliberately: whether the
 * leak breaks a given parse depends on whether some other production competes for the text, so a
 * parse-based test passes on small grammars while the defect is fully present. Downstream this
 * measured as a 64-kind intersection where it must be 0.
 */
class FallbackGuardDisjointTest {

    /** Fallback kinds that the rule's own guard is supposed to exclude. */
    private static List<String> leakedKinds(String grammarText) {
        var grammar = GrammarParser.parse(grammarText)
                                   .unwrap();
        var classification = RuleClassifier.classify(grammar)
                                           .unwrap();
        var built = DfaBuilder.build(grammar, classification)
                              .unwrap();
        var kinds = built.kinds();
        var leaked = new ArrayList<String>();

        classification.keywordSkip()
                      .forEach((ruleName, info) -> {
                          var fallback = kinds.identifierFallbackKinds()
                                              .get(ruleName);

                          if (fallback == null) {
                              return;
                          }

                          for (var guardName : info.keywordRuleNames()) {
                              var guardKinds = kinds.ruleNameToAliasKinds()
                                                    .get(guardName);

                              if (guardKinds == null) {
                                  continue;
                              }

                              for (var kind : fallback) {
                                  if (Arrays.stream(guardKinds)
                                            .anyMatch(g -> g == kind)) {
                                      leaked.add(ruleName + " accepts " + kinds.kindNameTable()[kind]
                                                 + " which " + guardName + " excludes");
                                  }
                              }
                          }
                      });

        return leaked;
    }

    /**
     * Reserved words spelled UPPERCASE with a named {@code *KW} rule — PostgreSQL's shape. The
     * fallback set is built from the rule name's stem ({@code CreateKW} to {@code create}) and was
     * compared against the keyword set unfolded, so {@code create} never matched {@code CREATE}
     * and the reserved word was offered as an identifier.
     */
    @Test
    void guardedRuleDoesNotAcceptItsOwnReservedWords() {
        var grammar = """
            Stmt     <- CreateKW ColId / ColId
            ColId    <- !Reserved < [a-z] [a-z0-9_]* >
            Reserved <- ('CREATE'i / 'SELECT'i) ![a-z0-9_]
            CreateKW <- < 'CREATE'i ![a-z0-9_] >
            SelectKW <- < 'SELECT'i ![a-z0-9_] >
            %whitespace <- [ \\t\\r\\n]*
            """;

        assertThat(leakedKinds(grammar))
        .withFailMessage("a reserved word must not be offered as an identifier fallback")
        .isEmpty();
    }
}
