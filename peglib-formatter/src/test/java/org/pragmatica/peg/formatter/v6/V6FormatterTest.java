package org.pragmatica.peg.formatter.v6;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.formatter.Doc;
import org.pragmatica.peg.formatter.Docs;
import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.cst.CstArrayBuilder;
import org.pragmatica.peg.v6.token.TokenArray;
import org.pragmatica.peg.v6.token.TokenArrayBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pragmatica.peg.formatter.Docs.concat;
import static org.pragmatica.peg.formatter.Docs.group;
import static org.pragmatica.peg.formatter.Docs.indent;
import static org.pragmatica.peg.formatter.Docs.line;
import static org.pragmatica.peg.formatter.Docs.text;

/**
 * Unit tests for {@link V6Formatter} — the parallel v6 walker over
 * {@link CstArray}. CSTs are built directly through {@link CstArrayBuilder} so
 * the tests have no dependency on a specific grammar pipeline.
 */
final class V6FormatterTest {

    private static final int KIND_IDENT = TokenArray.FIRST_USER_KIND;
    private static final int KIND_PLUS = TokenArray.FIRST_USER_KIND + 1;
    private static final int KIND_LBRACE = TokenArray.FIRST_USER_KIND + 2;
    private static final int KIND_RBRACE = TokenArray.FIRST_USER_KIND + 3;
    private static final int KIND_SEMI = TokenArray.FIRST_USER_KIND + 4;

    private static final String[] TOKEN_KIND_TABLE = {
        "WHITESPACE", "LINE_COMMENT", "BLOCK_COMMENT",
        "IDENT", "PLUS", "LBRACE", "RBRACE", "SEMI"
    };

    private static final String[] RULE_TABLE = {
        "Expr", "Sum", "Ident", "Block", "Statement"
    };

    private static final int RULE_EXPR = 0;
    private static final int RULE_SUM = 1;
    private static final int RULE_IDENT = 2;
    private static final int RULE_BLOCK = 3;
    private static final int RULE_STATEMENT = 4;

    @Nested
    @DisplayName("Default fallback")
    class DefaultFallback {

        @Test
        void singleLeaf_emitsSourceText() {
            var input = "foo";
            var tokens = new TokenArrayBuilder(input);
            tokens.append(KIND_IDENT, 0, 3);
            var tokenArray = tokens.build(TOKEN_KIND_TABLE);

            var builder = new CstArrayBuilder(input, tokenArray, RULE_TABLE);
            var root = builder.beginNode(RULE_IDENT, 0, CstArray.NO_NODE);
            builder.endNode(root, 0);
            var cst = builder.build(root);

            var formatter = V6Formatter.formatter(V6FormatterConfig.defaultConfig());
            var out = formatter.format(cst).unwrap();
            assertThat(out).isEqualTo("foo");
        }

        @Test
        void branchWithLeafChildren_concatenatesChildText() {
            var cst = buildSumA_Plus_B();
            var formatter = V6Formatter.formatter(V6FormatterConfig.defaultConfig());
            var out = formatter.format(cst).unwrap();
            assertThat(out).isEqualTo("a+b");
        }

