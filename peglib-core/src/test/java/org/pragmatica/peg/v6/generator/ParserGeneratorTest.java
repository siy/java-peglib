package org.pragmatica.peg.v6.generator;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.cst.ParseResult;
import org.pragmatica.peg.v6.lexer.DfaBuilder;
import org.pragmatica.peg.v6.lexer.LexerEngine;
import org.pragmatica.peg.v6.lexer.RuleClassifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParserGeneratorTest {

    private record Built(Grammar grammar,
                         RuleClassifier.Classification classification,
                         DfaBuilder.Built dfa,
                         LexerEngine engine) {}

    private static Built buildAll(String grammarText) {
        var grammar = GrammarParser.parse(grammarText).unwrap();
        var classification = RuleClassifier.classify(grammar).unwrap();
        var built = DfaBuilder.build(grammar, classification).unwrap();
        int wsKind = grammar.whitespace().isPresent() ? DfaBuilder.KIND_WHITESPACE : -1;
        var engine = new LexerEngine(built.dfa(), built.kinds().kindNameTable(), wsKind,
            built.kinds().keywordResolutions());
        return new Built(grammar, classification, built, engine);
    }

    private static ParserGenerator.GeneratedParser generate(Built b, String pkg, String cls) {
        return ParserGenerator.generate(b.grammar(), b.classification(), b.dfa().kinds(), pkg, cls)
            .unwrap();
    }

    private static ParserCompiler.CompiledParser compile(ParserGenerator.GeneratedParser g) {
        return ParserCompiler.compile(g).unwrap();
    }

    /** Walk children of a CstArray node and return their kind-names in order. */
    private static List<String> childKindNames(CstArray cst, int nodeIdx) {
        var out = new ArrayList<String>();
        for (var c = cst.firstChildAt(nodeIdx); c != CstArray.NO_NODE; c = cst.nextSiblingAt(c)) {
            out.add(cst.kindNameAt(c));
        }
        return out;
    }

    private static CstArray cstOf(ParseResult result) {
        assertThat(result.diagnostics()).as("expected no diagnostics, got %s", result.diagnostics()).isEmpty();
        return result.cst();
    }

    /**
     * Phase B.3.1: rootIndex now points at the synthetic "_ROOT" wrapper. The
     * "logical" start-rule node is its first child on a clean parse. Tests
     * that previously asserted on rootIndex continue to assert on the
     * start-rule node by going through this helper.
     */
    private static int startRuleNode(CstArray cst) {
        return cst.firstChildAt(cst.rootIndex());
    }

    @Test
    void simpleSequence_parsesAndBuildsCst() {
        // Sum references Number (LEXER) and uses '+' literal — Sum classifies as PARSER.
        var built = buildAll("""
            Sum <- Number '+' Number
            Number <- [0-9]+
            """);
        var generated = generate(built, "test.gen.parser.sum", "SumParser");
        var compiled = compile(generated);

        var tokens = built.engine().lex("3+5");
        var cst = cstOf(compiled.parse(tokens));

        int sum = startRuleNode(cst);
        assertThat(cst.kindNameAt(sum)).isEqualTo("Sum");
        // Number is LEXER so it doesn't get its own CST node — Sum is a leaf at parser level.
        assertThat(cst.firstChildAt(sum)).isEqualTo(CstArray.NO_NODE);
        // Span covers full input.
        assertThat(cst.textAt(sum).toString()).isEqualTo("3+5");
        // Synthetic root wraps everything.
        assertThat(cst.kindNameAt(cst.rootIndex())).isEqualTo("_ROOT");
    }

    @Test
    void parserRuleWithParserRuleChild_buildsBranch() {
        // Sum and Term both reference each other / Number. Term wraps Number and adds
        // a literal so Term itself stays PARSER; Sum dispatches into parseTerm twice.
        var built = buildAll("""
            Sum <- Term '+' Term
            Term <- '(' Number ')' / Number
            Number <- [0-9]+
            """);
        var generated = generate(built, "test.gen.parser.term", "TermParser");
        var compiled = compile(generated);

        var tokens = built.engine().lex("3+5");
        var cst = cstOf(compiled.parse(tokens));

        int sum = startRuleNode(cst);
        assertThat(cst.kindNameAt(sum)).isEqualTo("Sum");
        var children = childKindNames(cst, sum);
        assertThat(children).containsExactly("Term", "Term");
    }

    @Test
    void choice_pickFirstMatchingAlternative() {
        // Force a parser-rule with rule references and inline literals for each branch.
        var built = buildAll("""
            Word <- Foo / Bar / Baz
            Foo <- 'foo' Tail
            Bar <- 'bar' Tail
            Baz <- 'baz' Tail
            Tail <- '!'
            """);
        var generated = generate(built, "test.gen.parser.choice", "ChoiceParser");
        var compiled = compile(generated);

        for (var word : new String[]{"foo!", "bar!", "baz!"}) {
            var tokens = built.engine().lex(word);
            var cst = cstOf(compiled.parse(tokens));
            int wordNode = startRuleNode(cst);
            assertThat(cst.textAt(wordNode).toString()).isEqualTo(word);
            assertThat(cst.kindNameAt(wordNode)).isEqualTo("Word");
        }
    }

    @Test
    void oneOrMoreRepetition_parsesMultipleItems() {
        // List references Item (parser), so List is PARSER. Item references X (lexer)
        // and uses '!' literal so Item stays PARSER.
        var built = buildAll("""
            List <- Item Item Item
            Item <- X '!'
            X <- 'x'
            """);
        var generated = generate(built, "test.gen.parser.list3", "List3Parser");
        var compiled = compile(generated);

        var tokens = built.engine().lex("x!x!x!");
        var cst = cstOf(compiled.parse(tokens));

        int list = startRuleNode(cst);
        assertThat(cst.kindNameAt(list)).isEqualTo("List");
        var children = childKindNames(cst, list);
        assertThat(children).containsExactly("Item", "Item", "Item");
    }

    @Test
    void zeroOrMoreRepetition_acceptsEmptyAndMany() {
        var built = buildAll("""
            ListWrap <- Item*
            Item <- 'a' Tag
            Tag <- 'b'
            """);
        var generated = generate(built, "test.gen.parser.zom", "ZomParser");
        var compiled = compile(generated);

        var tokens = built.engine().lex("ababab");
        var cst = cstOf(compiled.parse(tokens));
        int wrap = startRuleNode(cst);
        assertThat(cst.kindNameAt(wrap)).isEqualTo("ListWrap");
        var children = childKindNames(cst, wrap);
        assertThat(children).containsExactly("Item", "Item", "Item");
    }

    @Test
    void optional_acceptsBothPresentAndMissing() {
        var built = buildAll("""
            Maybe <- Head Tail?
            Head <- 'a' Anchor
            Tail <- 'b'
            Anchor <- '#'
            """);
        var generated = generate(built, "test.gen.parser.opt", "OptParser");
        var compiled = compile(generated);

        var tokensA = built.engine().lex("a#");
        var cstA = cstOf(compiled.parse(tokensA));
        assertThat(cstA.textAt(cstA.rootIndex()).toString()).isEqualTo("a#");

        var tokensAb = built.engine().lex("a#b");
        var cstAb = cstOf(compiled.parse(tokensAb));
        assertThat(cstAb.textAt(cstAb.rootIndex()).toString()).isEqualTo("a#b");
    }

    @Test
    void unexpectedToken_recoversAndReportsDiagnostic() {
        var built = buildAll("""
            Pair <- Head 'b'
            Head <- 'a' '#'
            """);
        var generated = generate(built, "test.gen.parser.err", "ErrParser");
        var compiled = compile(generated);

        // 'a#x' fails at the third token of Pair — expects 'b' literal. Recovery
        // returns a ParseResult with at least one error diagnostic and a CST that
        // still has a valid root.
        var tokens = built.engine().lex("a#x");
        var result = compiled.parse(tokens);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.diagnostics()).isNotEmpty();
        assertThat(result.cst().nodeCount()).isGreaterThan(0);
    }

    @Test
    void java25Grammar_generatesAndCompiles() throws IOException {
        var grammarText = Files.readString(
            Paths.get("src/test/resources/java25.peg"),
            StandardCharsets.UTF_8);
        var built = buildAll(grammarText);
        var result = ParserGenerator.generate(built.grammar(), built.classification(),
            built.dfa().kinds(), "test.gen.parser.java25", "Java25Parser");

        assertThat(result.isSuccess())
            .as("java25 parser generation should succeed: %s", result)
            .isTrue();
        var generated = result.unwrap();

        // Diagnostics for the report.
        System.out.println("[ParserGenerator:java25] source bytes = " + generated.source().length()
            + ", parser rules = " + countParserRules(built.classification()));

        var compileResult = ParserCompiler.compile(generated);
        assertThat(compileResult.isSuccess())
            .as("java25 parser compilation should succeed: %s", compileResult)
            .isTrue();
    }

    private static int countParserRules(RuleClassifier.Classification c) {
        int n = 0;
        for (var k : c.kinds().values()) {
            if (k == org.pragmatica.peg.v6.lexer.RuleKind.PARSER
                || k == org.pragmatica.peg.v6.lexer.RuleKind.MIXED) {
                n++;
            }
        }
        return n;
    }

    @Test
    void packageNameMaybeEmpty() {
        var built = buildAll("""
            Sum <- Number '+' Number
            Number <- [0-9]+
            """);
        var generated = generate(built, "", "RootParser");
        assertThat(generated.source()).doesNotContain("package ");
        assertThat(generated.fullyQualifiedName()).isEqualTo("RootParser");
    }

    @Test
    void invalidClassName_isRejected() {
        var built = buildAll("""
            Sum <- Number '+' Number
            Number <- [0-9]+
            """);
        var result = ParserGenerator.generate(built.grammar(), built.classification(),
            built.dfa().kinds(), "p", "1bad");
        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void invalidPackage_isRejected() {
        var built = buildAll("""
            Sum <- Number '+' Number
            Number <- [0-9]+
            """);
        var result = ParserGenerator.generate(built.grammar(), built.classification(),
            built.dfa().kinds(), "1bad.x", "P");
        assertThat(result.isFailure()).isTrue();
    }
}
