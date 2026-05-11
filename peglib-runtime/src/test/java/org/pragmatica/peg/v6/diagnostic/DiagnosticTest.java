package org.pragmatica.peg.v6.diagnostic;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiagnosticTest {
    @Nested
    class Construction {
        @Test
        void error_factory_setsErrorSeverity() {
            var d = Diagnostic.error(0, 1, "msg", "exp", "found");
            assertEquals(Severity.ERROR, d.severity());
            assertEquals(0, d.offset());
            assertEquals(1, d.length());
            assertEquals("msg", d.message());
            assertEquals("exp", d.expected());
            assertEquals("found", d.found());
        }

        @Test
        void error_factory_shortForm_emptyExpectedAndFound() {
            var d = Diagnostic.error(3, 2, "boom");
            assertEquals(Severity.ERROR, d.severity());
            assertEquals("", d.expected());
            assertEquals("", d.found());
        }

        // Defensive null/range checks removed from Diagnostic as part of the
        // JBCT conformance refactor — callers (parser/lexer codegen) supply
        // validated values.
    }

    @Nested
    class GoldenFormat {
        @Test
        void specExample_byteForByte() {
            var d = Diagnostic.error(5, 1, "unexpected input", "[a-z]+", "@");
            String expected =
            "error: unexpected input\n" + "  --> input.txt:1:6\n" + "   |\n" + " 1 | abc, @@@, def\n"
            + "   |      ^ found '@'\n" + "   |\n" + "   = help: expected [a-z]+\n";
            assertEquals(expected, d.formatRustStyle("input.txt", "abc, @@@, def"));
        }
    }

    @Nested
    class FormatVariations {
        @Test
        void emptyExpected_skipsHelpLine() {
            var d = Diagnostic.error(0, 1, "boom", "", "x");
            String out = d.formatRustStyle("f", "x");
            assertEquals(
            "error: boom\n" + "  --> f:1:1\n" + "   |\n" + " 1 | x\n" + "   | ^ found 'x'\n" + "   |\n", out);
        }

        @Test
        void emptyFound_keepsCaretButNoFoundText() {
            var d = Diagnostic.error(0, 2, "boom", "id", "");
            String out = d.formatRustStyle("f", "ab");
            assertEquals(
            "error: boom\n" + "  --> f:1:1\n" + "   |\n" + " 1 | ab\n" + "   | ^^\n" + "   |\n"
            + "   = help: expected id\n",
            out);
        }

        @Test
        void zeroLength_emitsSingleCaret() {
            var d = Diagnostic.error(2, 0, "boom", "id", "");
            String out = d.formatRustStyle("f", "abc");
            assertEquals(
            "error: boom\n" + "  --> f:1:3\n" + "   |\n" + " 1 | abc\n" + "   |   ^\n" + "   |\n"
            + "   = help: expected id\n",
            out);
        }

        @Test
        void length3_emitsThreeCarets() {
            var d = Diagnostic.error(0, 3, "boom", "", "abc");
            String out = d.formatRustStyle("f", "abcdef");
            assertEquals(
            "error: boom\n" + "  --> f:1:1\n" + "   |\n" + " 1 | abcdef\n" + "   | ^^^ found 'abc'\n" + "   |\n", out);
        }

        @Test
        void multilineInput_diagnosticOnLine3() {
            var input = "first line\nsecond line\nthird line here";
            int offset = "first line\nsecond line\n".length() + 6;
            var d = Diagnostic.error(offset, 4, "boom", "kw", "line");
            String out = d.formatRustStyle("input.txt", input);
            assertEquals(
            "error: boom\n" + "  --> input.txt:3:7\n" + "   |\n" + " 3 | third line here\n"
            + "   |       ^^^^ found 'line'\n" + "   |\n" + "   = help: expected kw\n",
            out);
        }

        @Test
        void offsetAtVeryStart_line1Col1() {
            var d = Diagnostic.error(0, 1, "boom", "x", "y");
            String out = d.formatRustStyle("f", "y");
            assertEquals(
            "error: boom\n" + "  --> f:1:1\n" + "   |\n" + " 1 | y\n" + "   | ^ found 'y'\n" + "   |\n"
            + "   = help: expected x\n",
            out);
        }

        @Test
        void lastLineNoTrailingNewline_handled() {
            var input = "a\nb";
            var d = Diagnostic.error(2, 1, "boom", "", "b");
            String out = d.formatRustStyle("f", input);
            assertEquals(
            "error: boom\n" + "  --> f:2:1\n" + "   |\n" + " 2 | b\n" + "   | ^ found 'b'\n" + "   |\n", out);
        }

        @Test
        void severityWarning_firstWordIsWarning() {
            var d = new Diagnostic(Severity.WARNING, 0, 1, "watch", "", "x");
            String out = d.formatRustStyle("f", "x");
            assertEquals("warning: watch", firstLine(out));
        }

        @Test
        void severityInfo_firstWordIsInfo() {
            var d = new Diagnostic(Severity.INFO, 0, 1, "fyi", "", "x");
            String out = d.formatRustStyle("f", "x");
            assertEquals("info: fyi", firstLine(out));
        }

        @Test
        void severityError_firstWordIsError() {
            var d = new Diagnostic(Severity.ERROR, 0, 1, "bad", "", "x");
            String out = d.formatRustStyle("f", "x");
            assertEquals("error: bad", firstLine(out));
        }

        @Test
        void wideLineNumber_gutterExpands() {
            var sb = new StringBuilder();
            for (int i = 1; i <= 9; i++ ) {
                sb.append("line")
                  .append(i)
                  .append('\n');
            }
            sb.append("target here");
            var input = sb.toString();
            int offset = input.indexOf("target");
            var d = Diagnostic.error(offset, 6, "boom", "kw", "target");
            String out = d.formatRustStyle("f", input);
            assertEquals(
            "error: boom\n" + "  --> f:10:1\n" + "    |\n" + " 10 | target here\n" + "    | ^^^^^^ found 'target'\n"
            + "    |\n" + "    = help: expected kw\n",
            out);
        }
    }

    private static String firstLine(String text) {
        int nl = text.indexOf('\n');
        return nl < 0
               ? text
               : text.substring(0, nl);
    }
}
