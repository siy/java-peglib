package org.pragmatica.peg.formatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.formatter.internal.Renderer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pragmatica.peg.formatter.Docs.concat;
import static org.pragmatica.peg.formatter.Docs.empty;
import static org.pragmatica.peg.formatter.Docs.group;
import static org.pragmatica.peg.formatter.Docs.hardline;
import static org.pragmatica.peg.formatter.Docs.indent;
import static org.pragmatica.peg.formatter.Docs.line;
import static org.pragmatica.peg.formatter.Docs.softline;
import static org.pragmatica.peg.formatter.Docs.text;

/**
 * Unit tests for the Wadler-Lindig best-algorithm renderer.
 */
final class RendererTest {

    @Nested
    @DisplayName("Primitives")
    class Primitives {
        @Test
        void emptyRendersAsEmptyString() {
            assertThat(Renderer.render(empty(), 80)).isEmpty();
        }

        @Test
        void textRendersVerbatim() {
            assertThat(Renderer.render(text("hello"), 80)).isEqualTo("hello");
        }

        @Test
        void concatJoinsText() {
            assertThat(Renderer.render(concat(text("foo"), text("bar")), 80))
                .isEqualTo("foobar");
        }

        @Test
        void topLevelLineBreaksAsNewline() {
            // Root mode is BREAK, so Line at top level becomes \n
            var d = concat(text("a"), line(), text("b"));
            assertThat(Renderer.render(d, 80)).isEqualTo("a\nb");
        }

        @Test
        void topLevelSoftlineBreaksAsNewline() {
            var d = concat(text("a"), softline(), text("b"));
            assertThat(Renderer.render(d, 80)).isEqualTo("a\nb");
        }

        @Test
        void hardlineAlwaysBreaks() {
            var d = concat(text("a"), hardline(), text("b"));
            assertThat(Renderer.render(d, 80)).isEqualTo("a\nb");
        }

        @Test
        void rejectsNullDoc() {
            assertThatThrownBy(() -> Renderer.render(null, 80))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNonPositiveWidth() {
            assertThatThrownBy(() -> Renderer.render(empty(), 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Renderer.render(empty(), -1))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Group behavior")
    class Groups {
        @Test
        void groupFitsFlat() {
            var d = group(text("a"), line(), text("b"));
            // Flat: "a b" = 3 chars, well within width 80
            assertThat(Renderer.render(d, 80)).isEqualTo("a b");
        }

        @Test
        void groupBreaksWhenNotFit() {
            // Width 3 forces break: "aaaa bbbb" = 9 chars > 3
            var d = group(text("aaaa"), line(), text("bbbb"));
            assertThat(Renderer.render(d, 3)).isEqualTo("aaaa\nbbbb");
        }

        @Test
        void softlineDisappearsInFlatMode() {
            var d = group(text("a"), softline(), text("b"));
            assertThat(Renderer.render(d, 80)).isEqualTo("ab");
        }

        @Test
        void hardlineForcesGroupBreak() {
            // Even though the flat form fits (5 chars), hardline forces break
            var d = group(text("a"), hardline(), text("b"));
            assertThat(Renderer.render(d, 80)).isEqualTo("a\nb");
        }
    }

    @Nested
    @DisplayName("Indent")
    class Indents {
        @Test
        void indentInBreakModeAddsSpaces() {
            var d = concat(text("{"), indent(2, concat(line(), text("body"))), line(), text("}"));
            // Forced break at top level: { then newline+indent 2 + body then newline + }
            assertThat(Renderer.render(d, 80)).isEqualTo("{\n  body\n}");
        }

        @Test
        void indentInFlatModeIgnored() {
            var d = group(text("{"), indent(2, concat(line(), text("body"))), line(), text("}"));
            // Flat fits: "{ body }" = 8 chars
            assertThat(Renderer.render(d, 80)).isEqualTo("{ body }");
        }

        @Test
        void nestedIndentsAccumulate() {
            var inner = concat(line(), text("x"));
            var d = concat(text("a"), indent(2, concat(line(), text("b"), indent(4, inner))));
            // Break mode everywhere:
            // a\n  b\n      x
            assertThat(Renderer.render(d, 1)).isEqualTo("a\n  b\n      x");
        }
    }

    @Nested
    @DisplayName("Width-driven decisions")
    class WidthDecisions {
        @Test
        void exactlyFittingGroupStaysFlat() {
            var d = group(text("ab"), line(), text("cd")); // "ab cd" = 5 chars
            assertThat(Renderer.render(d, 5)).isEqualTo("ab cd");
        }

        @Test
        void overflowingGroupBreaks() {
            var d = group(text("ab"), line(), text("cdef")); // "ab cdef" = 7 chars
            assertThat(Renderer.render(d, 5)).isEqualTo("ab\ncdef");
        }

        @Test
        void surroundingContextInfluencesFitDecision() {
            // After "prefix" takes 6 cols, budget at width 10 is 4.
            // Flat group would be "a b" = 3 cols, fits.
            var g = group(text("a"), line(), text("b"));
            var d = concat(text("prefix"), g);
            assertThat(Renderer.render(d, 10)).isEqualTo("prefixa b");
        }

        @Test
        void surroundingContextForcesBreak() {
            // Prefix 6 cols, width 8, flat group is "a b" = 3 cols => 9 > 8 => break.
            var g = group(text("a"), line(), text("b"));
            var d = concat(text("prefix"), g);
            assertThat(Renderer.render(d, 8)).isEqualTo("prefixa\nb");
        }
    }
}
