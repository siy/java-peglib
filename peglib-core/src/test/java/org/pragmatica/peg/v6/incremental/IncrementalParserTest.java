package org.pragmatica.peg.v6.incremental;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.v6.Parser;
import org.pragmatica.peg.v6.PegParser;
import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.cst.ParseResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase D.2 — coverage for {@link IncrementalParser}.
 *
 * <p>The wrapper currently does full reparse on every edit, so the contract under
 * test is: after edit(o, l, t), the visible state must equal a fresh {@code parser.parse}
 * of the post-edit input. We verify this for several edit shapes (insert, replace,
 * delete) and a sequence of three edits, plus argument validation.
 */
class IncrementalParserTest {

    private static final String GRAMMAR = """
        File <- Item (',' Item)*
        Item <- 'foo' / 'bar'
        %whitespace <- [ \\t]*
        """;

    private static Parser parser;

    @BeforeAll
    static void setup() {
        parser = PegParser.fromGrammar(GRAMMAR).unwrap();
    }

    private static void assertCstEquivalent(CstArray actual, CstArray expected) {
        assertThat(actual.input()).isEqualTo(expected.input());
        assertThat(actual.nodeCount()).isEqualTo(expected.nodeCount());
        assertThat(actual.rootIndex()).isEqualTo(expected.rootIndex());
        for (var i = 0; i < expected.nodeCount(); i++) {
            assertThat(actual.kindAt(i)).as("kindAt(%d)", i).isEqualTo(expected.kindAt(i));
            assertThat(actual.parentAt(i)).as("parentAt(%d)", i).isEqualTo(expected.parentAt(i));
            assertThat(actual.firstChildAt(i)).as("firstChildAt(%d)", i).isEqualTo(expected.firstChildAt(i));
            assertThat(actual.nextSiblingAt(i)).as("nextSiblingAt(%d)", i).isEqualTo(expected.nextSiblingAt(i));
            assertThat(actual.firstTokenAt(i)).as("firstTokenAt(%d)", i).isEqualTo(expected.firstTokenAt(i));
            assertThat(actual.lastTokenAt(i)).as("lastTokenAt(%d)", i).isEqualTo(expected.lastTokenAt(i));
            assertThat(actual.flagsAt(i)).as("flagsAt(%d)", i).isEqualTo(expected.flagsAt(i));
        }
    }

    @Test
    void initialState_matchesFreshParse() {
        var ip = new IncrementalParser(parser, "foo, bar");

        assertThat(ip.input()).isEqualTo("foo, bar");
        assertCstEquivalent(ip.current(), parser.parse("foo, bar").cst());
        assertThat(ip.diagnostics()).isEqualTo(parser.parse("foo, bar").diagnostics());
        assertThat(ip.currentTokens()).isSameAs(ip.current().tokens());
    }

    @Test
    void insertAtEnd_updatesInputAndMatchesFreshParse() {
        var ip = new IncrementalParser(parser, "foo");

        var result = ip.edit(3, 0, ", bar");

        assertThat(ip.input()).isEqualTo("foo, bar");
        assertCstEquivalent(result.cst(), parser.parse("foo, bar").cst());
        assertCstEquivalent(ip.current(), result.cst());
        assertThat(ip.diagnostics()).isEqualTo(result.diagnostics());
    }

    @Test
    void insertAtStart_updatesInputAndMatchesFreshParse() {
        var ip = new IncrementalParser(parser, "bar");

        var result = ip.edit(0, 0, "foo, ");

        assertThat(ip.input()).isEqualTo("foo, bar");
        assertCstEquivalent(result.cst(), parser.parse("foo, bar").cst());
    }

    @Test
    void replaceMiddle_updatesInputAndMatchesFreshParse() {
        var ip = new IncrementalParser(parser, "foo, bar");

        var result = ip.edit(5, 3, "foo");

        assertThat(ip.input()).isEqualTo("foo, foo");
        assertCstEquivalent(result.cst(), parser.parse("foo, foo").cst());
    }

    @Test
    void delete_shortensInputAndMatchesFreshParse() {
        var ip = new IncrementalParser(parser, "foo, bar, foo");

        var result = ip.edit(3, 5, "");

        assertThat(ip.input()).isEqualTo("foo, foo");
        assertCstEquivalent(result.cst(), parser.parse("foo, foo").cst());
    }

    @Test
    void sequentialEdits_finalStateMatchesFullReparseOfCumulativeInput() {
        var ip = new IncrementalParser(parser, "foo");

        ip.edit(3, 0, ", bar");
        ip.edit(8, 0, ", foo");
        var result = ip.edit(0, 3, "bar");

        assertThat(ip.input()).isEqualTo("bar, bar, foo");
        var fresh = parser.parse("bar, bar, foo");
        assertCstEquivalent(result.cst(), fresh.cst());
        assertCstEquivalent(ip.current(), fresh.cst());
        assertThat(ip.diagnostics()).isEqualTo(fresh.diagnostics());
    }