        @Test
        void emptyCst_returnsEmptyString() {
            var input = "";
            var tokens = new TokenArrayBuilder(input).build(TOKEN_KIND_TABLE);
            var builder = new CstArrayBuilder(input, tokens, RULE_TABLE);
            var cst = builder.build(CstArray.NO_NODE);

            var formatter = V6Formatter.formatter(V6FormatterConfig.defaultConfig());
            assertThat(formatter.format(cst).unwrap()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Custom rules")
    class CustomRules {

        @Test
        void ruleReceivesChildDocs_andCanInsertSpaces() {
            var cst = buildSumA_Plus_B();
            V6FormatterRule sumRule = (ctx, kids) -> {
                // children are: Ident("a"), Terminal("+"), Ident("b")
                return concat(kids.get(0), text(" "), kids.get(1), text(" "), kids.get(2));
            };
            var config = V6FormatterConfig.builder().rule("Sum", sumRule).build();
            var formatter = V6Formatter.formatter(config);
            assertThat(formatter.format(cst).unwrap()).isEqualTo("a + b");
        }

        @Test
        void ruleCanAccessNodeText() {
            var cst = buildSumA_Plus_B();
            V6FormatterRule sumRule = (ctx, kids) -> text(ctx.nodeText().toString().toUpperCase());
            var config = V6FormatterConfig.builder().rule("Sum", sumRule).build();
            var formatter = V6Formatter.formatter(config);
            assertThat(formatter.format(cst).unwrap()).isEqualTo("A+B");
        }

        @Test
        void ruleFailure_returnsFailureResult() {
            var cst = buildSumA_Plus_B();
            V6FormatterRule throwingRule = (ctx, kids) -> {
                throw new RuntimeException("boom");
            };
            var config = V6FormatterConfig.builder().rule("Sum", throwingRule).build();
            var formatter = V6Formatter.formatter(config);
            var result = formatter.format(cst);
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void nullCst_returnsFailure() {
            var formatter = V6Formatter.formatter(V6FormatterConfig.defaultConfig());
            assertThat(formatter.format(null).isFailure()).isTrue();
        }
    }

    @Nested
    @DisplayName("Multi-line block formatting via Doc combinators")
    class MultiLineBlocks {

        @Test
        void blockBreaksWhenWide() {
            var cst = buildBlockWithStatements();
            V6FormatterRule blockRule = (ctx, kids) -> {
                // kids = [{, statement, statement, }]
                var openBrace = kids.get(0);
                var closeBrace = kids.get(kids.size() - 1);
                var stmts = new java.util.ArrayList<Doc>();
                for (var i = 1; i < kids.size() - 1; i++) {
                    if (i > 1) {
                        stmts.add(line());
                    }
                    stmts.add(kids.get(i));
                }
                return group(openBrace,
                    indent(ctx.defaultIndent(), concat(line(), Docs.concat(stmts))),
                    line(),
                    closeBrace);
            };
            V6FormatterRule stmtRule = (ctx, kids) -> concat(kids);
            var config = V6FormatterConfig.builder()
                .defaultIndent(2)
                .maxLineWidth(10)
                .rule("Block", blockRule)
                .rule("Statement", stmtRule)
                .build();
            var formatter = V6Formatter.formatter(config);
            var out = formatter.format(cst).unwrap();
            // Expected:
            // {
            //   foo;
            //   bar;
            // }
            assertThat(out).isEqualTo("{\n  foo;\n  bar;\n}");
        }

        @Test
        void blockStaysFlatWhenNarrow() {
            var cst = buildBlockWithStatements();
            V6FormatterRule blockRule = (ctx, kids) -> {
                var stmts = new java.util.ArrayList<Doc>();
                for (var i = 1; i < kids.size() - 1; i++) {
                    if (i > 1) {
                        stmts.add(text(" "));
                    }
                    stmts.add(kids.get(i));
                }
                return group(kids.get(0),
                    text(" "),
                    Docs.concat(stmts),
                    text(" "),
                    kids.get(kids.size() - 1));
            };
            V6FormatterRule stmtRule = (ctx, kids) -> concat(kids);
            var config = V6FormatterConfig.builder()
                .defaultIndent(2)
                .maxLineWidth(80)
                .rule("Block", blockRule)
                .rule("Statement", stmtRule)
                .build();
            var formatter = V6Formatter.formatter(config);
            var out = formatter.format(cst).unwrap();
            assertThat(out).isEqualTo("{ foo; bar; }");
        }
    }

    @Nested
    @DisplayName("Trivia handling")
    class TriviaHandling {

        @Test
        void preservePolicy_emitsLeadingWhitespace() {
            var cst = buildIdentWithLeadingWhitespace();
            var formatter = V6Formatter.formatter(
                V6FormatterConfig.builder().triviaPolicy(V6TriviaPolicy.PRESERVE).build());
            var out = formatter.format(cst).unwrap();
            assertThat(out).isEqualTo("  foo");
        }

        @Test
        void dropAllPolicy_removesTrivia() {
            var cst = buildIdentWithLeadingWhitespace();
            var formatter = V6Formatter.formatter(
                V6FormatterConfig.builder().triviaPolicy(V6TriviaPolicy.DROP_ALL).build());
            var out = formatter.format(cst).unwrap();
            assertThat(out).isEqualTo("foo");
        }

        @Test
        void preservePolicy_emitsLineComment_followedByHardBreak() {
            // Input: "// hi\nfoo"
            // tokens: [line-comment "// hi\n"][ident "foo"]
            var input = "// hi\nfoo";
            var tokens = new TokenArrayBuilder(input);
            tokens.append(TokenArray.KIND_LINE_COMMENT, 0, 6); // includes trailing \n
            tokens.append(KIND_IDENT, 6, 9);
            var tokenArray = tokens.build(TOKEN_KIND_TABLE);

            var builder = new CstArrayBuilder(input, tokenArray, RULE_TABLE);
            var root = builder.beginNode(RULE_IDENT, 1, CstArray.NO_NODE);
            builder.endNode(root, 1);
            var cst = builder.build(root);

            var formatter = V6Formatter.formatter(
                V6FormatterConfig.builder().triviaPolicy(V6TriviaPolicy.PRESERVE).build());
            var out = formatter.format(cst).unwrap();
            assertThat(out).isEqualTo("// hi\nfoo");
        }

        @Test
        void stripWhitespacePolicy_keepsCommentsDropsSpaces() {
            // Input: " // hi\n foo"
            var input = " // hi\n foo";
            var tokens = new TokenArrayBuilder(input);
            tokens.append(TokenArray.KIND_WHITESPACE, 0, 1);
            tokens.append(TokenArray.KIND_LINE_COMMENT, 1, 7);
            tokens.append(TokenArray.KIND_WHITESPACE, 7, 8);
            tokens.append(KIND_IDENT, 8, 11);
            var tokenArray = tokens.build(TOKEN_KIND_TABLE);

            var builder = new CstArrayBuilder(input, tokenArray, RULE_TABLE);
            var root = builder.beginNode(RULE_IDENT, 3, CstArray.NO_NODE);
            builder.endNode(root, 3);
            var cst = builder.build(root);

            var formatter = V6Formatter.formatter(
                V6FormatterConfig.builder().triviaPolicy(V6TriviaPolicy.STRIP_WHITESPACE).build());
            var out = formatter.format(cst).unwrap();
            // Whitespace runs are removed; line-comment is preserved with hard break.
            assertThat(out).isEqualTo("// hi\nfoo");
        }

        @Test
        void normalizeBlankLines_collapsesMultipleNewlines() {
            // "\n\n\n\nfoo" — four newlines collapse to a single blank line ("\n\n").
            var input = "\n\n\n\nfoo";
            var tokens = new TokenArrayBuilder(input);
            tokens.append(TokenArray.KIND_WHITESPACE, 0, 4);
            tokens.append(KIND_IDENT, 4, 7);
            var tokenArray = tokens.build(TOKEN_KIND_TABLE);

            var builder = new CstArrayBuilder(input, tokenArray, RULE_TABLE);
            var root = builder.beginNode(RULE_IDENT, 1, CstArray.NO_NODE);
            builder.endNode(root, 1);
            var cst = builder.build(root);

            var formatter = V6Formatter.formatter(
                V6FormatterConfig.builder().triviaPolicy(V6TriviaPolicy.NORMALIZE_BLANK_LINES).build());
            var out = formatter.format(cst).unwrap();
            assertThat(out).isEqualTo("\n\nfoo");
        }
    }

    @Nested
    @DisplayName("Config validation")
    class ConfigValidation {

        @Test
        void rejectsNullConfig() {
            assertThatThrownBy(() -> V6Formatter.formatter(null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void builderRejectsNegativeIndent() {
            assertThatThrownBy(() -> V6FormatterConfig.builder().defaultIndent(-1))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void builderRejectsNonPositiveWidth() {
            assertThatThrownBy(() -> V6FormatterConfig.builder().maxLineWidth(0))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void builderRejectsNullTriviaPolicy() {
            assertThatThrownBy(() -> V6FormatterConfig.builder().triviaPolicy(null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void builderRejectsEmptyRuleName() {
            assertThatThrownBy(() -> V6FormatterConfig.builder().rule("", (ctx, kids) -> Docs.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void contextRejectsOutOfBoundsNodeIdx() {
            var input = "x";
            var tokens = new TokenArrayBuilder(input);
            tokens.append(KIND_IDENT, 0, 1);
            var tokenArray = tokens.build(TOKEN_KIND_TABLE);
            var builder = new CstArrayBuilder(input, tokenArray, RULE_TABLE);
            var root = builder.beginNode(RULE_IDENT, 0, CstArray.NO_NODE);
            builder.endNode(root, 0);
            var cst = builder.build(root);

            assertThatThrownBy(() -> new V6FormatContext(cst, 99, 2, 80, V6TriviaPolicy.PRESERVE))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("View access")
    class ViewAccess {

        @Test
        void contextViewExposesSealedNode() {
            var cst = buildSumA_Plus_B();
            var ctx = new V6FormatContext(cst, cst.rootIndex(), 2, 80, V6TriviaPolicy.PRESERVE);
            assertThat(ctx.view()).isInstanceOf(org.pragmatica.peg.v6.cst.CstNode.Branch.class);
            assertThat(ctx.ruleName()).isEqualTo("Sum");
        }
    }

    // ---------- helpers --------------------------------------------------

    /** Build CST for "a+b": Sum [ Ident("a"), Terminal("+"), Ident("b") ]. */
    private static CstArray buildSumA_Plus_B() {
        var input = "a+b";
        var tokens = new TokenArrayBuilder(input);
        tokens.append(KIND_IDENT, 0, 1);  // 'a'
        tokens.append(KIND_PLUS, 1, 2);   // '+'
        tokens.append(KIND_IDENT, 2, 3);  // 'b'
        var tokenArray = tokens.build(TOKEN_KIND_TABLE);

        var builder = new CstArrayBuilder(input, tokenArray, RULE_TABLE);
        var sum = builder.beginNode(RULE_SUM, 0, CstArray.NO_NODE);
        var identA = builder.beginNode(RULE_IDENT, 0, sum);
        builder.endNode(identA, 0);
        // '+' as a leaf with rule kind Expr (any non-trivial kind suffices)
        var plus = builder.beginNode(RULE_EXPR, 1, sum);
        builder.endNode(plus, 1);
        var identB = builder.beginNode(RULE_IDENT, 2, sum);
        builder.endNode(identB, 2);
        builder.endNode(sum, 2);
        return builder.build(sum);
    }

    /** Build CST for "{foo;bar;}": Block [ '{', Statement[Ident,';'], Statement[Ident,';'], '}' ]. */
    private static CstArray buildBlockWithStatements() {
        var input = "{foo;bar;}";
        var tokens = new TokenArrayBuilder(input);
        tokens.append(KIND_LBRACE, 0, 1);
        tokens.append(KIND_IDENT, 1, 4);   // 'foo'
        tokens.append(KIND_SEMI, 4, 5);    // ';'
        tokens.append(KIND_IDENT, 5, 8);   // 'bar'
        tokens.append(KIND_SEMI, 8, 9);    // ';'
        tokens.append(KIND_RBRACE, 9, 10);
        var tokenArray = tokens.build(TOKEN_KIND_TABLE);

        var builder = new CstArrayBuilder(input, tokenArray, RULE_TABLE);
        var block = builder.beginNode(RULE_BLOCK, 0, CstArray.NO_NODE);

        var open = builder.beginNode(RULE_EXPR, 0, block);
        builder.endNode(open, 0);

        var stmt1 = builder.beginNode(RULE_STATEMENT, 1, block);
        var id1 = builder.beginNode(RULE_IDENT, 1, stmt1);
        builder.endNode(id1, 1);
        var semi1 = builder.beginNode(RULE_EXPR, 2, stmt1);
        builder.endNode(semi1, 2);
        builder.endNode(stmt1, 2);

        var stmt2 = builder.beginNode(RULE_STATEMENT, 3, block);
        var id2 = builder.beginNode(RULE_IDENT, 3, stmt2);
        builder.endNode(id2, 3);
        var semi2 = builder.beginNode(RULE_EXPR, 4, stmt2);
        builder.endNode(semi2, 4);
        builder.endNode(stmt2, 4);

        var close = builder.beginNode(RULE_EXPR, 5, block);
        builder.endNode(close, 5);

        builder.endNode(block, 5);
        return builder.build(block);
    }

    /** Build CST for "  foo": Ident leaf preceded by two whitespace tokens. */
    private static CstArray buildIdentWithLeadingWhitespace() {
        var input = "  foo";
        var tokens = new TokenArrayBuilder(input);
        tokens.append(TokenArray.KIND_WHITESPACE, 0, 1);
        tokens.append(TokenArray.KIND_WHITESPACE, 1, 2);
        tokens.append(KIND_IDENT, 2, 5);
        var tokenArray = tokens.build(TOKEN_KIND_TABLE);

        var builder = new CstArrayBuilder(input, tokenArray, RULE_TABLE);
        var root = builder.beginNode(RULE_IDENT, 2, CstArray.NO_NODE);
        builder.endNode(root, 2);
        return builder.build(root);
    }
}
