package org.pragmatica.peg.incremental;

import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.cst.CstArray;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seeded edits over valid and broken inputs, each compared with a fresh parse. Before 0.7.4 the
 * partial path could report a broken file as clean: it patched diagnostics subtree by subtree, and it
 * resumed after a token inserted at a subtree's edge.
 */
class IncrementalDiagnosticsDifferentialTest {
    private static final String GRAMMAR = """
        File <- Stmt (';' Stmt)* ';'?
        Stmt <- Name '=' Expr / Call '!'
        Expr <- Term ('+' Term)*
        Term <- Call / Name / Num
        Call <- Name '(' (Expr (',' Expr)*)? ')'
        Name <- [a-z]+
        Num  <- [0-9]+
        %whitespace <- [ ]+
        """;
    private static final String[] PIECES = {"a", "b", "1", "22", "(", ")", ",", "=", "+", ";", " ", "!", "", "", "f(a)", "x = 1"};

    private static String shape(CstArray cst) {
        var sb = new StringBuilder();

        for (int i = 0; i < cst.nodeCount(); i++) {
            sb.append(cst.kindAt(i)).append(':').append(cst.parentAt(i)).append(':').append(cst.firstTokenAt(i)).append('-').append(cst.lastTokenAt(i)).append(' ');
        }

        return sb.toString();
    }

    @Test
    void editsOverBrokenInput_reportExactlyWhatAFreshParseReports() {
        var parser = PegParser.fromGrammar(GRAMMAR).unwrap();
        int partial = 0;

        for (int seed = 0; seed < 400; seed++) {
            var random = new Random(seed);
            var ip = new IncrementalParser(parser, "x = f(a, 1) + g(b); y = h(1 + 2); f(a)!", Set.of("Stmt", "Expr", "Call", "Term"));

            for (int step = 0; step < 12; step++) {
                var before = ip.input();
                int offset = random.nextInt(before.length() + 1);
                int length = Math.min(random.nextInt(3), before.length() - offset);
                var text = PIECES[random.nextInt(PIECES.length)];
                int count = ip.partialReparseCount();

                ip.edit(offset, length, text);

                if (ip.partialReparseCount() > count) {
                    partial++;
                }

                var fresh = parser.parse(ip.input());

                assertThat(ip.diagnostics()).as("seed %d step %d: '%s' -> '%s'", seed, step, before, ip.input())
                                            .isEqualTo(fresh.diagnostics());
                assertThat(shape(ip.current())).as("seed %d step %d: '%s'", seed, step, ip.input())
                                               .isEqualTo(shape(fresh.cst()));
            }
        }

        assertThat(partial).as("the partial path must be exercised, or this test compares nothing").isGreaterThan(50);
    }

    /** An edit at a subtree's edge can fuse with the neighbouring token; the subtree alone still parses. */
    @Test
    void editThatChangesTheTokenAfterTheSubtree_isNotSpliced() {
        assertEditMatchesFreshParse("File <- Item '>' Name / Name '->' '!'\nItem <- Name Op?\nOp <- '+' / '-'\nName <- [a-z]+\n",
                                    "a+>b", 1, 1, "-");
    }

    @Test
    void editThatChangesTheTokenBeforeTheSubtree_isNotSpliced() {
        assertEditMatchesFreshParse("File <- '<' Item / '<-' '!'\nItem <- Op? Name\nOp <- '+' / '-'\nName <- [a-z]+\n",
                                    "<+a", 1, 1, "-");
    }

    private static void assertEditMatchesFreshParse(String grammar, String input, int offset, int length, String text) {
        var parser = PegParser.fromGrammar(grammar).unwrap();
        var ip = new IncrementalParser(parser, input, Set.of("Item"));

        assertThat(ip.diagnostics()).isEmpty();
        ip.edit(offset, length, text);
        assertThat(ip.diagnostics()).isNotEmpty()
                                    .isEqualTo(parser.parse(ip.input()).diagnostics());
        assertThat(shape(ip.current())).isEqualTo(shape(parser.parse(ip.input()).cst()));
    }
}
