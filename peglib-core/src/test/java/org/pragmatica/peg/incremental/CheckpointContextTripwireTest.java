package org.pragmatica.peg.incremental;

import java.util.Set;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.cst.CstArray;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #51: a choice above a checkpoint can depend on the checkpoint's content. Editing {@code 1} to
 * {@code x} inside {@code Stmt} makes the earlier alternative {@code Decl} match, which only a parse
 * from above the checkpoint can see.
 */
class CheckpointContextTripwireTest {
    private static final String GRAMMAR = """
        File <- Item*
        Item <- Decl / Stmt
        Decl <- Name '=' Name ';'
        Stmt <- Name '=' Value ';'
        Value <- Num / Name
        Name <- [a-z]+
        Num  <- [0-9]+
        %whitespace <- [ ]+
        %parser Value
        """;

    private static String kinds(CstArray cst) {
        var sb = new StringBuilder();

        for (int i = 0; i < cst.nodeCount(); i++) {
            sb.append(cst.kindNameAt(i)).append(' ');
        }

        return sb.toString().trim();
    }

    /** Tripwire: pins the current, wrong result. When this fails, #51 is fixed: enable the test below. */
    @Test
    void earlierAlternativeMadeToMatch_isMissedByThePartialPath_issue51() {
        var parser = PegParser.fromGrammar(GRAMMAR).unwrap();
        var ip = new IncrementalParser(parser, "a = 1; b = 2;", Set.of("Stmt"));

        ip.edit(4, 1, "x");

        assertThat(ip.partialReparseCount()).isEqualTo(1);
        assertThat(kinds(ip.current())).isEqualTo("_ROOT File Item Stmt Value Item Stmt Value");
        assertThat(kinds(parser.parse(ip.input()).cst())).isEqualTo("_ROOT File Item Decl Item Stmt Value");
    }

    @Disabled("issue #51: the partial path cannot see a choice above the checkpoint")
    @Test
    void earlierAlternativeMadeToMatch_matchesAFreshParse() {
        var parser = PegParser.fromGrammar(GRAMMAR).unwrap();
        var ip = new IncrementalParser(parser, "a = 1; b = 2;", Set.of("Stmt"));

        ip.edit(4, 1, "x");

        assertThat(kinds(ip.current())).isEqualTo(kinds(parser.parse(ip.input()).cst()));
    }
}
