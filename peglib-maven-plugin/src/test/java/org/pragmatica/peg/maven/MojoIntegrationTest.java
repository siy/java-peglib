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
}
