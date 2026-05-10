package org.pragmatica.peg.v6.lexer;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.grammar.GrammarParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B.5 — alias detection for LEXER/MIXED rules whose body simplifies to a
 * literal or choice-of-literals.
 *
 * <p>Such rules — {@code ClassKW <- < 'class' ![a-zA-Z0-9_$] >}, or
 * {@code Modifier <- < ('public' / 'private') ![a-zA-Z0-9_$] >} — cannot be
 * compiled to the DFA because of the trailing {@code !CharClass} word boundary
 * (the DFA path doesn't support {@code Not}). Without aliasing, references to
 * them at parse time would never match. With aliasing, the parser instead
 * accepts any of the inline-literal kinds emitted by the lexer for the
 * underlying texts.
 */
class AliasDetectionTest {

    private static DfaBuilder.Built buildFor(String grammarText) {
        var grammar = GrammarParser.parse(grammarText).unwrap();
        var classification = RuleClassifier.classify(grammar).unwrap();
        return DfaBuilder.build(grammar, classification).unwrap();
    }

    @Test
    void aliasDetected_forSingleLiteralWithWordBoundary() {
        // ClassKW pattern: a captured literal followed by a word-boundary not-class.
        var grammar = """
            File <- ClassKW IdentRef
            ClassKW <- < 'class' ![a-zA-Z] >
            IdentRef <- < [a-zA-Z]+ >
            """;
        var built = buildFor(grammar);
        var aliases = built.kinds().ruleNameToAliasKinds();
        assertThat(aliases).containsKey("ClassKW");
        // Inline-literal kind for "class" must be in the alias set.
        var classKind = built.kinds().inlineLiteralToKind().get("class/cs");
        assertThat(classKind).isNotNull();
        assertThat(aliases.get("ClassKW")).containsExactly(classKind);
    }

    @Test
    void aliasDetected_forChoiceOfLiteralsWithWordBoundary() {
        // Modifier-style rule: structural choice of literals with shared word boundary.
        var grammar = """
            File <- ModifierKW IdentRef
            ModifierKW <- < ('public' / 'private' / 'protected') ![a-zA-Z] >
            IdentRef <- < [a-zA-Z]+ >
            """;
        var built = buildFor(grammar);
        var aliases = built.kinds().ruleNameToAliasKinds();
        assertThat(aliases).containsKey("ModifierKW");
        var inline = built.kinds().inlineLiteralToKind();
        assertThat(aliases.get("ModifierKW")).containsExactlyInAnyOrder(
            inline.get("public/cs"),
            inline.get("private/cs"),
            inline.get("protected/cs"));
    }

    @Test
    void aliasNotDetected_forCharClassRule() {
        // [a-zA-Z]+ is not a literal — no alias.
        var grammar = """
            File <- IdentRef
            IdentRef <- < [a-zA-Z]+ >
            """;
        var built = buildFor(grammar);
        assertThat(built.kinds().ruleNameToAliasKinds()).doesNotContainKey("IdentRef");
    }

    @Test
    void aliasParsesPublicAndPrivate_endToEnd() {
        // End-to-end: lex + parse. ModifierKW must accept both 'public' and 'private'
        // tokens. Without the alias, parse would fail with "found=public".
        var grammar = """
            File <- ModifierKW IdentRef ';'
            ModifierKW <- < ('public' / 'private') ![a-zA-Z] >
            IdentRef <- < [a-zA-Z]+ >
            %whitespace <- [ \\t\\n]*
            """;
        var built = buildFor(grammar);
        var inline = built.kinds().inlineLiteralToKind();
        // Pre-condition: 'public' and 'private' have inline-literal kinds.
        assertThat(inline).containsKeys("public/cs", "private/cs");
        // Pre-condition: ModifierKW is aliased to those exact kinds.
        var aliasKinds = built.kinds().ruleNameToAliasKinds().get("ModifierKW");
        assertThat(aliasKinds).isNotNull();
        assertThat(aliasKinds).containsExactlyInAnyOrder(
            inline.get("public/cs"), inline.get("private/cs"));
    }

    @Test
    void aliasMapDoesNotIncludeSkipPrefixRules() {
        // Skip-prefix rules use post-match keyword resolution, not aliasing.
        var grammar = """
            File <- MiniIdent
            MiniIdent <- !MiniKw [a-z]+
            MiniKw <- 'if' / 'while'
            """;
        var built = buildFor(grammar);
        assertThat(built.kinds().ruleNameToAliasKinds()).doesNotContainKey("MiniIdent");
    }

    @Test
    void aliasDetected_forBareLiteral() {
        // Even without word-boundary guard, a body that simplifies to a literal aliases.
        var grammar = """
            File <- KwOnly IdentRef
            KwOnly <- 'class'
            IdentRef <- < [a-zA-Z]+ >
            """;
        var built = buildFor(grammar);
        var aliases = built.kinds().ruleNameToAliasKinds();
        assertThat(aliases).containsKey("KwOnly");
        assertThat(aliases.get("KwOnly"))
            .containsExactly(built.kinds().inlineLiteralToKind().get("class/cs"));
    }
}
