package org.pragmatica.peg;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import org.pragmatica.peg.grammar.GrammarResolver;
import org.pragmatica.peg.grammar.GrammarSource;
import org.pragmatica.peg.grammar.Rule;

/**
 * 0.7.2 — {@code %import} composition end to end: a root grammar declaring imports
 * must produce a working {@link Parser}.
 *
 * <p>Before 0.7.2 the directive parsed and {@code GrammarResolver} existed, but nothing
 * connected them: {@link PegParser#fromGrammar(String)} went straight from
 * {@code GrammarParser.parse} to classification, so imported rules were never brought in
 * and the grammar failed with a misleading {@code references undefined rule}. The
 * resolver was exercised only at the {@code Grammar} level, never through to a parser.
 *
 * <p>Naming, per {@link org.pragmatica.peg.grammar.Import}: an unaliased
 * {@code %import G.R} exposes the rule as {@code G_R}; {@code as Local} exposes it as
 * {@code Local}. Both forms are covered here because the grammar-qualified default is
 * easy to get wrong.
 */
class ImportCompositionTest {

    private static final String SHARED = """
        Ident <- [a-z]+
        Number <- [0-9]+
        """;

    /** Unaliased imports — rules arrive under their grammar-qualified names. */
    private static final String ROOT_PREFIXED = """
        %import Shared.Ident
        %import Shared.Number
        Start <- Entry+
        Entry <- Shared_Ident '=' Shared_Number ';'
        %whitespace <- [ \\t\\r\\n]*
        """;

    /** Aliased imports — rules arrive under the local names given by {@code as}. */
    private static final String ROOT_ALIASED = """
        %import Shared.Ident as Name
        %import Shared.Number as Value
        Start <- Entry+
        Entry <- Name '=' Value ';'
        %whitespace <- [ \\t\\r\\n]*
        """;

    private static GrammarSource source() {
        return GrammarSource.inMemory(Map.of("Shared", SHARED));
    }

    @Test
    void unaliasedImport_producesAWorkingParser() {
        var result = PegParser.fromGrammar(ROOT_PREFIXED, source())
                              .unwrap()
                              .parse("a = 1; bb = 22;");

        assertThat(result.diagnostics())
        .as("imported Shared_Ident / Shared_Number must parse")
        .isEmpty();
        assertThat(result.isSuccess())
        .isTrue();
        assertThat(result.cst()
                         .reconstruct())
        .isEqualTo("a = 1; bb = 22;");
    }

    @Test
    void aliasedImport_isUsableUnderItsLocalName() {
        var result = PegParser.fromGrammar(ROOT_ALIASED, source())
                              .unwrap()
                              .parse("a = 1; bb = 22;");

        assertThat(result.diagnostics())
        .as("aliased Name / Value must parse")
        .isEmpty();
        assertThat(result.cst()
                         .reconstruct())
        .isEqualTo("a = 1; bb = 22;");
    }

    @Test
    void importedGrammar_stillRejectsInvalidInput() {
        // Guards the tests above from passing vacuously: if the imported rules were
        // silently permissive, everything would parse.
        var parser = PegParser.fromGrammar(ROOT_PREFIXED, source())
                              .unwrap();

        assertThat(parser.parse("a = ;")
                         .diagnostics())
        .as("malformed entry must still be diagnosed")
        .isNotEmpty();
    }

    @Test
    void unaliasedName_isNotBoundBareIntoTheRootGrammar() {
        // %import Shared.Ident binds Shared_Ident, NOT Ident. Pinning this because the
        // opposite is the natural assumption and was wrong in the old docs.
        var root = """
            %import Shared.Ident
            Start <- Entry+
            Entry <- Ident ';'
            %whitespace <- [ \\t]*
            """;

        var result = PegParser.fromGrammar(root, source());

        assertThat(result.isSuccess())
        .as("bare 'Ident' must not resolve without an 'as' alias")
        .isFalse();
        result.onFailure(cause -> assertThat(cause.message())
                                  .as("the failure must be the unresolved bare name")
                                  .contains("Ident"));
    }

    @Test
    void missingImport_failsWithGrammarNotFound() {
        var result = PegParser.fromGrammar(ROOT_PREFIXED, GrammarSource.empty());

        assertThat(result.isSuccess())
        .as("empty source cannot satisfy %%import")
        .isFalse();
        // Assert WHY it failed: isSuccess()==false alone would also pass if the grammar
        // broke for some unrelated reason.
        result.onFailure(cause -> assertThat(cause.message())
                                  .contains("Shared")
                                  .contains("not found"));
    }

    @Test
    void importWithoutSource_failsWithActionableMessage() {
        var result = PegParser.fromGrammar(ROOT_PREFIXED);

        assertThat(result.isSuccess())
        .isFalse();
        result.onFailure(cause -> assertThat(cause.message())
                                  .as("the failure must name the fix, not report a phantom undefined rule")
                                  .contains("%import")
                                  .contains("GrammarSource")
                                  .doesNotContain("references undefined rule"));
    }

