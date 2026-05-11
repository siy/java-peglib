package org.pragmatica.peg.v6.generator;

import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.cst.ParseResult;
import org.pragmatica.peg.v6.lexer.DfaBuilder;
import org.pragmatica.peg.v6.lexer.LexerEngine;
import org.pragmatica.peg.v6.lexer.RuleClassifier;
import org.pragmatica.peg.v6.token.TokenArray;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Phase B.6.2 bisection harness — quickly probe small Java inputs against
 * the same parser pipeline used by Java25ParserGateTest. This is a debugging
 * test; it always passes (logs diagnostics to stdout).
 */
class Java25BisectTest {
    private static LexerEngine lexer;
    private static ParserCompiler.CompiledParser parser;

    @BeforeAll
    static void setup() throws IOException {
        var grammarText = Files.readString(
        Paths.get("src/test/resources/java25.peg"), StandardCharsets.UTF_8);
        Grammar grammar = GrammarParser.parse(grammarText)
                                       .unwrap();
        var classification = RuleClassifier.classify(grammar)
                                           .unwrap();
        var built = DfaBuilder.build(grammar, classification)
                              .unwrap();
        int wsKind = grammar.whitespace()
                            .isPresent()
                     ? DfaBuilder.KIND_WHITESPACE
                     : - 1;
        lexer = new LexerEngine(built.dfa(),
                                built.kinds()
                                     .kindNameTable(),
                                wsKind,
                                built.kinds()
                                     .keywordResolutions());
        var generated = ParserGenerator.generate(grammar,
                                                 classification,
                                                 built.kinds(),
                                                 "test.gen.parser.java25.bisect",
                                                 "Java25BisectParser")
                                       .unwrap();
        parser = ParserCompiler.compile(generated)
                               .unwrap();
    }

    private static void probe(String label, String input) {
        TokenArray tokens;
        try{
            tokens = lexer.lex(input);
        } catch (RuntimeException e) {
            System.out.println("[" + label + "] LEX FAIL: " + e.getMessage());
            return;
        }
        ParseResult result;
        try{
            result = parser.parse(tokens);
        } catch (RuntimeException e) {
            System.out.println("[" + label + "] PARSE EXC: " + e.getMessage());
            return;
        }
        CstArray cst = result.cst();
        boolean rt = cst.reconstruct()
                        .equals(input);
        int diag = result.diagnostics()
                         .size();
        System.out.println("[" + label + "] tok=" + tokens.count() + " nodes=" + cst.nodeCount() + " diag=" + diag
                           + " rt=" + rt + (diag > 0
                                            ? " first=" + result.diagnostics()
                                                               .get(0)
                                            : ""));
    }

    @Test
    void bisectGenerics() {
        // Basic class without generics
        probe("plain class", "class K {}");
        probe("class with field no generics", "class K { String x; }");
        probe("class with field generic 1 arg", "class K { Result<String> x; }");
        probe("class with field generic 2 args", "class K { Function<String, String> x; }");
        probe("class with field nested generic", "class K { List<Result<String>> x; }");
        // Class header generics
        probe("class<T>", "class K<T> {}");
        probe("class<T extends X>", "class K<T extends X> {}");
        probe("class<T extends X<Y>>", "class K<T extends X<Y>> {}");
        probe("class<T extends X & Y>", "class K<T extends X & Y> {}");
        probe("class<T extends Comparable<? super T>>", "class K<T extends Comparable<? super T>> {}");
        probe("class<T extends Comparable<? super T> & Cloneable>",
              "class K<T extends Comparable<? super T> & Cloneable> {}");
        // Triple-slash JavaDoc
        probe("triple-slash before class", "/// doc\nclass K {}");
        probe("triple-slash before public interface", "/// Test class literals.\npublic interface I {}");
        // public class with body
        probe("public class with method", "public class K { void m() {} }");
        // imports + class
        probe("imports + class+generics", "import java.util.List;\n\nclass K { List<String> x; }");
    }

    @Test
    void bisectAnnotations() {
        probe("class with @SuppressWarnings on field", "class K { @SuppressWarnings(\"unused\") String x; }");
        probe("class with @SuppressWarnings on inner class",
              "class K { @SuppressWarnings(\"unused\") static class I {} }");
        probe("class with @SuppressWarnings on method", "class K { @SuppressWarnings(\"unused\") void m() {} }");
        // The first failing offset 189 in Annotations.java is at 'public class Annotations'
        probe("imports + public class",
              "package format.examples;\n\n" + "import java.lang.annotation.ElementType;\n"
              + "import java.lang.annotation.Retention;\n" + "import java.lang.annotation.RetentionPolicy;\n"
              + "import java.lang.annotation.Target;\n\n\n" + "public class Annotations {}");
        // Stripped Annotations.java - just the imports and class header
        probe("annotations-prefix",
              "package format.examples;\n\n" + "import java.lang.annotation.ElementType;\n"
              + "public class Annotations {\n" + "    @SuppressWarnings(\"unused\") static class SingleAnnotation {}\n"
              + "}");
        // Regression: annotations on local var with `var` type inference (LocalVar
        // requires Annotation* prefix because LocalVarType's first alt matches the
        // `var` token directly, bypassing Type's Annotation* absorber).
        probe("annotated local var", "class K { void m() { @SuppressWarnings(\"unused\") var local = \"v\"; } }");
    }
}
