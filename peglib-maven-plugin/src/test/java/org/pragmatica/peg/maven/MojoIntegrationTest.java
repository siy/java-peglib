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
    void generateMojo_resolvesImportsFromGrammarDirectory(@TempDir Path tempDir) throws Exception {
        // 0.7.2 — %import through the plugin. The imported grammar sits beside the root
        // grammar and is found without configuring importDirectory. An unaliased
        // "%import Shared.Number" binds the rule as Shared_Number.
        Files.writeString(tempDir.resolve("Shared.peg"), "Number <- [0-9]+\n");
        var grammarFile = tempDir.resolve("root.peg")
                                 .toFile();
        Files.writeString(grammarFile.toPath(),
                          "%import Shared.Number\nSum <- Shared_Number '+' Shared_Number\n");
        var outputDir = tempDir.resolve("generated")
                               .toFile();
        var mojo = new GenerateMojo();
        mojo.setGrammarFile(grammarFile);
        mojo.setOutputDirectory(outputDir);
        mojo.setPackageName("demo.imp");
        mojo.setLexerClassName("ImpLexer");
        mojo.setParserClassName("ImpParser");
        mojo.setVisitorClassName("ImpVisitor");
        mojo.execute();

        var pkgDir = outputDir.toPath()
                              .resolve("demo")
                              .resolve("imp");
        assertThat(Files.exists(pkgDir.resolve("ImpParser.java"))).as("parser emitted from a grammar using %import")
                                                                  .isTrue();
        assertThat(Files.readString(pkgDir.resolve("ImpVisitor.java"))).as("the imported rule must reach the generated visitor")
                                                                       .contains("visitSum");
    }

    @Test
    void generateMojo_failsWhenImportedGrammarIsMissing(@TempDir Path tempDir) throws Exception {
        // Guards the test above from passing vacuously: without Shared.peg beside it,
        // the same grammar must fail rather than silently generate something.
        var grammarFile = tempDir.resolve("root.peg")
                                 .toFile();
        Files.writeString(grammarFile.toPath(),
                          "%import Missing.Number\nSum <- Missing_Number '+' Missing_Number\n");
        var mojo = new GenerateMojo();
        mojo.setGrammarFile(grammarFile);
        mojo.setOutputDirectory(tempDir.resolve("generated")
                                       .toFile());
        mojo.setPackageName("demo.missing");
        mojo.setLexerClassName("MLexer");
        mojo.setParserClassName("MParser");
        mojo.setVisitorClassName("MVisitor");

        // The message matters, not just the throw: before %import was wired, this same
        // grammar failed at rule-reference validation with "references undefined rule",
        // so asserting only the exception type passes whether or not the feature exists.
        assertThatThrownBy(mojo::execute).as("a missing imported grammar must fail the build")
                                         .isInstanceOf(MojoFailureException.class)
                                         .hasMessageContaining("Missing")
                                         .hasMessageNotContaining("references undefined rule");
    }

    @Test
    void lintMojo_resolvesImportsFromGrammarDirectory(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("Shared.peg"), "Number <- [0-9]+\n");
        var grammarFile = tempDir.resolve("root.peg")
                                 .toFile();
        Files.writeString(grammarFile.toPath(),
                          "%import Shared.Number\nSum <- Shared_Number '+' Shared_Number\n");
        var mojo = new LintMojo();
        mojo.setGrammarFile(grammarFile);

        mojo.execute();
    }

    @Test
    void lintMojo_failsWhenImportedGrammarIsMissing(@TempDir Path tempDir) throws Exception {
        var grammarFile = tempDir.resolve("root.peg")
                                 .toFile();
        Files.writeString(grammarFile.toPath(),
                          "%import Missing.Number\nSum <- Missing_Number '+' Missing_Number\n");
        var mojo = new LintMojo();
        mojo.setGrammarFile(grammarFile);

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class)
                                         .hasMessageContaining("Missing");
    }

    @Test
    void checkMojo_resolvesImportsFromGrammarDirectory(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("Shared.peg"), "Number <- [0-9]+\n");
        var grammarFile = tempDir.resolve("root.peg")
                                 .toFile();
        Files.writeString(grammarFile.toPath(),
                          "%import Shared.Number\nSum <- Shared_Number '+' Shared_Number\n");
        var mojo = new CheckMojo();
        mojo.setGrammarFile(grammarFile);
        mojo.setSmokeInput("1+2");

        mojo.execute();
    }

    @Test
    void checkMojo_honoursImportDirectoryInBothOfItsStages(@TempDir Path tempDir) throws Exception {
        // Regression guard: CheckMojo runs an embedded LintMojo and then builds a parser.
        // The embedded lint is hand-constructed, so Plexus never injects importDirectory into
        // it — without explicit propagation the two stages resolve %import against different
        // directories and the lint stage fails on a perfectly valid grammar.
        var grammars = Files.createDirectory(tempDir.resolve("grammars"));
        var sources = Files.createDirectory(tempDir.resolve("sources"));

        Files.writeString(grammars.resolve("Shared.peg"), "Number <- [0-9]+\n");
        var grammarFile = sources.resolve("root.peg")
                                 .toFile();
        Files.writeString(grammarFile.toPath(),
                          "%import Shared.Number\nSum <- Shared_Number '+' Shared_Number\n");
        var mojo = new CheckMojo();
        mojo.setGrammarFile(grammarFile);
        mojo.setImportDirectory(grammars.toFile());
        mojo.setSmokeInput("1+2");

        mojo.execute();
    }

    @Test
    void generateMojo_honoursImportDirectoryOutsideTheGrammarDirectory(@TempDir Path tempDir) throws Exception {
        var grammars = Files.createDirectory(tempDir.resolve("grammars"));
        var sources = Files.createDirectory(tempDir.resolve("sources"));

        Files.writeString(grammars.resolve("Shared.peg"), "Number <- [0-9]+\n");
        var grammarFile = sources.resolve("root.peg")
                                 .toFile();
        Files.writeString(grammarFile.toPath(),
                          "%import Shared.Number\nSum <- Shared_Number '+' Shared_Number\n");
        var outputDir = tempDir.resolve("generated")
                               .toFile();
        var mojo = new GenerateMojo();
        mojo.setGrammarFile(grammarFile);
        mojo.setImportDirectory(grammars.toFile());
        mojo.setOutputDirectory(outputDir);
        mojo.setPackageName("demo.dir");
        mojo.setLexerClassName("DLexer");
        mojo.setParserClassName("DParser");
        mojo.setVisitorClassName("DVisitor");
        mojo.execute();

        assertThat(Files.exists(outputDir.toPath()
                                         .resolve("demo")
                                         .resolve("dir")
                                         .resolve("DParser.java"))).as("importDirectory override must be honoured")
                                                                   .isTrue();
    }

    @Test
    void generateMojo_regeneratesWhenAnImportedGrammarChanges(@TempDir Path tempDir) throws Exception {
        // The up-to-date check compares targets against the ROOT grammar's mtime only, so a
        // grammar with %import must not take the skip path — otherwise editing Shared.peg
        // silently ships generated sources built from its previous content.
        var shared = tempDir.resolve("Shared.peg");

        Files.writeString(shared, "Number <- [0-9]+\n");
        var grammarFile = tempDir.resolve("root.peg")
                                 .toFile();
        Files.writeString(grammarFile.toPath(),
                          "%import Shared.Number\nSum <- Shared_Number '+' Shared_Number\n");
        var outputDir = tempDir.resolve("generated")
                               .toFile();
        var mojo = new GenerateMojo();
        mojo.setGrammarFile(grammarFile);
        mojo.setOutputDirectory(outputDir);
        mojo.setPackageName("demo.stale");
        mojo.setLexerClassName("SLexer");
        mojo.setParserClassName("SParser");
        mojo.setVisitorClassName("SVisitor");
        mojo.execute();

        var lexer = outputDir.toPath()
                             .resolve("demo")
                             .resolve("stale")
                             .resolve("SLexer.java");
        var first = Files.readString(lexer);
        // Change ONLY the imported grammar, leaving the root untouched.
        Files.writeString(shared, "Number <- [0-9]+ 'u'?\n");
        mojo.execute();

        assertThat(Files.readString(lexer)).as("editing an imported grammar must not leave stale output")
                                           .isNotEqualTo(first);
    }

    @Test
    void generateMojo_regeneratesWhenTheGeneratorVersionChanges(@TempDir Path tempDir) throws Exception {
        // Mtime alone cannot see a plugin/generator upgrade: the grammar is untouched, so the
        // skip path fires and leaves silently stale sources. That is very hard to notice in a
        // project that commits generated code — it produced a wrong bug report downstream.
        var grammarFile = tempDir.resolve("gen.peg")
                                 .toFile();

        Files.writeString(grammarFile.toPath(), "Sum <- Number '+' Number\nNumber <- [0-9]+\n");
        var outputDir = tempDir.resolve("generated")
                               .toFile();
        var mojo = new GenerateMojo();

        mojo.setGrammarFile(grammarFile);
        mojo.setOutputDirectory(outputDir);
        mojo.setPackageName("demo.stamp");
        mojo.setLexerClassName("StampLexer");
        mojo.setParserClassName("StampParser");
        mojo.setVisitorClassName("StampVisitor");
        mojo.execute();

        var lexer = outputDir.toPath()
                             .resolve("demo")
                             .resolve("stamp")
                             .resolve("StampLexer.java");

        assertThat(Files.readString(lexer)).as("generated sources carry a generator stamp")
                                           .startsWith("// peglib-generator: ");
        // Simulate output produced by an older generator, leaving mtimes untouched.
        var body = Files.readString(lexer)
                        .lines()
                        .skip(1)
                        .collect(java.util.stream.Collectors.joining("\n"));

        Files.writeString(lexer, "// peglib-generator: 0.0.1-old\n" + body);
        mojo.execute();

        assertThat(Files.readString(lexer)).as("a stamp mismatch must force regeneration")
                                           .doesNotContain("0.0.1-old");
        // The version alone cannot distinguish two builds of the SAME version, which is the
        // normal case while a release is unreleased: reinstalling with a fixed emitter leaves the
        // stamp identical, consumers skip regeneration, and they measure a parser that no longer
        // matches the library. The stamp therefore carries a build identity as well.
        assertThat(Files.readString(lexer)
                        .lines()
                        .findFirst()
                        .orElse("")).as("the stamp must identify the BUILD, not only the version")
                                    .containsPattern("// peglib-generator: \\S+ \\(build:[0-9a-z]+\\)");
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
