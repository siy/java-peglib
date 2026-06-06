package org.pragmatica.peg.v6.generator;

import org.pragmatica.peg.v6.PegParser;
import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.cst.ParseResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.6.2 — regression coverage for the shift-operator inline-expansion fix.
 *
 * <p>The Java25 grammar splits shift operators into single-character literals so
 * that nested generics ({@code List<List<String>>}) still parse:
 * {@code LShift <- '<' '<' !'='}, {@code RShift <- '>' '>' !'>' !'='},
 * {@code URShift <- '>' '>' '>' !'='}. These rules classify as LEXER but the DFA
 * builder cannot compile their negative lookahead, so they are expanded inline by
 * the parser generator as adjacent multi-token matches against the constituent
 * {@code <}/{@code >}/{@code =} inline-literal kinds.
 *
 * <p>Before the fix every {@code <<}/{@code >>}/{@code >>>} in field- or
 * local-variable-initializer context produced a cascade of "trailing input not
 * consumed" diagnostics; nested generics and relational comparisons must keep
 * parsing cleanly after the fix.
 */
class ShiftOperatorInlineExpansionTest {
    private static final Path GRAMMAR_PATH = Paths.get("src/test/resources/java25.peg");

    private static PegParserWrapper parser;

    /** Thin holder so the heavy generate+compile happens exactly once. */
    private record PegParserWrapper(org.pragmatica.peg.v6.Parser delegate) {
        ParseResult parse(String input) {
            return delegate.parse(input, 200);
        }
    }

    @BeforeAll
    static void setupOnce() throws IOException {
        var grammarText = Files.readString(GRAMMAR_PATH, StandardCharsets.UTF_8);
        parser = new PegParserWrapper(PegParser.fromGrammar(grammarText).unwrap());
    }

    private static int countShiftNodesWithOperator(CstArray cst) {
        // A Shift node that actually applied a shift operator has more than one
        // Additive child (Shift <- Additive ((URShift / LShift / RShift) Additive)*).
        int withOp = 0;
        for (int i = 0; i < cst.nodeCount(); i++) {
            if (!"Shift".equals(cst.kindNameAt(i))) {
                continue;
            }
            int additives = 0;
            for (int c = cst.firstChildAt(i); c >= 0; c = cst.nextSiblingAt(c)) {
                if ("Additive".equals(cst.kindNameAt(c))) {
                    additives++;
                }
            }
            if (additives > 1) {
                withOp++;
            }
        }
        return withOp;
    }

    private static void assertParsesCleanly(String source, int minNodes) {
        var result = parser.parse(source);
        assertThat(result.diagnostics())
            .as("diagnostics for: %s", source)
            .isEmpty();
        assertThat(result.cst().nodeCount())
            .as("CST node count for: %s (guards against empty-match bailout)", source)
            .isGreaterThanOrEqualTo(minNodes);
    }

    @Nested
    class ShiftInExpressions {
        @Test
        void leftShift_parsesCleanly_inFieldInitializer() {
            assertParsesCleanly("class A { int x = 1 << 2; }", 20);
            assertThat(countShiftNodesWithOperator(parser.parse("class A { int x = 1 << 2; }").cst()))
                .as("<< should be recognised as a shift operator")
                .isEqualTo(1);
        }

        @Test
        void rightShift_parsesCleanly_inFieldInitializer() {
            assertParsesCleanly("class A { int x = 1 >> 2; }", 20);
            assertThat(countShiftNodesWithOperator(parser.parse("class A { int x = 1 >> 2; }").cst()))
                .isEqualTo(1);
        }

        @Test
        void unsignedRightShift_parsesCleanly_inFieldInitializer() {
            assertParsesCleanly("class A { int x = 1 >>> 2; }", 20);
            assertThat(countShiftNodesWithOperator(parser.parse("class A { int x = 1 >>> 2; }").cst()))
                .isEqualTo(1);
        }

        @Test
        void leftShift_parsesCleanly_inLocalVariableInitializer() {
            assertParsesCleanly("class A { void m() { int x = 1 << 2; } }", 25);
        }

