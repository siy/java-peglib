package org.pragmatica.peg;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.tree.CstNode;
import org.pragmatica.peg.tree.SourceLocation;
import org.pragmatica.peg.tree.SourceSpan;
import org.pragmatica.peg.tree.Trivia;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that verify generated CST parsers correctly handle trivia.
 * These tests compile and run the generated parser at runtime.
 */
class GeneratedParserTriviaTest {

    @Test
    void generatedParser_capturesLeadingTrivia() throws Exception {
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- ([ \\t]+ / '//' [^\\n]* '\\n')+
            """;

        // Generate CST parser source
        var sourceResult = PegParser.generateCstParser(grammar, "test.trivia", "TriviaParser");
        assertThat(sourceResult.isSuccess()).isTrue();

        var source = sourceResult.unwrap();

        // Compile and load the parser
        var parserClass = compileAndLoad(source, "test.trivia.TriviaParser");
        var parser = parserClass.getDeclaredConstructor().newInstance();
        var parseMethod = parserClass.getMethod("parse", String.class);

        // Test parsing with leading whitespace
        @SuppressWarnings("unchecked")
        var result = (Result<Object>) parseMethod.invoke(parser, "  42");
        assertThat(result.isSuccess()).isTrue();

        var node = result.unwrap();
        assertThat(node).isNotNull();

        // Use reflection to check trivia
        var leadingTriviaMethod = node.getClass().getMethod("leadingTrivia");
        @SuppressWarnings("unchecked")
        var trivia = (List<?>) leadingTriviaMethod.invoke(node);

        assertThat(trivia).hasSize(1);
        var firstTrivia = trivia.getFirst();
        var textMethod = firstTrivia.getClass().getMethod("text");
        assertThat(textMethod.invoke(firstTrivia)).isEqualTo("  ");
    }

    @Test
    void generatedParser_capturesCommentAsTrivia() throws Exception {
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- ([ \\t]+ / '//' [^\\n]* '\\n')+
            """;

        var sourceResult = PegParser.generateCstParser(grammar, "test.comment", "CommentParser");
        assertThat(sourceResult.isSuccess()).isTrue();

        var source = sourceResult.unwrap();
        var parserClass = compileAndLoad(source, "test.comment.CommentParser");
        var parser = parserClass.getDeclaredConstructor().newInstance();
        var parseMethod = parserClass.getMethod("parse", String.class);

        @SuppressWarnings("unchecked")
        var result = (Result<Object>) parseMethod.invoke(parser, "// comment\n42");
        assertThat(result.isSuccess()).isTrue();

        var node = result.unwrap();
        var leadingTriviaMethod = node.getClass().getMethod("leadingTrivia");
        @SuppressWarnings("unchecked")
        var trivia = (List<?>) leadingTriviaMethod.invoke(node);

        assertThat(trivia).hasSize(1);
        var firstTrivia = trivia.getFirst();

        // Check it's classified as LineComment
        assertThat(firstTrivia.getClass().getSimpleName()).isEqualTo("LineComment");
        var textMethod = firstTrivia.getClass().getMethod("text");
        assertThat(textMethod.invoke(firstTrivia)).isEqualTo("// comment\n");
    }

    @Test
    void generatedParser_capturesMixedTrivia() throws Exception {
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- ([ \\t]+ / '//' [^\\n]* '\\n')+
            """;

        var sourceResult = PegParser.generateCstParser(grammar, "test.mixed", "MixedParser");
        assertThat(sourceResult.isSuccess()).isTrue();

        var source = sourceResult.unwrap();
        var parserClass = compileAndLoad(source, "test.mixed.MixedParser");
        var parser = parserClass.getDeclaredConstructor().newInstance();
        var parseMethod = parserClass.getMethod("parse", String.class);

        @SuppressWarnings("unchecked")
        var result = (Result<Object>) parseMethod.invoke(parser, "  // comment\n  42");
        assertThat(result.isSuccess()).isTrue();

        var node = result.unwrap();
        var leadingTriviaMethod = node.getClass().getMethod("leadingTrivia");
        @SuppressWarnings("unchecked")
        var trivia = (List<?>) leadingTriviaMethod.invoke(node);

        assertThat(trivia).hasSize(3);

        // First: whitespace
        assertThat(trivia.get(0).getClass().getSimpleName()).isEqualTo("Whitespace");

        // Second: line comment
        assertThat(trivia.get(1).getClass().getSimpleName()).isEqualTo("LineComment");

        // Third: whitespace
        assertThat(trivia.get(2).getClass().getSimpleName()).isEqualTo("Whitespace");
    }

    @Test
    void generatedParser_noTriviaWithoutWhitespace() throws Exception {
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- [ ]+
            """;

        var sourceResult = PegParser.generateCstParser(grammar, "test.empty", "EmptyTriviaParser");
        assertThat(sourceResult.isSuccess()).isTrue();

        var source = sourceResult.unwrap();
        var parserClass = compileAndLoad(source, "test.empty.EmptyTriviaParser");
        var parser = parserClass.getDeclaredConstructor().newInstance();
        var parseMethod = parserClass.getMethod("parse", String.class);

        @SuppressWarnings("unchecked")
        var result = (Result<Object>) parseMethod.invoke(parser, "42");
        assertThat(result.isSuccess()).isTrue();

        var node = result.unwrap();
        var leadingTriviaMethod = node.getClass().getMethod("leadingTrivia");
        @SuppressWarnings("unchecked")
        var trivia = (List<?>) leadingTriviaMethod.invoke(node);

        assertThat(trivia).isEmpty();
    }