    @Test
    void grammarWithoutImports_twoArgFormMatchesOneArgForm() {
        var plain = """
            Start <- Entry+
            Entry <- Ident '=' Number ';'
            Ident <- [a-z]+
            Number <- [0-9]+
            %whitespace <- [ \\t\\r\\n]*
            """;
        var viaSource = PegParser.fromGrammar(plain, GrammarSource.empty())
                                 .unwrap()
                                 .parse("a = 1;");
        var viaText = PegParser.fromGrammar(plain)
                               .unwrap()
                               .parse("a = 1;");

        assertThat(viaSource.cst()
                            .nodeCount())
        .isEqualTo(viaText.cst()
                          .nodeCount());
        assertThat(viaSource.diagnostics())
        .isEmpty();
    }

    @Test
    void nestedImport_resolvesThroughTwoLevels() {
        // Root -> Mid -> Leaf. Mid's own rule references a name that only Mid's import
        // provides, so this only works if Mid is composed before Root takes its closure.
        var leaf = """
            Thing <- [0-9]+
            """;
        var mid = """
            %import Leaf.Thing
            Something <- Leaf_Thing '+' Leaf_Thing
            """;
        var root = """
            %import Mid.Something as Sum
            Start <- Sum (',' Sum)*
            %whitespace <- [ \t]*
            """;
        var source = GrammarSource.inMemory(Map.of("Leaf", leaf, "Mid", mid));
        var result = PegParser.fromGrammar(root, source)
                              .unwrap()
                              .parse("1+2, 3+4");

        assertThat(result.diagnostics())
        .as("a rule reached through two levels of %%import must parse")
        .isEmpty();
        assertThat(result.cst()
                         .reconstruct())
        .isEqualTo("1+2, 3+4");
    }

    @Test
    void nestedImport_diamondDuplicatesTheSharedLeaf() {
        // KNOWN LIMITATION, pinned deliberately. Prefixing is path-dependent: a rule pulled
        // through two different import paths arrives twice, as Left_Leaf_Atom and
        // Right_Leaf_Atom. Composition succeeds, but two identical LEXER rules mean the first
        // wins at tokenization and the second branch never matches.
        //
        // Fixing this needs the resolver to track where a rule was DEFINED rather than the path
        // taken to reach it, so both paths converge on one name. That is a design change, not a
        // patch — until then, avoid diamonds over lexer rules.
        var leaf = """
            Atom <- [0-9]+
            """;
        var left = """
            %import Leaf.Atom
            Left <- Leaf_Atom '<'
            """;
        var right = """
            %import Leaf.Atom
            Right <- Leaf_Atom '>'
            """;
        var root = """
            %import Left.Left as L
            %import Right.Right as R
            Start <- (L / R)+
            %whitespace <- [ \t]*
            """;
        var source = GrammarSource.inMemory(Map.of("Leaf", leaf, "Left", left, "Right", right));

        assertThat(GrammarResolver.resolveText(root, source)
                                  .unwrap()
                                  .rules()
                                  .stream()
                                  .map(Rule::name))
        .as("the shared leaf arrives once per import path")
        .contains("Left_Leaf_Atom", "Right_Leaf_Atom");
        // Composition itself still succeeds — the duplication is a parse-time consequence.
        assertThat(PegParser.fromGrammar(root, source)
                            .isSuccess())
        .isTrue();
    }

    @Test
    void nestedImport_cycleIsRejectedNotStackOverflow() {
        var a = """
            %import B.RuleB
            RuleA <- 'a' B_RuleB
            """;
        var b = """
            %import A.RuleA
            RuleB <- 'b' A_RuleA
            """;
        var root = """
            %import A.RuleA as Entry
            Start <- Entry
            """;
        var result = PegParser.fromGrammar(root, GrammarSource.inMemory(Map.of("A", a, "B", b)));

        assertThat(result.isSuccess())
        .isFalse();
        result.onFailure(cause -> assertThat(cause.message())
                                  .as("a nested cycle must be reported, not blow the stack")
                                  .contains("Cyclic"));
    }

    @Test
    void transitiveClosure_pullsRulesTheImportedRuleReferences() {
        var lib = """
            Entry <- Key '=' Val
            Key <- [a-z]+
            Val <- [0-9]+
            """;
        var root = """
            %import Lib.Entry as Pair
            Start <- Pair (';' Pair)*
            %whitespace <- [ \\t]*
            """;
        var result = PegParser.fromGrammar(root, GrammarSource.inMemory(Map.of("Lib", lib)))
                              .unwrap()
                              .parse("a = 1; b = 2");

        assertThat(result.diagnostics())
        .as("Key and Val must be pulled in transitively behind Pair")
        .isEmpty();
        assertThat(result.cst()
                         .reconstruct())
        .isEqualTo("a = 1; b = 2");
    }
}