        @Test
        void rightShift_parsesCleanly_inLocalVariableInitializer() {
            assertParsesCleanly("class A { void m() { int x = 1 >> 2; } }", 25);
        }

        @Test
        void unsignedRightShift_parsesCleanly_inLocalVariableInitializer() {
            assertParsesCleanly("class A { void m() { int x = 1 >>> 2; } }", 25);
        }

        @Test
        void leftShift_parsesCleanly_whenParenthesized() {
            assertParsesCleanly("class A { int x = (1 << 2); }", 25);
        }

        @Test
        void chainedShift_parsesCleanly() {
            assertParsesCleanly("class A { int x = 1 << 2 >> 3; }", 25);
            assertThat(countShiftNodesWithOperator(parser.parse("class A { int x = 1 << 2 >> 3; }").cst()))
                .isEqualTo(1);
        }
    }

    @Nested
    class ShiftAssignment {
        @Test
        void leftShiftAssign_parsesCleanly() {
            assertParsesCleanly("class A { void m() { x <<= 2; } }", 25);
        }

        @Test
        void rightShiftAssign_parsesCleanly() {
            assertParsesCleanly("class A { void m() { x >>= 2; } }", 25);
        }

        @Test
        void unsignedRightShiftAssign_parsesCleanly() {
            assertParsesCleanly("class A { void m() { x >>>= 2; } }", 25);
        }
    }

    @Nested
    class GenericsStillWork {
        @Test
        void nestedGenerics_parseWithZeroDiagnostics() {
            assertParsesCleanly("class A { List<List<String>> x; }", 15);
            assertThat(countShiftNodesWithOperator(parser.parse("class A { List<List<String>> x; }").cst()))
                .as(">> closing nested generics must NOT be parsed as a shift operator")
                .isZero();
        }

        @Test
        void parameterizedMap_parsesWithZeroDiagnostics() {
            assertParsesCleanly("class A { java.util.Map<String, List<Integer>> x; }", 20);
            assertThat(countShiftNodesWithOperator(parser.parse("class A { java.util.Map<String, List<Integer>> x; }").cst()))
                .isZero();
        }

        @Test
        void relationalComparison_parsesWithZeroDiagnostics() {
            assertParsesCleanly("class A { boolean b = a < b; }", 15);
            assertThat(countShiftNodesWithOperator(parser.parse("class A { boolean b = a < b; }").cst()))
                .as("a single '<' is relational, not a shift")
                .isZero();
        }
    }

    @Nested
    class Adjacency {
        @Test
        void spacedLessThans_doNotFuseIntoLeftShift() {
            // `1 < < 2` has whitespace between the two '<' tokens, so the inline
            // expansion's adjacency check must refuse to treat it as `<<`. Java
            // itself rejects this, so the parser should NOT see a shift operator.
            var result = parser.parse("class A { int x = 1 < < 2; }");
            assertThat(countShiftNodesWithOperator(result.cst()))
                .as("`< <` (spaced) must not parse as a `<<` shift")
                .isZero();
        }
    }

    @Nested
    class LoudGuard {
        @Test
        void fromGrammar_fails_whenParserRuleReferencesSkippedLexerRuleWithoutExpansion() {
            // Tok is LEXER (literals only) with a multi-char negative lookahead,
            // which the DFA cannot compile and which does not fit the single-char
            // `literal+ !literal` inline-expansion shape. Start (PARSER) references
            // it, so the generator must reject rather than emit a dead-kind match.
            var grammar = """
                Start <- Tok ';'
                Tok <- 'ab' !'cd'
                """;
            org.pragmatica.peg.v6.PegParser.fromGrammar(grammar)
                .onSuccess(__ -> org.junit.jupiter.api.Assertions.fail("expected SkippedRuleReferenced failure"))
                .onFailure(cause -> assertThat(cause.message())
                    .contains("Tok")
                    .contains("skipped"));
        }
    }
}

