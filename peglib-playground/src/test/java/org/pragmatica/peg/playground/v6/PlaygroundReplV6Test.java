package org.pragmatica.peg.playground.v6;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PlaygroundReplV6Test {

    private static final String GRAMMAR = """
            Sum <- Number '+' Number
            Number <- [0-9]+
            %whitespace <- [ \\t]*
            """;

    @Test
    void parseValidInput_printsOkAndStats(@TempDir Path tempDir) throws Exception {
        Path grammar = tempDir.resolve("g.peg");
        Files.writeString(grammar, GRAMMAR, StandardCharsets.UTF_8);

        var buffer = new ByteArrayOutputStream();
        var out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        var repl = new PlaygroundReplV6(grammar,
                                        new BufferedReader(new StringReader("")),
                                        out);

        repl.handleCommand("12 + 34");

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("OK");
        assertThat(output).contains("nodes=");
    }

    @Test
    void quitCommand_signalsExit(@TempDir Path tempDir) throws Exception {
        Path grammar = tempDir.resolve("g.peg");
        Files.writeString(grammar, "R <- 'x'\n", StandardCharsets.UTF_8);

        var buffer = new ByteArrayOutputStream();
        var out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        var repl = new PlaygroundReplV6(grammar,
                                        new BufferedReader(new StringReader("")),
                                        out);

        boolean exit = repl.handleCommand(":quit");
        assertThat(exit).isTrue();
    }

    @Test
    void invalidInput_printsFailWithDiagnostic(@TempDir Path tempDir) throws Exception {
        Path grammar = tempDir.resolve("g.peg");
        Files.writeString(grammar,
                          """
                          Pair <- Head 'b'
                          Head <- 'a' '#'
                          """,
                          StandardCharsets.UTF_8);

        var buffer = new ByteArrayOutputStream();
        var out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        var repl = new PlaygroundReplV6(grammar,
                                        new BufferedReader(new StringReader("")),
                                        out);

        repl.handleCommand("a#x");

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertThat(output).containsAnyOf("FAIL", "error");
    }

    @Test
    void statusCommand_printsGrammarMetadata(@TempDir Path tempDir) throws Exception {
        Path grammar = tempDir.resolve("g.peg");
        Files.writeString(grammar, GRAMMAR, StandardCharsets.UTF_8);

        var buffer = new ByteArrayOutputStream();
        var out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        var repl = new PlaygroundReplV6(grammar,
                                        new BufferedReader(new StringReader("")),
                                        out);

        repl.handleCommand(":status");
        String output = buffer.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("grammar:");
        assertThat(output).contains("mtime=");
    }
}
