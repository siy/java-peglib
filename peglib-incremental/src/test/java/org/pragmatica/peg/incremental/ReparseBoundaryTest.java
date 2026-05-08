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

    /**
     * Apply a single edit to a freshly initialised session over {@code text}.
     */
    private static Session editFromInit(String text, int offset, int oldLen, String newText) {
        var init = parser().initialize(text);
        return init.session().edit(init.cursor(), offset, oldLen, newText).newSession();
    }

    private static void assertParity(Session session) {
        var oracle = PegParser.fromGrammar(grammar())
            .fold(cause -> { throw new IllegalStateException(cause.message()); }, p -> p);
        var oracleResult = oracle.parseCst(session.text());
        assertThat(oracleResult.isSuccess()).as("oracle must be able to parse %s", session.text()).isTrue();
        assertThat(CstHash.cstHash(session.root()))
            .as("incremental parity against full reparse for buffer %s", session.text())
            .isEqualTo(CstHash.cstHash(oracleResult.unwrap()));
    }

    @Test
    @DisplayName("Edit at buffer start")
    void edit_at_buffer_start() {
        var s = editFromInit("let x = 1;", 0, 0, "let a = 99; ");
        assertParity(s);
    }

    @Test
    @DisplayName("Edit at buffer end")
    void edit_at_buffer_end() {
        var text = "let x = 1;";
        var s = editFromInit(text, text.length(), 0, " let y = 2;");
        assertParity(s);
    }

    @Test
    @DisplayName("Insert a whole new top-level rule")
    void insert_new_statement() {
        var s = editFromInit("let x = 1;", 0, 0, "let new = 42; ");
        assertParity(s);
    }

    @Test
    @DisplayName("Delete a whole statement shrinks the tree")
    void delete_statement() {
        var s = editFromInit("let x = 1; let y = 2;", 0, 11, "");
        assertParity(s);
    }

    @Test
    @DisplayName("Edit inside a number leaf")
    void edit_inside_number() {
        var s = editFromInit("let x = 1;", 8, 1, "42");
        assertParity(s);
    }

    @Test
    @DisplayName("Edit inside an identifier leaf")
    void edit_inside_ident() {
        var s = editFromInit("let x = 1;", 4, 1, "abc");
        assertParity(s);
    }

    @Test
    @DisplayName("Edit entirely within whitespace trivia")
    void edit_within_whitespace() {
        var s = editFromInit("let x  =  1;", 7, 1, "   ");
        assertParity(s);
    }

    @Test
    @DisplayName("Edit that straddles two statements")
    void edit_straddles_statements() {
        var s = editFromInit("let x = 1; let y = 2;", 9, 4, "99; let z = ");
        assertParity(s);
    }

    @Test
    @DisplayName("Edit that increases nesting via wrapper parens — here by retyping ident longer")
    void edit_changes_rule_shape() {
        var s = editFromInit("let x = 1;", 4, 1, "longername");
        assertParity(s);
    }

    @Test
    @DisplayName("Pure insertion at interior offset")
    void pure_insertion_interior() {
        var s = editFromInit("let x = 1; let y = 2;", 11, 0, "let z = 3; ");
        assertParity(s);
    }

    @Test
    @DisplayName("Several sequential edits produce parity at every step")
    void sequential_edits_parity() {
        var init = parser().initialize("let x = 1;");
        var session = init.session();
        var cursor = init.cursor();
        var o1 = session.edit(cursor, session.text().length(), 0, " let y = 2;");
        assertParity(o1.newSession());
        var o2 = o1.newSession().edit(o1.newCursor(), o1.newSession().text().length(), 0, " let z = 3;");
        assertParity(o2.newSession());
        var o3 = o2.newSession().edit(o2.newCursor(), 0, 0, "let a = 0; ");
        assertParity(o3.newSession());
        var o4 = o3.newSession().edit(o3.newCursor(),
                                      o3.newSession().text().indexOf("1"),
                                      1,
                                      "99");
        assertParity(o4.newSession());
    }

    @Test
    @DisplayName("Buffer that becomes empty after a deletion")
    void delete_entire_buffer() {
        var s = editFromInit("let x = 1;", 0, "let x = 1;".length(), "");
        assertThat(s.text()).isEmpty();
        assertParity(s);
    }
}
