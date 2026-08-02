package org.pragmatica.peg.maven;

import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration test for the peglib-maven-plugin. Invokes mojos
 * programmatically to verify that the plugin's goals function without needing
 * the full Maven test harness.
 */
class MojoIntegrationTest {

    @Test
    void lintMojo_succeedsOnCleanGrammar(@TempDir Path tempDir) throws Exception {
        var grammarFile = tempDir.resolve("clean.peg")
                                 .toFile();
        Files.writeString(grammarFile.toPath(), "Start <- 'ok'\n");
        var mojo = new LintMojo();
        mojo.setGrammarFile(grammarFile);
        mojo.setFailOnWarning(false);
        mojo.execute();
    }

    @Test
    void lintMojo_failsOnDuplicateLiteral(@TempDir Path tempDir) throws Exception {
        var grammarFile = tempDir.resolve("dup.peg")
                                 .toFile();
        Files.writeString(grammarFile.toPath(), "Start <- 'foo' / 'foo'\n");
        var mojo = new LintMojo();
        mojo.setGrammarFile(grammarFile);
        mojo.setFailOnWarning(false);
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class);
    }

    @Test
    void checkMojo_endToEndWithSmokeInput(@TempDir Path tempDir) throws Exception {
        var grammarFile = tempDir.resolve("smoke.peg")
                                 .toFile();
        // PARSER rule referencing a LEXER rule — the pipeline requires at
        // least one PARSER/MIXED rule before it can emit a parser to smoke-test.
        Files.writeString(grammarFile.toPath(), "Sum <- Number '+' Number\nNumber <- [0-9]+\n");
        var mojo = new CheckMojo();
        mojo.setGrammarFile(grammarFile);
        mojo.setSmokeInput("1+2");
        mojo.execute();
    }

    @Test
    void generateMojo_writesLexerParserVisitor(@TempDir Path tempDir) throws Exception {
        var grammarFile = tempDir.resolve("gen.peg")
                                 .toFile();
        // PARSER rule referencing a LEXER rule + a literal — the pipeline
        // requires at least one PARSER/MIXED rule to emit a parser source.
        Files.writeString(grammarFile.toPath(), "Sum <- Number '+' Number\nNumber <- [0-9]+\n");
        var outputDir = tempDir.resolve("generated")
                               .toFile();
        var mojo = new GenerateMojo();
        mojo.setGrammarFile(grammarFile);
        mojo.setOutputDirectory(outputDir);
        mojo.setPackageName("demo.gen");
        mojo.setLexerClassName("DemoLexer");
        mojo.setParserClassName("DemoParser");
        mojo.setVisitorClassName("DemoVisitor");
        mojo.execute();

        var pkgDir = outputDir.toPath()
                              .resolve("demo")
                              .resolve("gen");
        var lexer = pkgDir.resolve("DemoLexer.java");
        var parser = pkgDir.resolve("DemoParser.java");
        var visitor = pkgDir.resolve("DemoVisitor.java");
        assertThat(Files.exists(lexer)).as("lexer file emitted")
                                       .isTrue();
        assertThat(Files.exists(parser)).as("parser file emitted")
                                        .isTrue();
        assertThat(Files.exists(visitor)).as("visitor file emitted")
                                         .isTrue();
        assertThat(Files.readString(lexer)).contains("package demo.gen;",
                                                     "class DemoLexer",
                                                     "lex(String input)");
        assertThat(Files.readString(parser)).contains("package demo.gen;",
                                                      "class DemoParser",
                                                      "parse(TokenArray tokens)");
        assertThat(Files.readString(visitor)).contains("package demo.gen;",
                                                       "abstract class DemoVisitor<T>",
                                                       "visitSum");
    }

    @Test
    void generateMojo_skipsWhenAllArtifactsUpToDate(@TempDir Path tempDir) throws Exception {
        var grammarFile = tempDir.resolve("gen.peg")
                                 .toFile();
        Files.writeString(grammarFile.toPath(), "Sum <- Number '+' Number\nNumber <- [0-9]+\n");
        var outputDir = tempDir.resolve("generated")
                               .toFile();
        var mojo = new GenerateMojo();
        mojo.setGrammarFile(grammarFile);
        mojo.setOutputDirectory(outputDir);
        mojo.setPackageName("demo.gen");
        mojo.setLexerClassName("DemoLexer");
        mojo.setParserClassName("DemoParser");
        mojo.setVisitorClassName("DemoVisitor");
        mojo.execute();
        var lexer = outputDir.toPath()
                             .resolve("demo")
                             .resolve("gen")
                             .resolve("DemoLexer.java")
                             .toFile();
        // Mark all three generated files newer than the grammar so the
        // up-to-date branch fires.
        long bumped = grammarFile.lastModified() + 10_000L;
        for (var name : new String[]{"DemoLexer.java", "DemoParser.java", "DemoVisitor.java"}) {
            outputDir.toPath()
                     .resolve("demo")
                     .resolve("gen")
                     .resolve(name)
                     .toFile()
                     .setLastModified(bumped);
        }
        long beforeMtime = lexer.lastModified();
        mojo.execute();
        // Up-to-date path must not rewrite any file — mtimes stay what we set.
        assertThat(lexer.lastModified()).isEqualTo(beforeMtime);
    }

    @Test
    void generateMojo_failsOnInvalidPackageName(@TempDir Path tempDir) throws Exception {
        var grammarFile = tempDir.resolve("gen.peg")
                                 .toFile();
        Files.writeString(grammarFile.toPath(), "Sum <- Number '+' Number\nNumber <- [0-9]+\n");
        var outputDir = tempDir.resolve("generated")
                               .toFile();
        var mojo = new GenerateMojo();
        mojo.setGrammarFile(grammarFile);
        mojo.setOutputDirectory(outputDir);
        mojo.setPackageName("1bad.pkg");
        mojo.setLexerClassName("L");
        mojo.setParserClassName("P");
        mojo.setVisitorClassName("V");
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class);
    }
}
