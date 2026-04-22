package org.pragmatica.peg.formatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pragmatica.peg.formatter.Docs.concat;
import static org.pragmatica.peg.formatter.Docs.empty;
import static org.pragmatica.peg.formatter.Docs.group;
import static org.pragmatica.peg.formatter.Docs.hardline;
import static org.pragmatica.peg.formatter.Docs.indent;
import static org.pragmatica.peg.formatter.Docs.join;
import static org.pragmatica.peg.formatter.Docs.line;
import static org.pragmatica.peg.formatter.Docs.softline;
import static org.pragmatica.peg.formatter.Docs.text;

/**
 * Unit tests for the {@link Doc} algebra and {@link Docs} builder functions.
 */
final class DocAlgebraTest {

    @Nested
    @DisplayName("Doc constructors")
    class Constructors {
        @Test
        void textRejectsNull() {
            assertThatThrownBy(() -> new Doc.Text(null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void textRejectsNewlines() {
            assertThatThrownBy(() -> new Doc.Text("a\nb"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newlines");
        }

        @Test
        void textAcceptsPlainString() {
            assertThat(new Doc.Text("hello").value()).isEqualTo("hello");
        }

        @Test
        void groupRejectsNull() {
            assertThatThrownBy(() -> new Doc.Group(null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void indentRejectsNull() {
            assertThatThrownBy(() -> new Doc.Indent(2, null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void concatRejectsNulls() {
            var t = new Doc.Text("x");
            assertThatThrownBy(() -> new Doc.Concat(null, t))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Doc.Concat(t, null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Docs builders")
    class Builders {
        @Test
        void emptyReturnsEmpty() {
            assertThat(empty()).isInstanceOf(Doc.Empty.class);
        }

        @Test
        void textBuilderMatchesConstructor() {
            assertThat(text("x")).isEqualTo(new Doc.Text("x"));
        }

        @Test
        void lineIsLineRecord() {
            assertThat(line()).isInstanceOf(Doc.Line.class);
        }

        @Test
        void softlineIsSoftlineRecord() {
            assertThat(softline()).isInstanceOf(Doc.Softline.class);
        }

        @Test
        void hardlineIsHardLineRecord() {
            assertThat(hardline()).isInstanceOf(Doc.HardLine.class);
        }

        @Test
        void concatEmptyReturnsEmpty() {
            assertThat(concat()).isInstanceOf(Doc.Empty.class);
        }

        @Test
        void concatOneReturnsSingleton() {
            assertThat(concat(text("x"))).isEqualTo(text("x"));
        }

        @Test
        void concatTwoWrapsInConcat() {
            var result = concat(text("a"), text("b"));
            assertThat(result).isInstanceOf(Doc.Concat.class);
        }

        @Test
        void concatListOfEmptyIsEmpty() {
            assertThat(concat(List.of())).isInstanceOf(Doc.Empty.class);
        }

        @Test
        void groupWrapsInGroup() {
            var g = group(text("a"), text("b"));
            assertThat(g).isInstanceOf(Doc.Group.class);
        }

        @Test
        void indentWrapsInIndent() {
            var i = indent(4, text("x"));
            assertThat(i).isInstanceOf(Doc.Indent.class);
            assertThat(((Doc.Indent) i).amount()).isEqualTo(4);
        }

        @Test
        void joinEmptyListIsEmpty() {
            assertThat(join(text(","), List.of())).isInstanceOf(Doc.Empty.class);
        }

        @Test
        void joinSingletonIsIdentity() {
            assertThat(join(text(","), List.of(text("a")))).isEqualTo(text("a"));
        }

        @Test
        void joinInsertsSeparator() {
            var joined = join(text(","), List.of(text("a"), text("b"), text("c")));
            // Shape check: rendered output should be "a,b,c"
            var rendered = org.pragmatica.peg.formatter.internal.Renderer.render(joined, 80);
            assertThat(rendered).isEqualTo("a,b,c");
        }
    }

    @Nested
    @DisplayName("concatAll")
    class ConcatAll {
        @Test
        void emptyIsEmpty() {
            assertThat(Doc.concatAll(List.of())).isInstanceOf(Doc.Empty.class);
        }

        @Test
        void nullIsEmpty() {
            assertThat(Doc.concatAll(null)).isInstanceOf(Doc.Empty.class);
        }

        @Test
        void preservesOrder() {
            var d = Doc.concatAll(List.of(text("a"), text("b"), text("c")));
            var rendered = org.pragmatica.peg.formatter.internal.Renderer.render(d, 80);
            assertThat(rendered).isEqualTo("abc");
        }
    }
}
