package org.pragmatica.peg;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.generator.ErrorReporting;

import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the negative lookahead codegen bug.
 *
 * The generated parser incorrectly skipped whitespace before predicate expressions
 * (And/Not) inside sequences, causing !ClauseKW to fail because whitespace consumption
 * moved the position past the keyword boundary.
 *
 * @see <a href="BUG-NEGATIVE-LOOKAHEAD-CODEGEN.md">Bug description</a>
 */
class NegativeLookaheadBugTest {

    static final String GRAMMAR = """
        Input      <- Statement ';'?
        Statement  <- SelectStmt / Other
        SelectStmt <- SelectKW TargetList FromClause?
        TargetList <- TargetElem (',' TargetElem)*
        TargetElem <- Expr (AsKW ColLabel / !ClauseKW ColLabel)?
        Expr       <- ColRef / Literal
        ColRef     <- ColId
        Literal    <- < [0-9]+ >
        FromClause <- FromKW ColId
        ColId      <- !ReservedKW < [a-zA-Z_] [a-zA-Z0-9_]* >
        ColLabel   <- < [a-zA-Z_] [a-zA-Z0-9_]* >
        Other      <- (!';' .)*
        ClauseKW   <- ReservedKW / ('SET'i / 'ORDER'i / 'GROUP'i / 'WHERE'i) ![a-zA-Z0-9_]
        ReservedKW <- ('SELECT'i / 'FROM'i / 'AS'i / 'AND'i / 'OR'i / 'NOT'i) ![a-zA-Z0-9_]
        SelectKW   <- < 'SELECT'i ![a-zA-Z0-9_] >
        FromKW     <- < 'FROM'i ![a-zA-Z0-9_] >
        AsKW       <- < 'AS'i ![a-zA-Z0-9_] >
        %whitespace <- [ \\t\\r\\n]*
        """;

    @Test
    void interpretedParserSucceeds() {
        var parser = PegParser.fromGrammar(GRAMMAR).unwrap();
        var result = parser.parseCst("SELECT name FROM users");

        assertThat(result.isSuccess())
            .as("Interpreted parser should succeed on 'SELECT name FROM users'")
            .isTrue();
    }

    @Test
    void generatedCstParserSucceeds() throws Exception {
        var source = PegParser.generateCstParser(
            GRAMMAR,
            "test.generated.lookahead",
            "SqlParser",
            ErrorReporting.ADVANCED
        ).unwrap();

        var parser = compileAndInstantiate(source, "test.generated.lookahead.SqlParser");
        var parseMethod = parser.getClass().getMethod("parse", String.class);

        var result = parseMethod.invoke(parser, "SELECT name FROM users");

        assertThat(result.toString())
            .as("Generated CST parser should succeed on 'SELECT name FROM users'")
            .contains("Success");
    }

    private Object compileAndInstantiate(String source, String className) throws Exception {
        var tempDir = Files.createTempDirectory("peglib-test");
        var packagePath = className.substring(0, className.lastIndexOf('.')).replace('.', '/');
        var simpleClassName = className.substring(className.lastIndexOf('.') + 1);

        var packageDir = tempDir.resolve(packagePath);
        Files.createDirectories(packageDir);

        var sourceFile = packageDir.resolve(simpleClassName + ".java");
        Files.writeString(sourceFile, source);

        var compiler = ToolProvider.getSystemJavaCompiler();
        var errStream = new java.io.ByteArrayOutputStream();
        var result = compiler.run(null, null, errStream,
            "-d", tempDir.toString(),
            "-cp", System.getProperty("java.class.path"),
            sourceFile.toString()
        );

        if (result != 0) {
            System.err.println("=== Compilation errors ===");
            System.err.println(errStream);
            throw new RuntimeException("Compilation failed for " + className + ": " + errStream);
        }

        var classLoader = new URLClassLoader(new URL[]{tempDir.toUri().toURL()});
        var parserClass = classLoader.loadClass(className);
        return parserClass.getDeclaredConstructor().newInstance();
    }
}
