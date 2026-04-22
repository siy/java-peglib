package org.pragmatica.peg.incremental.examples;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.incremental.Edit;
import org.pragmatica.peg.incremental.IncrementalParser;
import org.pragmatica.peg.incremental.Session;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runnable example illustrating the idiomatic editor-loop use of
 * {@link IncrementalParser}: initialize a session over the initial buffer,
 * apply an {@link Edit} per user keystroke / batch, read the new tree and
 * stats off the returned {@link Session}.
 */
final class BasicEditorLoopExample {

    private static final String GRAMMAR = """
        Program <- Stmt*
        Stmt <- 'let' Ident '=' Number ';'
        Ident <- < [a-zA-Z][a-zA-Z0-9]* >
        Number <- < [0-9]+ >
        %whitespace <- [ \\t\\n]*
        """;

    @Test
    @DisplayName("Editor loop: initialize -> edit -> edit -> inspect stats")
    void editor_loop() {
        Grammar grammar = GrammarParser.parse(GRAMMAR).fold(
            cause -> { throw new IllegalStateException(cause.message()); },
            g -> g);
        IncrementalParser parser = IncrementalParser.create(grammar);
        Session session = parser.initialize("let x = 1;", 0);
        assertThat(session.root()).isNotNull();
        assertThat(session.text()).isEqualTo("let x = 1;");

        // User types ' 42' after the '1' (offset 9).
        session = session.edit(new Edit(9, 0, "42"));
        assertThat(session.text()).isEqualTo("let x = 142;");
        assertThat(session.stats().reparseCount()).isEqualTo(1);

        // User pastes a whole new statement at the start.
        session = session.edit(0, 0, "let y = 7; ");
        assertThat(session.text()).isEqualTo("let y = 7; let x = 142;");
        assertThat(session.stats().reparseCount()).isEqualTo(2);

        // User moves cursor — no reparse.
        var before = session;
        session = session.moveCursor(5);
        assertThat(session.cursor()).isEqualTo(5);
        assertThat(session.stats().reparseCount()).isEqualTo(before.stats().reparseCount());
    }
}
