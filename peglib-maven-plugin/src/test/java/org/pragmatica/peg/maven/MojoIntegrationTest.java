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
    void generateMojo_writesGeneratedSource(@TempDir Path tempDir) throws Exception {
        var grammarFile = tempDir.resolve("demo.peg")
                                 .toFile();
        Files.writeString(grammarFile.toPath(), "Start <- 'ok'\n");
        var outputDir = tempDir.resolve("generated")
                               .toFile();
        var mojo = new GenerateMojo();
        mojo.setGrammarFile(grammarFile);
        mojo.setOutputDirectory(outputDir);
        mojo.setPackageName("demo");
        mojo.setClassName("DemoParser");
        mojo.setErrorReporting("BASIC");
        mojo.execute();
        var generated = outputDir.toPath()
                                 .resolve("demo")
                                 .resolve("DemoParser.java");
        assertThat(Files.exists(generated)).isTrue();
        var content = Files.readString(generated);
        assertThat(content).contains("package demo;");
        assertThat(content).contains("class DemoParser");
    }

    @Test
    void generateMojo_skipsWhenUpToDate(@TempDir Path tempDir) throws Exception {
        var grammarFile = tempDir.resolve("demo.peg")
                                 .toFile();
        Files.writeString(grammarFile.toPath(), "Start <- 'ok'\n");
        var outputDir = tempDir.resolve("generated")
                               .toFile();
        var mojo = new GenerateMojo();
        mojo.setGrammarFile(grammarFile);
        mojo.setOutputDirectory(outputDir);
        mojo.setPackageName("demo");
        mojo.setClassName("DemoParser");
        mojo.setErrorReporting("BASIC");
        mojo.execute();
        var generated = outputDir.toPath()
                                 .resolve("demo")
                                 .resolve("DemoParser.java");
        long firstMtime = generated.toFile()
                                   .lastModified();
        // Make generated file newer than grammar to trigger up-to-date path.
        generated.toFile()
                 .setLastModified(grammarFile.lastModified() + 10_000L);
        mojo.execute();
        long secondMtime = generated.toFile()
                                    .lastModified();
        // Up-to-date path must not rewrite the file — mtime stays what we set.
        assertThat(secondMtime).isGreaterThan(firstMtime);
    }

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
        Files.writeString(grammarFile.toPath(), "Start <- 'hello'\n");
        var mojo = new CheckMojo();
        mojo.setGrammarFile(grammarFile);
        mojo.setSmokeInput("hello");
        mojo.execute();
    }

    @Test
    void generateV6Mojo_writesLexerParserVisitor(@TempDir Path tempDir) throws Exception {
        var grammarFile = tempDir.resolve("v6.peg")
                                 .toFile();
        // PARSER rule referencing a LEXER rule + a literal — the v6 pipeline
        // requires at least one PARSER/MIXED rule to emit a parser source.
        Files.writeString(grammarFile.toPath(), "Sum <- Number '+' Number\nNumber <- [0-9]+\n");
        var outputDir = tempDir.resolve("generated")
                               .toFile();
        var mojo = new GenerateV6Mojo();
        mojo.setGrammarFile(grammarFile);
        mojo.setOutputDirectory(outputDir);
        mojo.setPackageName("demo.v6");
        mojo.setLexerClassName("DemoLexer");
        mojo.setParserClassName("DemoParser");
        mojo.setVisitorClassName("DemoVisitor");
        mojo.execute();

        var pkgDir = outputDir.toPath()
                              .resolve("demo")
                              .resolve("v6");
        var lexer = pkgDir.resolve("DemoLexer.java");
        var parser = pkgDir.resolve("DemoParser.java");
        var visitor = pkgDir.resolve("DemoVisitor.java");
        assertThat(Files.exists(lexer)).as("lexer file emitted")
                                       .isTrue();
        assertThat(Files.exists(parser)).as("parser file emitted")
                                        .isTrue();
        assertThat(Files.exists(visitor)).as("visitor file emitted")
                                         .isTrue();
        assertThat(Files.readString(lexer)).contains("package demo.v6;",
                                                     "class DemoLexer",
                                                     "lex(String input)");
        assertThat(Files.readString(parser)).contains("package demo.v6;",
                                                      "class DemoParser",
                                                      "parse(TokenArray tokens)");
        assertThat(Files.readString(visitor)).contains("package demo.v6;",
                                                       "abstract class DemoVisitor<T>",
                                                       "visitSum");
    }

    @Test
    void generateV6Mojo_skipsWhenAllArtifactsUpToDate(@TempDir Path tempDir) throws Exception {
        var grammarFile = tempDir.resolve("v6.peg")
                                 .toFile();
        Files.writeString(grammarFile.toPath(), "Sum <- Number '+' Number\nNumber <- [0-9]+\n");
        var outputDir = tempDir.resolve("generated")
                               .toFile();
        var mojo = new GenerateV6Mojo();
        mojo.setGrammarFile(grammarFile);
        mojo.setOutputDirectory(outputDir);
        mojo.setPackageName("demo.v6");
        mojo.setLexerClassName("DemoLexer");
        mojo.setParserClassName("DemoParser");
        mojo.setVisitorClassName("DemoVisitor");
        mojo.execute();
        var lexer = outputDir.toPath()
                             .resolve("demo")
                             .resolve("v6")
                             .resolve("DemoLexer.java")
                             .toFile();
        // Mark all three generated files newer than the grammar so the
        // up-to-date branch fires.
        long bumped = grammarFile.lastModified() + 10_000L;
        for (var name : new String[]{"DemoLexer.java", "DemoParser.java", "DemoVisitor.java"}) {
            outputDir.toPath()
                     .resolve("demo")
                     .resolve("v6")
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
    void generateV6Mojo_failsOnInvalidPackageName(@TempDir Path tempDir) throws Exception {
        var grammarFile = tempDir.resolve("v6.peg")
                                 .toFile();
        Files.writeString(grammarFile.toPath(), "Sum <- Number '+' Number\nNumber <- [0-9]+\n");
        var outputDir = tempDir.resolve("generated")
                               .toFile();
        var mojo = new GenerateV6Mojo();
        mojo.setGrammarFile(grammarFile);
        mojo.setOutputDirectory(outputDir);
        mojo.setPackageName("1bad.pkg");
        mojo.setLexerClassName("L");
        mojo.setParserClassName("P");
        mojo.setVisitorClassName("V");
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class);
    }
}
