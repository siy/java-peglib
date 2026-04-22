package org.pragmatica.peg.playground;

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

class PlaygroundReplTest {

    @Test
    void parseValidInput_printsOkAndStats(@TempDir Path tempDir) throws Exception {
        Path grammar = tempDir.resolve("g.peg");
        Files.writeString(grammar,
                          "Number <- < [0-9]+ >\n%whitespace <- [ \\t]*\n",
                          StandardCharsets.UTF_8);

        var reader = new BufferedReader(new StringReader(""));
        var buffer = new ByteArrayOutputStream();
        var out = new PrintStream(buffer, true, StandardCharsets.UTF_8);

        var repl = new PlaygroundRepl(grammar, reader, out, false);
        repl.handleCommand("42");

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("OK");
        assertThat(output).contains("nodes=");
    }

    @Test
    void traceCommand_togglesTraceOutput(@TempDir Path tempDir) throws Exception {
        Path grammar = tempDir.resolve("g.peg");
        Files.writeString(grammar,
                          "Number <- < [0-9]+ >\n%whitespace <- [ ]*\n",
                          StandardCharsets.UTF_8);

        var buffer = new ByteArrayOutputStream();
        var out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        var repl = new PlaygroundRepl(grammar, new BufferedReader(new StringReader("")), out, false);

        repl.handleCommand(":trace on");
        repl.handleCommand("7");

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("rule entries:");
    }

    @Test
    void quitCommand_signalsExit(@TempDir Path tempDir) throws Exception {
        Path grammar = tempDir.resolve("g.peg");
        Files.writeString(grammar, "R <- 'x'\n", StandardCharsets.UTF_8);

        var buffer = new ByteArrayOutputStream();
        var out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        var repl = new PlaygroundRepl(grammar, new BufferedReader(new StringReader("")), out, false);

        boolean exit = repl.handleCommand(":quit");
        assertThat(exit).isTrue();
    }

    @Test
    void parseInvalidInput_printsFail(@TempDir Path tempDir) throws Exception {
        Path grammar = tempDir.resolve("g.peg");
        Files.writeString(grammar,
                          "Number <- < [0-9]+ >\n%whitespace <- [ ]*\n",
                          StandardCharsets.UTF_8);

        var buffer = new ByteArrayOutputStream();
        var out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        var repl = new PlaygroundRepl(grammar, new BufferedReader(new StringReader("")), out, false);

        repl.handleCommand("abc");

        String output = buffer.toString(StandardCharsets.UTF_8);
        // Output may be FAIL or include an error diagnostic — either path exercises the failure branch
        assertThat(output).containsAnyOf("FAIL", "error");
    }
}
