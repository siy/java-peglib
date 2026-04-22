package org.pragmatica.peg.incremental;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.incremental.internal.BackReferenceScan;
import org.pragmatica.peg.incremental.internal.CstHash;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §6.3 / §10 back-reference mitigation: rules containing a
 * {@link org.pragmatica.peg.grammar.Expression.BackReference} (or
 * transitively depending on one) must fall back to full reparse on every
 * edit. Parity still holds — the difference is the
 * {@link Stats#fullReparseCount()} bump.
 */
final class BackReferenceFallbackTest {

    private static final String GRAMMAR_WITH_BACKREF = """
        Tag <- '<' $name<Ident> '>' Body '</' $name '>'
        Body <- (!'<' .)*
        Ident <- < [a-zA-Z]+ >
        %whitespace <- [ \\t\\n]*
        """;

    private static Grammar grammar() {
        return GrammarParser.parse(GRAMMAR_WITH_BACKREF).fold(
            cause -> { throw new IllegalStateException(cause.message()); }, g -> g);
    }

    @Test
    @DisplayName("BackReferenceScan flags the rule using a capture")
    void scan_flags_unsafe() {
        var unsafe = BackReferenceScan.unsafeRules(grammar());
        assertThat(unsafe).contains("Tag");
    }

    @Test
    @DisplayName("Edit inside back-ref-bearing rule falls back to full reparse")
    void edit_triggers_full_reparse() {
        var parser = IncrementalParser.create(grammar());
        var s0 = parser.initialize("<x>content</x>");
        var s1 = s0.edit(3, 0, "ab");
        assertThat(s1.stats().fullReparseCount()).isEqualTo(1);
        // Parity still holds.
        var oracle = PegParser.fromGrammar(grammar()).fold(
            cause -> { throw new IllegalStateException(cause.message()); }, p -> p);
        var oracleTree = oracle.parseCst(s1.text()).unwrap();
        assertThat(CstHash.of(s1.root())).isEqualTo(CstHash.of(oracleTree));
    }

    @Test
    @DisplayName("Grammar without back-refs has empty unsafe set")
    void scan_empty_when_no_backrefs() {
        var g = GrammarParser.parse("""
            Program <- Stmt*
            Stmt <- 'x' ';'
            """).fold(cause -> { throw new IllegalStateException(cause.message()); }, r -> r);
        assertThat(BackReferenceScan.unsafeRules(g)).isEmpty();
    }

    @Test
    @DisplayName("Transitive dependency on back-ref rule propagates unsafe flag")
    void transitive_unsafe() {
        var g = GrammarParser.parse("""
            Wrapper <- Inner
            Inner <- $n<Ident> $n
            Ident <- < [a-z]+ >
            """).fold(cause -> { throw new IllegalStateException(cause.message()); }, r -> r);
        var unsafe = BackReferenceScan.unsafeRules(g);
        assertThat(unsafe).contains("Inner");
        assertThat(unsafe).contains("Wrapper");
        assertThat(unsafe).doesNotContain("Ident");
    }
}
