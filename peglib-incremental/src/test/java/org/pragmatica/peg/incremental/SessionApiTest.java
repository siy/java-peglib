package org.pragmatica.peg.incremental;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests covering the public {@code Session} / {@code IncrementalParser} /
 * {@code Edit} / {@code Cursor} API shape: no-op edits, cursor clamping,
 * cursor shift rules, and the invariants in SPEC §4.4 / §5.
 */
final class SessionApiTest {

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

    @Nested
    @DisplayName("Edit record validation")
    class EditValidation {
        @Test
        void edit_rejects_negative_offset() {
            assertThatThrownBy(() -> new Edit(-1, 0, ""))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void edit_rejects_negative_oldLen() {
            assertThatThrownBy(() -> new Edit(0, -1, ""))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void edit_rejects_null_newText() {
            assertThatThrownBy(() -> new Edit(0, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void edit_delta_and_newLen_computed() {
            var edit = new Edit(5, 3, "ab");
            assertThat(edit.newLen()).isEqualTo(2);
            assertThat(edit.delta()).isEqualTo(-1);
        }

        @Test
        void edit_isNoOp_detects_zero_edits() {
            assertThat(new Edit(0, 0, "").isNoOp()).isTrue();
            assertThat(new Edit(0, 0, "x").isNoOp()).isFalse();
            assertThat(new Edit(0, 1, "").isNoOp()).isFalse();
        }
    }

    @Nested
    @DisplayName("Initialize")
    class Initialize {
        @Test
        void initialize_rejects_null_buffer() {
            assertThatThrownBy(() -> parser().initialize(null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void initialize_default_cursor_is_zero() {
            var init = parser().initialize("let x = 1;");
            assertThat(init.cursor().offset()).isEqualTo(0);
            assertThat(init.session().text()).isEqualTo("let x = 1;");
        }

        @Test
        void initialize_clamps_cursor_over_end() {
            var init = parser().initialize("let x = 1;", 9999);
            assertThat(init.cursor().offset()).isEqualTo("let x = 1;".length());
        }

        @Test
        void initialize_clamps_cursor_negative() {
            var init = parser().initialize("let x = 1;", -5);
            assertThat(init.cursor().offset()).isEqualTo(0);
        }

        @Test
        void initialize_emits_tree_root() {
            var init = parser().initialize("let x = 1;");
            assertThat(init.session().root()).isNotNull();
            assertThat(init.session().stats().reparseCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("No-op edit short-circuit")
    class NoOp {
        @Test
        void no_op_edit_returns_same_session() {
            var init = parser().initialize("let x = 1;");
            var outcome = init.session().edit(init.cursor(), 0, 0, "");
            assertThat(outcome.newSession()).isSameAs(init.session());
            assertThat(outcome.newCursor()).isSameAs(init.cursor());
            assertThat(init.session().stats().reparseCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Cursor movement (Lever D — pure, no Session allocation)")
    class CursorMove {
        @Test
        void move_cursor_pure_does_not_change_tree() {
            var init = parser().initialize("let x = 1;", 0);
            var moved = init.cursor().moveTo(4, init.session().index());
            assertThat(moved.offset()).isEqualTo(4);
            // Session was not touched; same root, same text.
            assertThat(init.session().text()).isEqualTo("let x = 1;");
        }

        @Test
        void move_cursor_clamps_to_buffer() {
            var init = parser().initialize("let x = 1;");
            var moved = init.cursor().moveTo(1000, init.session().index());
            assertThat(moved.offset()).isEqualTo("let x = 1;".length());
        }

        @Test
        void move_cursor_to_same_offset_yields_equal_record() {
            var init = parser().initialize("let x = 1;", 5);
            var moved = init.cursor().moveTo(5, init.session().index());
            assertThat(moved).isEqualTo(init.cursor());
        }
    }

    @Nested
    @DisplayName("Cursor shift during edits")
    class CursorShift {
        @Test
        void cursor_before_edit_stays_put() {
            var init = parser().initialize("let x = 1;", 2); // cursor at 'l' | 'et'
            var outcome = init.session().edit(init.cursor(), 6, 0, "y"); // insert after 'x '
            assertThat(outcome.newCursor().offset()).isEqualTo(2);
        }

        @Test
        void cursor_after_edit_shifts_by_delta() {
            var init = parser().initialize("let x = 1;", 9); // near end
            var outcome = init.session().edit(init.cursor(), 4, 1, "yyy"); // replace 'x' with 'yyy'
            assertThat(outcome.newCursor().offset()).isEqualTo(11);
        }

        @Test
        void cursor_inside_edit_snaps_to_end_of_replacement() {
            var init = parser().initialize("let x = 1;", 5); // cursor between 'x' and ' '
            var outcome = init.session().edit(init.cursor(), 4, 4, "foo = "); // replace 'x = ' with 'foo = '
            assertThat(outcome.newCursor().offset()).isEqualTo(4 + "foo = ".length());
        }
    }

    @Nested
    @DisplayName("Out-of-range edits rejected")
    class OutOfRange {
        @Test
        void edit_offset_past_end_rejected() {
            var init = parser().initialize("abc");
            assertThatThrownBy(() -> init.session().edit(init.cursor(), 100, 0, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void edit_range_past_end_rejected() {
            var init = parser().initialize("abc");
            assertThatThrownBy(() -> init.session().edit(init.cursor(), 1, 100, ""))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("reparseAll escape hatch")
    class ReparseAll {
        @Test
        void reparse_all_increments_fullReparseCount() {
            var init = parser().initialize("let x = 1;");
            var outcome = init.session().reparseAll(init.cursor());
            assertThat(outcome.newSession().stats().fullReparseCount()).isEqualTo(1);
            assertThat(outcome.newSession().stats().reparseCount()).isEqualTo(1);
        }

        @Test
        void reparse_all_preserves_text_and_cursor() {
            var init = parser().initialize("let x = 1;", 5);
            var outcome = init.session().reparseAll(init.cursor());
            assertThat(outcome.newSession().text()).isEqualTo(init.session().text());
            assertThat(outcome.newCursor().offset()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("Stats")
    class StatsChecks {
        @Test
        void initial_stats_zero() {
            var init = parser().initialize("let x = 1;");
            assertThat(init.session().stats()).isEqualTo(Stats.INITIAL);
        }

        @Test
        void edit_increments_reparseCount() {
            var init = parser().initialize("let x = 1;");
            var outcome = init.session().edit(init.cursor(), 9, 0, "2");
            assertThat(outcome.newSession().stats().reparseCount()).isEqualTo(1);
        }

        @Test
        void stats_lastReparseMs_is_computed_from_nanos() {
            var stats = new Stats(1, 0, "X", 10, 5_000_000L);
            assertThat(stats.lastReparseMs()).isEqualTo(5.0);
        }
    }
}
