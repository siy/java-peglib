package org.pragmatica.peg.examples;

import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.parser.Parser;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.pragmatica.peg.examples.Java25GrammarExample.JAVA_GRAMMAR;

/**
 * Test Java25 CST parser against real Java projects.
 *
 * Output format: <full file name> - OK|ERROR|FAILURE
 * - OK: parsed successfully
 * - ERROR: input file has syntax errors (javac also fails)
 * - FAILURE: parser bug (javac accepts but parser fails)
 */
public class Java25ParserTest {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: Java25ParserTest <directory> [directory...]");
            System.exit(1);
        }

        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();

        var ok = new AtomicInteger();
        var error = new AtomicInteger();
        var failure = new AtomicInteger();

        for (var dir : args) {
            var path = Path.of(dir);
            if (!Files.isDirectory(path)) {
                System.err.println("Not a directory: " + dir);
                continue;
            }

            Files.walk(path)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.toString().contains("/target/"))
                .filter(p -> !p.toString().contains("/build/"))
                .filter(p -> !p.toString().contains("/."))
                .forEach(file -> {
                    var result = testFile(parser, file);
                    System.out.println(file + " - " + result);
                    switch (result) {
                        case "OK" -> ok.incrementAndGet();
                        case "ERROR" -> error.incrementAndGet();
                        case "FAILURE" -> failure.incrementAndGet();
                    }
                });
        }

        System.out.println();
        System.out.println("=== Summary ===");
        System.out.println("OK: " + ok.get());
        System.out.println("ERROR: " + error.get());
        System.out.println("FAILURE: " + failure.get());
        System.out.println("Total: " + (ok.get() + error.get() + failure.get()));
    }

    private static String testFile(Parser parser, Path file) {
        try {
            var content = Files.readString(file);
            var result = parser.parseCst(content);

            if (result.isSuccess()) {
                return "OK";
            }

            // Parser failed - check with javac
            if (javacAccepts(file)) {
                return "FAILURE"; // Parser bug
            } else {
                return "ERROR"; // Legitimate syntax error
            }
        } catch (Exception e) {
            return "FAILURE"; // Unexpected exception
        }
    }

    private static boolean javacAccepts(Path file) {
        try {
            var process = new ProcessBuilder("javac", "-proc:none", "-d", "/tmp", file.toString())
                .redirectErrorStream(true)
                .start();

            var completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return false;
            }

            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