    @Test
    void editIntroducesError_diagnosticsMatchFreshParse() {
        var ip = new IncrementalParser(parser, "foo, bar");

        var result = ip.edit(5, 3, "@@@");

        assertThat(ip.input()).isEqualTo("foo, @@@");
        var fresh = parser.parse("foo, @@@");
        assertThat(result.diagnostics()).isEqualTo(fresh.diagnostics());
        assertThat(ip.diagnostics()).isEqualTo(fresh.diagnostics());
    }

    @Test
    void noOpEdit_stateRemainsConsistent() {
        var ip = new IncrementalParser(parser, "foo, bar");

        var result = ip.edit(4, 0, "");

        assertThat(ip.input()).isEqualTo("foo, bar");
        assertCstEquivalent(result.cst(), parser.parse("foo, bar").cst());
    }

    @Test
    void editOutOfBounds_negativeOffset_throws() {
        var ip = new IncrementalParser(parser, "foo");

        assertThatThrownBy(() -> ip.edit(-1, 0, "x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("out of bounds");
    }

    @Test
    void editOutOfBounds_negativeOldLen_throws() {
        var ip = new IncrementalParser(parser, "foo");

        assertThatThrownBy(() -> ip.edit(0, -1, "x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("out of bounds");
    }

    @Test
    void editOutOfBounds_rangeBeyondInput_throws() {
        var ip = new IncrementalParser(parser, "foo");

        assertThatThrownBy(() -> ip.edit(2, 5, "x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("out of bounds");
    }

    @Test
    void edit_nullNewText_throws() {
        var ip = new IncrementalParser(parser, "foo");

        assertThatThrownBy(() -> ip.edit(0, 0, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_nullParser_throws() {
        assertThatThrownBy(() -> new IncrementalParser(null, "foo"))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_nullInitialInput_throws() {
        assertThatThrownBy(() -> new IncrementalParser(parser, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void parserAccessor_returnsConstructedParser() {
        var ip = new IncrementalParser(parser, "foo");

        assertThat(ip.parser()).isSameAs(parser);
    }

    @Test
    void editAfterFailedInitialParse_stillWorks() {
        var ip = new IncrementalParser(parser, "@@@");
        assertThat(ip.diagnostics()).isNotEmpty();

        var result = ip.edit(0, 3, "foo");

        assertThat(ip.input()).isEqualTo("foo");
        var fresh = parser.parse("foo");
        assertCstEquivalent(result.cst(), fresh.cst());
        assertThat(ip.diagnostics()).isEqualTo(fresh.diagnostics());
    }

    @Test
    void parseResult_returnedFromEdit_isSameAsCurrent() {
        var ip = new IncrementalParser(parser, "foo");

        ParseResult result = ip.edit(3, 0, ", bar");

        assertThat(result.cst()).isSameAs(ip.current());
        assertThat(result.cst().tokens()).isSameAs(ip.currentTokens());
    }

    @Test
    void defaultCheckpointRules_areExposed() {
        var ip = new IncrementalParser(parser, "foo");

        assertThat(ip.checkpointRules()).isEqualTo(IncrementalParser.DEFAULT_CHECKPOINT_RULES);
    }

    @Test
    void customCheckpointRules_areStored() {
        var custom = java.util.Set.of("Item", "File");
        var ip = new IncrementalParser(parser, "foo", custom);

        assertThat(ip.checkpointRules()).isEqualTo(custom);
    }

    @Test
    void constructor_nullCheckpointRules_throws() {
        assertThatThrownBy(() -> new IncrementalParser(parser, "foo", null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void manyEdits_finalStateMatchesFullReparse() {
        // Stress-test windowed splice across a sequence of varied edits.
        var ip = new IncrementalParser(parser, "foo");
        ip.edit(3, 0, ", bar");          // foo, bar
        ip.edit(0, 3, "bar");            // bar, bar
        ip.edit(8, 0, ", foo, bar");     // bar, bar, foo, bar
        ip.edit(5, 3, "foo");            // bar, foo, foo, bar
        var result = ip.edit(0, 3, "foo");  // foo, foo, foo, bar

        var expected = "foo, foo, foo, bar";
        var fresh = parser.parse(expected);
        assertThat(ip.input()).isEqualTo(expected);
        assertCstEquivalent(result.cst(), fresh.cst());
        assertCstEquivalent(ip.current(), fresh.cst());
        assertThat(ip.diagnostics()).isEqualTo(fresh.diagnostics());
    }
}
