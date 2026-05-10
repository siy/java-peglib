package org.pragmatica.peg.v6.lexer;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.grammar.GrammarParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class DfaBuilderTest {

    private record Match(int end, int kind, int priority) {
        boolean isMatch() {
            return kind != Dfa.NO_ACCEPT;
        }
    }

    private static Match simulate(Dfa dfa, String input, int from) {
        int state = Dfa.START_STATE;
        int lastKind = Dfa.NO_ACCEPT;
        int lastPriority = -1;
        int lastEnd = from;
        if (Dfa.START_STATE < dfa.stateCount()) {
            int startKind = dfa.acceptKind(Dfa.START_STATE);
            if (startKind != Dfa.NO_ACCEPT) {
                lastKind = startKind;
                lastPriority = dfa.acceptPriority(Dfa.START_STATE);
                lastEnd = from;
            }
        }
        for (int i = from; i < input.length(); i++) {
            int ch = input.charAt(i);
            int next = dfa.transition(state, ch);
            if (next == Dfa.NO_TRANSITION) {
                break;
            }
            state = next;
            int kind = dfa.acceptKind(state);
            if (kind != Dfa.NO_ACCEPT) {
                lastKind = kind;
                lastPriority = dfa.acceptPriority(state);
                lastEnd = i + 1;
            }
        }
        return new Match(lastEnd, lastKind, lastPriority);
    }

    private static DfaBuilder.Built build(String grammarText) {
        var grammar = GrammarParser.parse(grammarText).unwrap();
        var classification = RuleClassifier.classify(grammar).unwrap();
        return DfaBuilder.build(grammar, classification).unwrap();
    }

    /**
     * Phase B.5 invariant: a LEXER rule whose body contains literals has those
     * literals lifted to INLINE_<text> kinds and added to the rule's alias array.
     * The DFA's accept state is tagged with the inline-literal kind (not the rule
     * kind), and the parser's alias-match check treats either kind as acceptable
     * for that rule. Tests that simulated single-rule DFAs and asserted the DFA
     * returned the rule kind must instead assert that the DFA returned the rule
     * kind OR any kind in the rule's alias set.
     */
    private static void assertAcceptedAsRuleOrAlias(DfaBuilder.Built built,
                                                    String ruleName,
                                                    int actualKind,
                                                    int ruleKind) {
        if (actualKind == ruleKind) {
            return;
        }
        var aliasMap = built.kinds().ruleNameToAliasKinds();
        int[] aliases = aliasMap.get(ruleName);
        assertThat(aliases)
            .as("rule '%s' must have an alias set when DFA returns kind %d != ruleKind %d",
                ruleName, actualKind, ruleKind)
            .isNotNull();
        boolean inAliases = false;
        for (int k : aliases) {
            if (k == actualKind) {
                inAliases = true;
                break;
            }
        }
        assertThat(inAliases)
            .as("rule '%s' actual kind %d (%s) not in alias set %s nor equal to rule kind %d",
                ruleName, actualKind,
                actualKind < built.kinds().kindNameTable().length
                    ? built.kinds().kindNameTable()[actualKind] : "?",
                java.util.Arrays.toString(aliases), ruleKind)
            .isTrue();
    }

    @Test
    void singleLiteral_acceptsExactMatchOnly() {
        var built = build("Word <- 'abc'\n");
        int wordKind = built.kinds().ruleNameToKind().get("Word");

        var match = simulate(built.dfa(), "abc", 0);
        assertThat(match.isMatch()).isTrue();
        assertThat(match.end()).isEqualTo(3);
        // Phase B.5 — body literal 'abc' is allocated as an INLINE_<text> kind and
        // also added to Word's alias array. The DFA accepts via the inline-literal
        // kind; the parser's alias-match check accepts either the rule kind or any
        // alias kind. Assert membership in {wordKind} ∪ aliasKinds.
        assertAcceptedAsRuleOrAlias(built, "Word", match.kind(), wordKind);

        var partial = simulate(built.dfa(), "ab", 0);
        assertThat(partial.isMatch()).isFalse();
    }

    @Test
    void digitClassPlus_acceptsOneOrMore() {
        var built = build("Number <- [0-9]+\n");
        int numberKind = built.kinds().ruleNameToKind().get("Number");

        assertThat(simulate(built.dfa(), "0", 0).isMatch()).isTrue();
        assertThat(simulate(built.dfa(), "0", 0).end()).isEqualTo(1);
        assertThat(simulate(built.dfa(), "42", 0).end()).isEqualTo(2);
        assertThat(simulate(built.dfa(), "999abc", 0).end()).isEqualTo(3);
        assertThat(simulate(built.dfa(), "999abc", 0).kind()).isEqualTo(numberKind);

        var empty = simulate(built.dfa(), "", 0);
        assertThat(empty.isMatch()).isFalse();
    }

    @Test
    void twoRulesLongestMatchPicksHex() {
        var built = build("""
            Hex <- '0x' [0-9a-fA-F]+
            Number <- [0-9]+
            """);
        int hexKind = built.kinds().ruleNameToKind().get("Hex");
        int numberKind = built.kinds().ruleNameToKind().get("Number");

        var hexMatch = simulate(built.dfa(), "0x1f", 0);
        assertThat(hexMatch.isMatch()).isTrue();
        assertThat(hexMatch.end()).isEqualTo(4);
        assertThat(hexMatch.kind()).isEqualTo(hexKind);

        var numberMatch = simulate(built.dfa(), "42", 0);
        assertThat(numberMatch.isMatch()).isTrue();
        assertThat(numberMatch.end()).isEqualTo(2);
        assertThat(numberMatch.kind()).isEqualTo(numberKind);
    }

    @Test
    void priorityFirstDefinedWins() {
        var built = build("""
            IfKeyword <- 'if'
            Identifier <- [a-z]+
            """);
        int ifKind = built.kinds().ruleNameToKind().get("IfKeyword");
        int idKind = built.kinds().ruleNameToKind().get("Identifier");

        // PEG first-match-wins: at "if" both rules can accept; IfKeyword (defined first) wins.
        // Note: longest-match for "ifoo" prefers the longer Identifier match; "if" alone yields IfKeyword.
        // Phase B.5 — IfKeyword's body literal 'if' is allocated as an INLINE_if kind
        // and added to IfKeyword's alias array; the DFA accepts via the inline kind.
        var ifMatch = simulate(built.dfa(), "if", 0);
        assertThat(ifMatch.isMatch()).isTrue();
        assertThat(ifMatch.end()).isEqualTo(2);
        assertAcceptedAsRuleOrAlias(built, "IfKeyword", ifMatch.kind(), ifKind);

        // Identifier has no literal in body, so its rule kind is what the DFA returns.
        var ifoo = simulate(built.dfa(), "ifoo", 0);
        assertThat(ifoo.isMatch()).isTrue();
        assertThat(ifoo.end()).isEqualTo(4);
        assertThat(ifoo.kind()).isEqualTo(idKind);
    }

    @Test
    void caseInsensitiveLiteralAcceptsAllCases() {
        var built = build("Bool <- 'true'i\n");
        int boolKind = built.kinds().ruleNameToKind().get("Bool");

        // Phase B.5 — body literal 'true'i is allocated as an INLINE_true_CI kind
        // and added to Bool's alias array; the DFA accepts via the inline kind.
        for (var input : new String[]{"true", "TRUE", "True", "tRuE"}) {
            var m = simulate(built.dfa(), input, 0);
            assertThat(m.isMatch())
                .as("input '%s' should match", input)
                .isTrue();
            assertThat(m.end()).isEqualTo(4);
            assertAcceptedAsRuleOrAlias(built, "Bool", m.kind(), boolKind);
        }
    }

    @Test
    void negatedCharClassMatchesAnyExcluded() {
        var built = build("StringBody <- [^\"]+\n");
        int kind = built.kinds().ruleNameToKind().get("StringBody");

        var m = simulate(built.dfa(), "hello world", 0);
        assertThat(m.isMatch()).isTrue();
        assertThat(m.end()).isEqualTo(11);
        assertThat(m.kind()).isEqualTo(kind);

        var quoteOnly = simulate(built.dfa(), "\"", 0);
        assertThat(quoteOnly.isMatch()).isFalse();

        var stops = simulate(built.dfa(), "abc\"def", 0);
        assertThat(stops.end()).isEqualTo(3);
    }

    @Test
    void zeroOrMoreAcceptsEmpty() {
        var built = build("""
            Word <- [a-z]*
            Anchor <- 'X'
            """);
        int wordKind = built.kinds().ruleNameToKind().get("Word");

        // Start state accepts empty string for Word (kind = wordKind, priority 0).
        var empty = simulate(built.dfa(), "", 0);
        assertThat(empty.isMatch()).isTrue();
        assertThat(empty.kind()).isEqualTo(wordKind);
        assertThat(empty.end()).isEqualTo(0);

        var word = simulate(built.dfa(), "abc", 0);
        assertThat(word.isMatch()).isTrue();
        assertThat(word.end()).isEqualTo(3);
        assertThat(word.kind()).isEqualTo(wordKind);
    }

    @Test
    void boundedRepetitionAcceptsRange() {
        var built = build("Hex <- [0-9]{2,4}\n");
        int hexKind = built.kinds().ruleNameToKind().get("Hex");

        var two = simulate(built.dfa(), "12", 0);
        assertThat(two.isMatch()).isTrue();
        assertThat(two.end()).isEqualTo(2);
        assertThat(two.kind()).isEqualTo(hexKind);

        var three = simulate(built.dfa(), "123", 0);
        assertThat(three.end()).isEqualTo(3);

        var four = simulate(built.dfa(), "1234", 0);
        assertThat(four.end()).isEqualTo(4);

        // Longest-match takes 4 chars on "12345"; the trailing "5" isn't consumed.
        var five = simulate(built.dfa(), "12345", 0);
        assertThat(five.isMatch()).isTrue();
        assertThat(five.end()).isEqualTo(4);

        var one = simulate(built.dfa(), "1", 0);
        assertThat(one.isMatch()).isFalse();
    }

    @Test
    void java25GrammarBuildsDfa() throws IOException {
        var grammarText = Files.readString(
            Paths.get("src/test/resources/java25.peg"),
            StandardCharsets.UTF_8);
        var grammar = GrammarParser.parse(grammarText).unwrap();
        var classification = RuleClassifier.classify(grammar).unwrap();

        var built = DfaBuilder.build(grammar, classification).unwrap();
        var dfa = built.dfa();

        assertThat(dfa.stateCount()).isGreaterThan(0);
        assertThat(built.kinds().ruleNameToKind()).isNotEmpty();
        boolean anyAccepting = false;
        for (int i = 0; i < dfa.stateCount(); i++) {
            if (dfa.acceptKind(i) != Dfa.NO_ACCEPT) {
                anyAccepting = true;
                break;
            }
        }
        assertThat(anyAccepting).isTrue();

        System.out.println("[DfaBuilder:java25] state count = " + dfa.stateCount()
            + ", lexer rules = " + built.kinds().ruleNameToKind().size()
            + ", kind table size = " + built.kinds().kindNameTable().length
            + ", skipped (char-level fallback) = " + built.skipped().size());
        for (var s : built.skipped()) {
            System.out.println("  - skipped: " + s.ruleName());
        }
    }
}
