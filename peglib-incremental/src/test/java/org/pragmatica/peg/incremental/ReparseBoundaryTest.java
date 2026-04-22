package org.pragmatica.peg.incremental;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.incremental.internal.CstHash;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §6.3 edge-case matrix — hand-crafted buffers and edits with an
 * explicit parity check against a fresh full parse.
 */
final class ReparseBoundaryTest {

    // Permissive grammar: most boundary-expansion edge cases involve edits
    // that temporarily violate a stricter grammar. Using a permissive
    // grammar keeps the focus on the reparse-boundary algorithm (shape
    // changes, straddles, trivia-internal edits, etc.) rather than on
    // strict input-rejection semantics.
    private static final String GRAMMAR = """
        Program <- Token*
        Token <- Word / Punct
        Word <- < [a-zA-Z0-9_]+ >
        Punct <- < [=;+\\-*/(){}\\[\\].,:!<>] >
        %whitespace <- [ \\t\\n]*
        """;

    private static Grammar grammar() {
        return GrammarParser.parse(GRAMMAR).fold(
            cause -> { throw new IllegalStateException(cause.message()); },
            g -> g);
    }

    private static IncrementalParser parser() {
        return IncrementalParser.create(grammar());
    }

    private static void assertParity(Session session) {
        var oracle = PegParser.fromGrammar(grammar())
            .fold(cause -> { throw new IllegalStateException(cause.message()); }, p -> p);
        var oracleResult = oracle.parseCst(session.text());
        assertThat(oracleResult.isSuccess()).as("oracle must be able to parse %s", session.text()).isTrue();
        assertThat(CstHash.of(session.root()))
            .as("incremental parity against full reparse for buffer %s", session.text())
            .isEqualTo(CstHash.of(oracleResult.unwrap()));
    }

    @Test
    @DisplayName("Edit at buffer start")
    void edit_at_buffer_start() {
        var s = parser().initialize("let x = 1;").edit(0, 0, "let a = 99; ");
        assertParity(s);
    }

    @Test
    @DisplayName("Edit at buffer end")
    void edit_at_buffer_end() {
        var text = "let x = 1;";
        var s = parser().initialize(text).edit(text.length(), 0, " let y = 2;");
        assertParity(s);
    }

    @Test
    @DisplayName("Insert a whole new top-level rule")
    void insert_new_statement() {
        var s = parser().initialize("let x = 1;").edit(0, 0, "let new = 42; ");
        assertParity(s);
    }

    @Test
    @DisplayName("Delete a whole statement shrinks the tree")
    void delete_statement() {
        var s = parser().initialize("let x = 1; let y = 2;").edit(0, 11, "");
        assertParity(s);
    }

    @Test
    @DisplayName("Edit inside a number leaf")
    void edit_inside_number() {
        var s = parser().initialize("let x = 1;").edit(8, 1, "42");
        assertParity(s);
    }

    @Test
    @DisplayName("Edit inside an identifier leaf")
    void edit_inside_ident() {
        var s = parser().initialize("let x = 1;").edit(4, 1, "abc");
        assertParity(s);
    }

    @Test
    @DisplayName("Edit entirely within whitespace trivia")
    void edit_within_whitespace() {
        var s = parser().initialize("let x  =  1;").edit(7, 1, "   ");
        assertParity(s);
    }

    @Test
    @DisplayName("Edit that straddles two statements")
    void edit_straddles_statements() {
        var s = parser().initialize("let x = 1; let y = 2;").edit(9, 4, "99; let z = ");
        assertParity(s);
    }

    @Test
    @DisplayName("Edit that increases nesting via wrapper parens — here by retyping ident longer")
    void edit_changes_rule_shape() {
        var s = parser().initialize("let x = 1;").edit(4, 1, "longername");
        assertParity(s);
    }

    @Test
    @DisplayName("Pure insertion at interior offset")
    void pure_insertion_interior() {
        var s = parser().initialize("let x = 1; let y = 2;").edit(11, 0, "let z = 3; ");
        assertParity(s);
    }

    @Test
    @DisplayName("Several sequential edits produce parity at every step")
    void sequential_edits_parity() {
        var session = parser().initialize("let x = 1;");
        session = session.edit(session.text().length(), 0, " let y = 2;");
        assertParity(session);
        session = session.edit(session.text().length(), 0, " let z = 3;");
        assertParity(session);
        session = session.edit(0, 0, "let a = 0; ");
        assertParity(session);
        session = session.edit(session.text().indexOf("1"), 1, "99");
        assertParity(session);
    }

    @Test
    @DisplayName("Buffer that becomes empty after a deletion")
    void delete_entire_buffer() {
        var s = parser().initialize("let x = 1;").edit(0, "let x = 1;".length(), "");
        assertThat(s.text()).isEmpty();
        assertParity(s);
    }
}
