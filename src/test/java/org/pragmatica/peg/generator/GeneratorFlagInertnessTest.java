package org.pragmatica.peg.generator;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.parser.ParserConfig;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that threading {@link ParserConfig} through {@link ParserGenerator}
 * is inert as long as all perf flags are false (which is the {@link ParserConfig#DEFAULT}).
 *
 * <p>This is the commit #2 parity guarantee: the generator reads the new flags
 * but must not yet act on them. Byte-for-byte identity of the emitted source
 * between the no-config factory and the config-accepting factory (with {@code
 * DEFAULT}) proves commit #3's flag-driven emission has a clean baseline.
 */
class GeneratorFlagInertnessTest {

    @Test
    void generateCst_withDefaultConfig_isByteIdenticalToLegacyFactory() {
        var grammar = loadJava25Grammar();

        var legacySource = ParserGenerator.create(grammar, "gen", "Java25Parser")
                                          .generateCst();
        var configuredSource = ParserGenerator.create(grammar, "gen", "Java25Parser", ParserConfig.DEFAULT)
                                              .generateCst();

        assertEquals(legacySource, configuredSource,
            "Generator output must be byte-identical when all perf flags are off");
    }

    @Test
    void generateCst_withDefaultConfigAndAdvancedReporting_isByteIdenticalToLegacyFactory() {
        var grammar = loadJava25Grammar();

        var legacySource = ParserGenerator.create(grammar, "gen", "Java25Parser", ErrorReporting.ADVANCED)
                                          .generateCst();
        var configuredSource = ParserGenerator.create(grammar, "gen", "Java25Parser",
                                                      ErrorReporting.ADVANCED, ParserConfig.DEFAULT)
                                              .generateCst();

        assertEquals(legacySource, configuredSource,
            "Generator output must be byte-identical for ADVANCED reporting when all perf flags are off");
    }

    @Test
    void generate_withDefaultConfig_isByteIdenticalToLegacyFactory() {
        var grammar = loadJava25Grammar();

        var legacySource = ParserGenerator.create(grammar, "gen", "Java25Parser")
                                          .generate();
        var configuredSource = ParserGenerator.create(grammar, "gen", "Java25Parser", ParserConfig.DEFAULT)
                                              .generate();

        assertEquals(legacySource, configuredSource,
            "Action-bearing generator output must be byte-identical when all perf flags are off");
    }

    private static Grammar loadJava25Grammar() {
        try {
            var text = Files.readString(Path.of("src/test/resources/java25.peg"));
            return GrammarParser.parse(text).unwrap();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load java25.peg", e);
        }
    }
}
