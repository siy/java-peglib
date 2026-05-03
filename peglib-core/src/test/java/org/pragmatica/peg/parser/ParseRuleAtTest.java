package org.pragmatica.peg.parser;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.action.RuleId;
import org.pragmatica.peg.tree.CstNode;

import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link Parser#parseRuleAt(Class, String, int)} — the 0.3.0
 * partial-parse entry point used by the incremental reparsing module. Covers
 * the interpreter path (via {@code PegEngine}) and the generated-parser path
 * (via {@code ParserGenerator}) including cross-path parity.
 *
 * <p>See {@code docs/incremental/SPEC.md} §5.6 for the design rationale.
 */
class ParseRuleAtTest {

    private static final String NUMBER_GRAMMAR = """
                        Number <- < [0-9]+ >
                        """;

    private static final String NUMBER_WS_GRAMMAR = """
                        Number <- < [0-9]+ >
                        %whitespace <- [ \\t]*
                        """;

    // Marker for negative test — intentionally not part of NUMBER_GRAMMAR.
    record Bar() implements RuleId {}

    // Used for interpreter dispatch; matches rule name "Number" via simple name.
    record Number() implements RuleId {}

    @Nested
    class Interpreter {

        @Test
        void parseRuleAt_offsetZero_returnsEndOffsetAtMatchEnd() {
            var parser = PegParser.fromGrammar(NUMBER_GRAMMAR)
                                  .unwrap();

            var result = parser.parseRuleAt(Number.class, "42", 0);

            assertThat(result.isSuccess()).isTrue();
            var partial = result.unwrap();
            assertThat(partial.endOffset()).isEqualTo(2);
            assertThat(partial.node()).isNotNull();
            assertThat(partial.node()
                              .span()
                              .end()
                              .offset()).isEqualTo(2);
        }

        @Test
        void parseRuleAt_nonZeroOffset_returnsCorrectEndOffset() {
            var parser = PegParser.fromGrammar(NUMBER_WS_GRAMMAR)
                                  .unwrap();

            // Input: "  42  ", rule invoked at offset 2 should match "42" and stop at offset 4.
            var result = parser.parseRuleAt(Number.class, "  42  ", 2);

            assertThat(result.isSuccess()).isTrue();
            var partial = result.unwrap();
            assertThat(partial.endOffset()).isEqualTo(4);
            assertThat(partial.node()
                              .span()
                              .end()
                              .offset()).isEqualTo(4);
        }

        @Test
        void parseRuleAt_unknownRuleClass_returnsFailure() {
            var parser = PegParser.fromGrammar(NUMBER_GRAMMAR)
                                  .unwrap();

            // "Bar" is not a rule in NUMBER_GRAMMAR.
            var result = parser.parseRuleAt(Bar.class, "42", 0);

            assertThat(result.isFailure()).isTrue();
            assertThat(result.toString()).contains("Bar");
        }

        @Test
        void parseRuleAt_parseFailureAtOffset_returnsFailure() {
            var parser = PegParser.fromGrammar(NUMBER_GRAMMAR)
                                  .unwrap();

            // Input at offset 0 is 'a', not a digit — rule fails.
            var result = parser.parseRuleAt(Number.class, "abc", 0);

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void parseRuleAt_offsetOutOfRange_returnsFailure() {
            var parser = PegParser.fromGrammar(NUMBER_GRAMMAR)
                                  .unwrap();

            var negative = parser.parseRuleAt(Number.class, "42", -1);
            var tooLarge = parser.parseRuleAt(Number.class, "42", 10);

            assertThat(negative.isFailure()).isTrue();
            assertThat(tooLarge.isFailure()).isTrue();
        }

        @Test
        void parseRuleAt_endOffsetEqualsInputLength_whenRuleConsumesAll() {
            var parser = PegParser.fromGrammar(NUMBER_GRAMMAR)
                                  .unwrap();

            var result = parser.parseRuleAt(Number.class, "12345", 0);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap()
                             .endOffset()).isEqualTo(5);
        }

        @Test
        void parseRuleAt_nullRuleId_returnsFailure() {
            var parser = PegParser.fromGrammar(NUMBER_GRAMMAR)
                                  .unwrap();

            var result = parser.parseRuleAt(null, "42", 0);

            assertThat(result.isFailure()).isTrue();
        }
    }

    @Nested
    class PartialParseRecord {

        @Test
        void partialParse_accessorsExposeFields() {
            var parser = PegParser.fromGrammar(NUMBER_GRAMMAR)
                                  .unwrap();
            var partial = parser.parseRuleAt(Number.class, "42", 0)
                                .unwrap();

            assertThat(partial.node()).isInstanceOf(CstNode.class);
            assertThat(partial.endOffset()).isEqualTo(2);
        }

        @Test
        void partialParse_equalityAndHashCode() {
            CstNode node = new CstNode.Terminal(
            org.pragmatica.peg.tree.SourceSpan.sourceSpan(
            org.pragmatica.peg.tree.SourceLocation.sourceLocation(1, 1, 0),
            org.pragmatica.peg.tree.SourceLocation.sourceLocation(1, 3, 2)),
            "Number", "42", java.util.List.of(), java.util.List.of());

            var a = new PartialParse(node, 2);
            var b = new PartialParse(node, 2);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }
    }

