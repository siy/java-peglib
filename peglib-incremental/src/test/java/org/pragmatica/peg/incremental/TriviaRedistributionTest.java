package org.pragmatica.peg.incremental;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.incremental.internal.CstHash;
import org.pragmatica.peg.parser.ParserConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §5.4 v2 — trivia-aware reparse splice. Hand-crafted edits exercise:
 *
 * <ul>
 *   <li>edits entirely within a whitespace trivia run (insert / delete /
 *       replace);</li>
 *   <li>edits spanning trivia and a token (must fall through to structural
 *       reparse — fast-path declines);</li>
 *   <li>edits deleting trivia entirely (collapse a blank line);</li>
 *   <li>edits inserting new trivia inside an existing whitespace run.</li>
 * </ul>
 *
 * <p>Parity oracle is a fresh full parse of the post-edit buffer (CstHash
 * comparison), as in {@link ReparseBoundaryTest}.
 */
final class TriviaRedistributionTest {

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
        // 0.3.2 v2: trivia fast-path opt-in.
        return IncrementalParser.create(grammar(), ParserConfig.DEFAULT, true);
    }

    /**
     * Apply a single edit to a freshly initialised session over {@code text}
     * and return the post-edit Session. Lever D: takes the cursor from the
     * InitialSession, threads it through the edit.
     */
    private static Session editFromInit(String text, int offset, int oldLen, String newText) {
        var init = parser().initialize(text);
        return init.session().edit(init.cursor(), offset, oldLen, newText).newSession();
    }

    private static void assertParity(Session session) {
        var oracle = PegParser.fromGrammar(grammar())
            .fold(cause -> { throw new IllegalStateException(cause.message()); }, p -> p);
        var oracleResult = oracle.parseCst(session.text());
        assertThat(oracleResult.isSuccess()).as("oracle must parse %s", session.text()).isTrue();
        assertThat(CstHash.cstHash(session.root()))
            .as("incremental parity for buffer %s", session.text())
            .isEqualTo(CstHash.cstHash(oracleResult.unwrap()));
    }

    @Nested
    @DisplayName("Trivia-only fast-path")
    final class TriviaOnly {

        @Test
        @DisplayName("Insert a single space inside an existing whitespace run")
        void insert_space_in_whitespace() {
            var s = editFromInit("let x  =  1;", 7, 0, " ");
            assertParity(s);
            assertThat(s.text()).isEqualTo("let x   =  1;");
            assertThat(s.stats().lastReparsedRule()).isEqualTo("<trivia>");
        }

        @Test
        @DisplayName("Delete a space inside an existing whitespace run")
        void delete_space_in_whitespace() {
            var s = editFromInit("let x   =   1;", 7, 1, "");
            assertParity(s);
            assertThat(s.text()).isEqualTo("let x  =   1;");
            assertThat(s.stats().lastReparsedRule()).isEqualTo("<trivia>");
        }

        @Test
        @DisplayName("Replace whitespace run with different whitespace mixture")
        void replace_whitespace_with_whitespace() {
            var s = editFromInit("let x  =  1;", 6, 1, "\t");
            assertParity(s);
            assertThat(s.stats().lastReparsedRule()).isEqualTo("<trivia>");
        }

        @Test
        @DisplayName("Insert a newline (still whitespace) into a whitespace run")
        void insert_newline_in_whitespace() {
            var s = editFromInit("let x = 1;\n let y = 2;", 11, 0, "\n");
            assertParity(s);
            assertThat(s.stats().lastReparsedRule()).isEqualTo("<trivia>");
        }

        @Test
        @DisplayName("Delete a single character from a multi-line whitespace run")
        void delete_blank_line_between_statements() {
            var s = editFromInit("let x = 1;\n\n\nlet y = 2;", 11, 1, "");
            assertParity(s);
            assertThat(s.text()).isEqualTo("let x = 1;\n\nlet y = 2;");
            assertThat(s.stats().lastReparsedRule()).isEqualTo("<trivia>");
        }
    }

    @Nested
    @DisplayName("Edits that fall through to structural reparse")
    final class StructuralFallthrough {

        @Test
        @DisplayName("Edit straddling whitespace + token: must NOT take fast-path")
        void edit_spans_trivia_and_token() {
            var s = editFromInit("let x = 1;", 5, 3, "y =");
            assertParity(s);
            assertThat(s.stats().lastReparsedRule()).isNotEqualTo("<trivia>");
        }

        @Test
        @DisplayName("Insert non-whitespace into a whitespace run: declines fast-path")
        void insert_non_whitespace_in_whitespace() {
            var s = editFromInit("let x  = 1;", 6, 0, "y");
            assertParity(s);
            assertThat(s.stats().lastReparsedRule()).isNotEqualTo("<trivia>");
        }
    }

    @Nested
    @DisplayName("Deleted-trivia handling preserves trivia attribution")
    final class DeletedTrivia {

        @Test
        @DisplayName("Sequential trivia deletes still produce parity at every step")
        void multiple_trivia_deletes() {
            var init = parser().initialize("let x   =   1   ;");
            var session = init.session();
            var cursor = init.cursor();
            var o1 = session.edit(cursor, 5, 1, "");
            assertParity(o1.newSession());
            var o2 = o1.newSession().edit(o1.newCursor(), 7, 1, "");
            assertParity(o2.newSession());
            var o3 = o2.newSession().edit(o2.newCursor(), 9, 1, "");
            assertParity(o3.newSession());
        }

        @Test
        @DisplayName("Delete entire whitespace run between two tokens")
        void delete_whitespace_entirely() {
            var s = editFromInit("let x = 1; let y = 2;", 10, 1, "");
            assertParity(s);
            assertThat(s.text()).isEqualTo("let x = 1;let y = 2;");
        }
    }

    @Nested
    @DisplayName("Inserted-trivia handling")
    final class InsertedTrivia {

        @Test
        @DisplayName("Insert trivia between two tokens that previously had no whitespace")
        void insert_trivia_between_adjacent_tokens() {
            var s = editFromInit("let x=1;", 7, 0, " ");
            assertParity(s);
        }

        @Test
        @DisplayName("Repeated trivia inserts sustain parity at every step")
        void repeated_trivia_inserts() {
            var init = parser().initialize("let x = 1; let y = 2;");
            var session = init.session();
            var cursor = init.cursor();
            var o1 = session.edit(cursor, 5, 0, " ");
            assertParity(o1.newSession());
            var o2 = o1.newSession().edit(o1.newCursor(), 7, 0, " ");
            assertParity(o2.newSession());
            var o3 = o2.newSession().edit(o2.newCursor(), 11, 0, "\n");
            assertParity(o3.newSession());
        }
    }
}