    @Test
    void generatedParser_capturesTrailingTrivia() throws Exception {
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- ([ ]+)+
            """;

        var sourceResult = PegParser.generateCstParser(grammar, "test.trailing", "TrailingParser");
        assertThat(sourceResult.isSuccess()).isTrue();

        var source = sourceResult.unwrap();
        var parserClass = compileAndLoad(source, "test.trailing.TrailingParser");
        var parser = parserClass.getDeclaredConstructor().newInstance();
        var parseMethod = parserClass.getMethod("parse", String.class);

        @SuppressWarnings("unchecked")
        var result = (Result<Object>) parseMethod.invoke(parser, "42  ");
        assertThat(result.isSuccess()).isTrue();

        var node = result.unwrap();
        var trailingTriviaMethod = node.getClass().getMethod("trailingTrivia");
        @SuppressWarnings("unchecked")
        var trivia = (List<?>) trailingTriviaMethod.invoke(node);

        assertThat(trivia).hasSize(1);
        var firstTrivia = trivia.getFirst();
        var textMethod = firstTrivia.getClass().getMethod("text");
        assertThat(textMethod.invoke(firstTrivia)).isEqualTo("  ");
    }

    @Test
    void runtimeAndGenerated_triviaConsistent() throws Exception {
        // Verify runtime and generated parsers produce consistent trivia
        var grammar = """
            Number <- < [0-9]+ >
            %whitespace <- ([ \\t]+ / '//' [^\\n]* '\\n')+
            """;

        var input = "  // test\n  42";

        // Runtime parser trivia
        var runtimeParser = PegParser.fromGrammar(grammar).unwrap();
        var runtimeResult = runtimeParser.parseCst(input);
        assertThat(runtimeResult.isSuccess()).isTrue();
        var runtimeTrivia = runtimeResult.unwrap().leadingTrivia();

        // Generated parser trivia
        var sourceResult = PegParser.generateCstParser(grammar, "test.consistent", "ConsistentParser");
        assertThat(sourceResult.isSuccess()).isTrue();
        var parserClass = compileAndLoad(sourceResult.unwrap(), "test.consistent.ConsistentParser");
        var parser = parserClass.getDeclaredConstructor().newInstance();
        var parseMethod = parserClass.getMethod("parse", String.class);

        @SuppressWarnings("unchecked")
        var genResult = (Result<Object>) parseMethod.invoke(parser, input);
        assertThat(genResult.isSuccess()).isTrue();
        var genNode = genResult.unwrap();
        var leadingTriviaMethod = genNode.getClass().getMethod("leadingTrivia");
        @SuppressWarnings("unchecked")
        var genTrivia = (List<?>) leadingTriviaMethod.invoke(genNode);

        // Both should have same number of trivia items
        assertThat(genTrivia).hasSameSizeAs(runtimeTrivia);

        // Both should have same trivia types and text
        for (int i = 0; i < runtimeTrivia.size(); i++) {
            var rt = runtimeTrivia.get(i);
            var gt = genTrivia.get(i);

            var gtTextMethod = gt.getClass().getMethod("text");
            assertThat(gtTextMethod.invoke(gt)).isEqualTo(rt.text());

            // Compare types
            var rtType = rt.getClass().getSimpleName();
            var gtType = gt.getClass().getSimpleName();
            assertThat(gtType).isEqualTo(rtType);
        }
    }

    // Helper to compile and load generated parser
    private Class<?> compileAndLoad(String source, String className) throws Exception {
        var tempDir = Files.createTempDirectory("peglib-test");
        var packagePath = className.substring(0, className.lastIndexOf('.')).replace('.', '/');
        var simpleClassName = className.substring(className.lastIndexOf('.') + 1);

        var packageDir = tempDir.resolve(packagePath);
        Files.createDirectories(packageDir);

        var sourceFile = packageDir.resolve(simpleClassName + ".java");
        Files.writeString(sourceFile, source);

        // Compile
        var compiler = ToolProvider.getSystemJavaCompiler();
        var result = compiler.run(null, null, null,
            "-d", tempDir.toString(),
            "-cp", System.getProperty("java.class.path"),
            sourceFile.toString()
        );

        if (result != 0) {
            throw new RuntimeException("Compilation failed for " + className);
        }

        // Load
        var classLoader = new URLClassLoader(new URL[]{tempDir.toUri().toURL()});
        return classLoader.loadClass(className);
    }
}