    @Nested
    class GeneratorParity {

        @Test
        void generatedParser_parseRuleAt_matchesInterpreter() throws Exception {
            // Generate a CST parser for NUMBER_WS_GRAMMAR.
            var source = PegParser.generateCstParser(NUMBER_WS_GRAMMAR,
                                                     "test.partial",
                                                     "PartialParser")
                                  .unwrap();
            var parserClass = compileAndLoad(source, "test.partial.PartialParser");
            var parser = parserClass.getDeclaredConstructor()
                                    .newInstance();

            var ruleIdIfc = parserClass.getClasses();
            Class<?> numberRuleId = null;
            for (var c : ruleIdIfc) {
                if (c.getSimpleName()
                     .equals("RuleId")) {
                    for (var inner : c.getClasses()) {
                        if (inner.getSimpleName()
                                 .equals("Number")) {
                            numberRuleId = inner;
                            break;
                        }
                    }
                    break;
                }
            }
            assertThat(numberRuleId).isNotNull();
            var parseRuleAt = parserClass.getMethod("parseRuleAt", Class.class, String.class, int.class);

            // Offset 0
            var input1 = "42";
            var genResult1 = parseRuleAt.invoke(parser, numberRuleId, input1, 0);
            var isSuccess1 = (boolean) genResult1.getClass()
                                                 .getMethod("isSuccess")
                                                 .invoke(genResult1);
            assertThat(isSuccess1).isTrue();
            var partial1 = genResult1.getClass()
                                     .getMethod("unwrap")
                                     .invoke(genResult1);
            int endOffset1 = (int) partial1.getClass()
                                           .getMethod("endOffset")
                                           .invoke(partial1);

            var interpEnd1 = PegParser.fromGrammar(NUMBER_WS_GRAMMAR)
                                      .unwrap()
                                      .parseRuleAt(Number.class, input1, 0)
                                      .unwrap()
                                      .endOffset();
            assertThat(endOffset1).isEqualTo(interpEnd1);

            // Offset 2 in "  42  "
            var input2 = "  42  ";
            var genResult2 = parseRuleAt.invoke(parser, numberRuleId, input2, 2);
            var isSuccess2 = (boolean) genResult2.getClass()
                                                 .getMethod("isSuccess")
                                                 .invoke(genResult2);
            assertThat(isSuccess2).isTrue();
            var partial2 = genResult2.getClass()
                                     .getMethod("unwrap")
                                     .invoke(genResult2);
            int endOffset2 = (int) partial2.getClass()
                                           .getMethod("endOffset")
                                           .invoke(partial2);
            var interpEnd2 = PegParser.fromGrammar(NUMBER_WS_GRAMMAR)
                                      .unwrap()
                                      .parseRuleAt(Number.class, input2, 2)
                                      .unwrap()
                                      .endOffset();
            assertThat(endOffset2).isEqualTo(interpEnd2);
        }

        @Test
        void generatedParser_parseRuleAt_failureOnBadInput() throws Exception {
            var source = PegParser.generateCstParser(NUMBER_GRAMMAR,
                                                     "test.partial.fail",
                                                     "FailParser")
                                  .unwrap();
            var parserClass = compileAndLoad(source, "test.partial.fail.FailParser");
            var parser = parserClass.getDeclaredConstructor()
                                    .newInstance();

            Class<?> numberRuleId = null;
            for (var c : parserClass.getClasses()) {
                if (c.getSimpleName()
                     .equals("RuleId")) {
                    for (var inner : c.getClasses()) {
                        if (inner.getSimpleName()
                                 .equals("Number")) {
                            numberRuleId = inner;
                            break;
                        }
                    }
                }
            }
            assertThat(numberRuleId).isNotNull();
            var parseRuleAt = parserClass.getMethod("parseRuleAt", Class.class, String.class, int.class);

            // "abc" at offset 0 is not digits — must fail.
            var genResult = parseRuleAt.invoke(parser, numberRuleId, "abc", 0);
            var isFailure = (boolean) genResult.getClass()
                                               .getMethod("isFailure")
                                               .invoke(genResult);
            assertThat(isFailure).isTrue();
        }
    }

    private static Class<?> compileAndLoad(String source, String className) throws Exception {
        var tempDir = Files.createTempDirectory("peglib-parseRuleAt-test");
        var packagePath = className.substring(0, className.lastIndexOf('.'))
                                   .replace('.', '/');
        var simpleClassName = className.substring(className.lastIndexOf('.') + 1);

        var packageDir = tempDir.resolve(packagePath);
        Files.createDirectories(packageDir);
        var sourceFile = packageDir.resolve(simpleClassName + ".java");
        Files.writeString(sourceFile, source);

        var compiler = ToolProvider.getSystemJavaCompiler();
        var rc = compiler.run(null, null, null,
                              "-d", tempDir.toString(),
                              "-cp", System.getProperty("java.class.path"),
                              sourceFile.toString());
        if (rc != 0) {
            throw new RuntimeException("Compilation failed for " + className);
        }
        var classLoader = new URLClassLoader(new URL[]{tempDir.toUri()
                                                              .toURL()});
        return classLoader.loadClass(className);
    }
}
