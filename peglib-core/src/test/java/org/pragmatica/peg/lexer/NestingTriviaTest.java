package org.pragmatica.peg.lexer;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.cst.CstArray;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.token.TokenArray;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.7.3 — {@code %nest '<open>' '<close>'}, nested block comments (issue #45).
 *
 * <p>These run through {@link PegParser#fromGrammar}, so they exercise the GENERATED lexer —
 * the one {@code Parser.parse} actually runs — rather than the interpreted {@link LexerEngine}
 * most lexer tests construct directly. That distinction is load-bearing here: the two paths are
 * separate implementations of the same algorithm, and a fix applied to only one of them would
 * leave this suite green while shipping the bug. {@code NestingLexerParityTest} pins them
 * together; this file pins the behaviour a user sees.
 *
 * <h2>What these assert, and why it is token counts</h2>
 *
 * <p>The tempting assertion is "the parse reports a diagnostic". It is the wrong one, and this
 * project has a recorded history of writing it. The defect in #45 is not that nested comments
 * fail to parse — it is that the lexer closes the comment at the INNER {@code *&#47;} and leaks
 * the remainder into the token stream as live source. Sometimes that trips the parser; the two
 * cases below marked "silent" show it composing into a valid program instead, so the parse
 * succeeds and means something other than what the source says. A diagnostic-based assertion
 * passes on those. Counting the content tokens the parse actually produced does not.
 */
class NestingTriviaTest {
    /**
     * Deliberately minimal: content is one repeated token kind, so "what did the lexer think
     * was source?" reduces to a word count and no grammar detail can mask the answer.
     */
    private static final String BASE = """
        Program      <- Word+
        Word         <- < [a-zA-Z0-9_]+ >
        BlockComment <- '/*' (!'*/' .)* '*/'
        %whitespace  <- ([ \\t\\r\\n] / BlockComment)*
        """;

    private static final String NESTING = BASE + "%nest '/*' '*/'\n";

    /**
     * The content tokens a parse produced, in order.
     *
     * <p>Reads the TOKEN stream rather than the CST. In the tokens-first architecture a LEXER
     * rule never becomes a CST node — {@code Word} is lexical, so the tree here is just
     * {@code _ROOT / Program} and counting nodes would answer a different question. The token
     * stream is also the more direct assertion: the defect is the lexer mistaking comment text
     * for source, and this is literally the list of things it decided were source.
     */
    private static List<String> words(String grammar, String input) {
        var parser = PegParser.fromGrammar(grammar)
                              .unwrap();
        var tokens = parser.parse(input)
                           .cst()
                           .tokens();
        var found = new ArrayList<String>();

        for (int i = 0; i < tokens.count(); i++) {
            if (!tokens.isTrivia(i)) {
                found.add(tokens.textAt(i)
                                .toString());
            }
        }

        return found;
    }

    @Test
    void controlPlainCommentIsUnaffected() {
        // The reading that already worked must keep working — a nesting scanner that swallowed
        // ordinary comments differently would be a regression dressed as a fix.
        assertThat(words(NESTING, "a /* plain */ b")).containsExactly("a", "b");
    }

    @Test
    void nestedCommentIsOneComment() {
        assertThat(words(NESTING, "a /* outer /* inner */ still a comment */ b")).containsExactly("a", "b");
    }

    @Test
    void withoutTheDirectiveTheInnerCloseStillEndsTheComment() {
        // The baseline the directive exists to change, asserted rather than assumed. If
        // classification ever handled nesting without %nest, this test should be revisited
        // rather than deleted — but silently passing would mean the directive is untested.
        assertThat(words(BASE, "a /* outer /* inner */ still a comment */ b")).contains("still", "a", "comment");
    }

    @Test
    void deeplyNestedCommentIsOneComment() {
        assertThat(words(NESTING, "a /* one /* two /* three */ two */ one */ b")).containsExactly("a", "b");
    }

    /**
     * The silent divergence from #45, in miniature: the leaked text sits INSIDE a balanced span,
     * so correct nesting must not see it. Under the bug the parse succeeds and yields extra
     * content tokens, with no diagnostic to assert on — which is why the assertion counts words.
     *
     * <p>See {@code SqlShapedIssue45} below for the ticket's own SQL examples, including the one
     * that looks like a divergence and is not.
     */
    @Test
    void leakedRemainderDoesNotBecomeContent() {
        assertThat(words(NESTING, "keep /* /* */ leaked words */ tail")).containsExactly("keep", "tail");
    }

    @Test
    void emptyNestedBlockIsOneComment() {
        assertThat(words(NESTING, "a /**/ b")).containsExactly("a", "b");
    }

    @Test
    void openDelimiterInsideAContentTokenIsNotAComment() {
        // The scanner is consulted only at a token start, so a delimiter that falls INSIDE a
        // token must not open a comment. That needs a grammar whose words can actually contain
        // '/' and '*' — with a word class that excludes them the input cannot express the case
        // at all, and the test would assert nothing.
        var grammar = """
            Program     <- Word+
            Word        <- < [a-zA-Z0-9_/*]+ >
            %whitespace <- [ \\t\\r\\n]*
            %nest '/*' '*/'
            """;

        assertThat(words(grammar, "x/*text*/y")).containsExactly("x/*text*/y");
    }

    @Test
    void anApostropheDelimiterEmitsValidJava() {
        // The generated lexer holds each open delimiter's first character as a Java char
        // literal. Escaping it as if it were string content emits three apostrophes in a row,
        // which does not compile — so the grammar would parse and then fail to build. Unusual
        // input, but "the generated source is valid Java for every grammar that parses" is not
        // a property to leave to chance.
        var grammar = """
            Program     <- Word+
            Word        <- < [a-z]+ >
            %whitespace <- [ \\t\\r\\n]*
            %nest "'x" "x'"
            """;

        assertThat(PegParser.fromGrammar(grammar)
                            .isSuccess())
        .withFailMessage("a %nest delimiter starting with an apostrophe must still generate compilable Java")
        .isTrue();
        assertThat(words(grammar, "a 'x inside x' b")).containsExactly("a", "b");
    }

    @Test
    void unterminatedCommentFallsThroughToTheDfa() {
        // Deliberate: an unbalanced block is NOT consumed to end of input. Whatever the lexer
        // did with malformed input before the grammar declared %nest, it still does — so the
        // directive can only change the reading of comments that actually balance.
        var withDirective = words(NESTING, "a /* never closed");
        var without = words(BASE, "a /* never closed");

        assertThat(withDirective).isEqualTo(without);
    }

    @Test
    void nestedCommentIsClassifiedAsBlockCommentTrivia() {
        // The kind must be the one the equivalent %whitespace alternative would have produced,
        // not a new kind of the scanner's own — otherwise a consumer switching on trivia kind
        // silently stops seeing comments the moment nesting is enabled.
        var parser = PegParser.fromGrammar(NESTING)
                              .unwrap();
        var tokens = parser.parse("a /* outer /* inner */ done */ b")
                           .cst()
                           .tokens();

        assertThat(kindsOfCommentTokens(tokens)).containsExactly(TokenArray.KIND_BLOCK_COMMENT);
    }

    @Test
    void nestedDocCommentKeepsItsDocKind() {
        // The doc-variant refinement runs on the matched text after the token is produced. The
        // nesting path reaches it because it substitutes for the DFA scan rather than bypassing
        // the rest of the loop — if it had emitted-and-continued, this would come back as a
        // plain block comment and the doc kind would be unreachable under %nest.
        var parser = PegParser.fromGrammar(NESTING)
                              .unwrap();
        var tokens = parser.parse("a /** outer /* inner */ done */ b")
                           .cst()
                           .tokens();

        assertThat(kindsOfCommentTokens(tokens)).containsExactly(TokenArray.KIND_DOC_BLOCK_COMMENT);
    }

    private static List<Integer> kindsOfCommentTokens(TokenArray tokens) {
        var kinds = new ArrayList<Integer>();

        for (int i = 0; i < tokens.count(); i++) {
            if (tokens.textAt(i)
                      .toString()
                      .startsWith("/*")) {
                kinds.add(tokens.kindAt(i));
            }
        }

        return kinds;
    }

    @Test
    void roundTripReconstructionIsExact() {
        // Trivia lives in the token stream, so a nesting comment must be reconstructible
        // verbatim. A scanner that mis-measured the span would round-trip short.
        var input = "a /* outer /* inner */ still */ b";
        var parser = PegParser.fromGrammar(NESTING)
                              .unwrap();

        assertThat(parser.parse(input)
                         .cst()
                         .reconstruct())
        .isEqualTo(input);
    }

    @Test
    void haskellStyleDelimitersNestToo() {
        // Nothing about the mechanism is specific to C-style comments. An open delimiter that
        // resembles no comment prefix peglib knows maps to plain whitespace trivia, which is
        // the correct classification for a language with no doc-comment convention here.
        var grammar = """
            Program     <- Word+
            Word        <- < [a-zA-Z0-9_]+ >
            %whitespace <- [ \\t\\r\\n]*
            %nest '{-' '-}'
            """;

        assertThat(words(grammar, "a {- outer {- inner -} still -} b")).containsExactly("a", "b");
    }

    @Test
    void twoNestDirectivesAreBothActive() {
        // Each %nest occurrence contributes an independent pair; a second must not replace the
        // first. Asserted because "last one wins" is the natural bug for a directive whose
        // argument is not a rule name.
        var grammar = """
            Program     <- Word+
            Word        <- < [a-zA-Z0-9_]+ >
            %whitespace <- [ \\t\\r\\n]*
            %nest '/*' '*/'
            %nest '{-' '-}'
            """;

        assertThat(words(grammar, "a /* x /* y */ z */ b {- p {- q -} r -} c")).containsExactly("a", "b", "c");
    }

    @Test
    void grammarWithoutNestDeclaresNoNestingTrivia() {
        assertThat(GrammarParser.parse(BASE)
                                .unwrap()
                                .nestingTrivia())
        .isEmpty();
    }

    @Test
    void nestDirectiveIsCarriedOnTheGrammar() {
        var pairs = GrammarParser.parse(NESTING)
                                 .unwrap()
                                 .nestingTrivia();

        assertThat(pairs).singleElement()
                         .satisfies(pair -> {
                             assertThat(pair.open()).isEqualTo("/*");
                             assertThat(pair.close()).isEqualTo("*/");
                         });
    }

    @Test
    void emptyDelimiterIsRefusedAtParseTime() {
        // Not merely invalid — an empty open delimiter matches everywhere and advances the
        // counter by zero, so accepting it would hang the lexer on first use. This is the one
        // directive argument where the relaxed "unknown names are inert" policy would be unsafe.
        assertThat(GrammarParser.parse(BASE + "%nest '' '*/'\n")
                                .isSuccess()).isFalse();
        assertThat(GrammarParser.parse(BASE + "%nest '/*' ''\n")
                                .isSuccess()).isFalse();
    }

    @Test
    void malformedNestDirectiveIsAnErrorNotASilentDrop() {
        // %nest claims its name unconditionally, so a wrong argument shape is reported. Were it
        // guarded on a lookahead the way %memo and %parser are, this would fall through to the
        // generic directive path and quietly do nothing.
        assertThat(GrammarParser.parse(BASE + "%nest Foo\n")
                                .isSuccess()).isFalse();
        assertThat(GrammarParser.parse(BASE + "%nest '/*'\n")
                                .isSuccess()).isFalse();
    }

    @Test
    void nestingWorksWithNoWhitespaceCommentAlternative() {
        // %nest stands alone. The pair does not require a matching %whitespace alternative,
        // which matters because the rule that alternative would name is exactly the one a
        // nesting language cannot write.
        var grammar = """
            Program     <- Word+
            Word        <- < [a-zA-Z0-9_]+ >
            %whitespace <- [ \\t\\r\\n]*
            %nest '/*' '*/'
            """;

        assertThat(words(grammar, "a /* outer /* inner */ still */ b")).containsExactly("a", "b");
    }

    @Test
    void nestingWorksWithNoWhitespaceDirectiveAtAll() {
        // The pair is the only thing telling the lexer this text is trivia. Worth pinning
        // separately from the case above: with no %whitespace the grammar has no trivia
        // machinery of its own, and the scanner's token has to be skipped by the parser purely
        // on its reserved kind. Words are separated by the comment itself, not by spaces.
        var grammar = """
            Program <- Word+
            Word    <- < [a-zA-Z0-9_]+ >
            %nest '/*' '*/'
            """;

        assertThat(words(grammar, "a/* outer /* inner */ still */b")).containsExactly("a", "b");
    }

    @Test
    void treeAndTokensAgreeOnWhatIsContent() {
        // Shape sanity, per the project's phase-gate rule: byte-equal round-trip alone would
        // pass on a parse that matched an empty alternative and bailed. The Program node must
        // actually span the input, and the comment must not have contributed content tokens.
        var parser = PegParser.fromGrammar(NESTING)
                              .unwrap();
        var result = parser.parse("a /* c /* d */ e */ b");
        var cst = result.cst();

        assertThat(result.diagnostics()).isEmpty();
        assertThat(cst.nodeCount()).isGreaterThan(1);
        assertThat(nonTriviaCount(cst)).isEqualTo(2);
        assertThat(cst.textAt(cst.rootIndex())
                      .toString()).isEqualTo("a /* c /* d */ e */ b");
    }

    private static int nonTriviaCount(CstArray cst) {
        var tokens = cst.tokens();
        int count = 0;

        for (int i = 0; i < tokens.count(); i++) {
            if (!tokens.isTrivia(i)) {
                count++;
            }
        }

        return count;
    }

    /**
     * The two SQL examples from issue #45, kept in the shape the ticket used — line comments and
     * all — because the distinction between them is the whole point and a simplified grammar
     * loses it.
     *
     * <p>The ticket originally offered both as silent divergences. Only one is. The reporter
     * corrected it afterwards, and the correction is worth encoding rather than paraphrasing:
     * <b>a balanced nested comment is exactly the case where the buggy and the correct lexer
     * agree</b>. Where the leaked text falls outside the balanced span, the old lexer reached the
     * right answer by a different route — its early close left {@code -- *&#47;} as a line
     * comment, which swallowed the orphaned close — so `%nest` changes nothing there and must be
     * shown not to.
     *
     * <p>Both directions are asserted. A fix that only ever made comments longer would pass the
     * divergence test and fail the control.
     */
    @org.junit.jupiter.api.Nested
    class SqlShapedIssue45 {
        private static final String SQL = """
            Stmt  <- 'SELECT' Items 'FROM' Name ';'
            Items <- Item (',' Item)*
            Item  <- Num / Name
            Num   <- < [0-9]+ >
            Name  <- < [a-zA-Z_][a-zA-Z0-9_]* >
            LineComment  <- '--' [^\\n]*
            BlockComment <- '/*' (!'*/' .)* '*/'
            %whitespace  <- ([ \\t\\r\\n] / LineComment / BlockComment)*
            """;

        private static final String SQL_NESTING = SQL + "%nest '/*' '*/'\n";

        /**
         * Select-list items in {@code input}.
         *
         * <p>Counts tokens of kind {@code Item}, not {@code Num}/{@code Name}: {@code Item <- Num
         * / Name} is reference-only and spans one token, so it classifies LEXER and absorbs both
         * — the token stream never mentions the finer kinds. The trailing {@code FROM t}
         * contributes one more {@code Item}, which is subtracted.
         */
        private static int selectItems(String grammar, String input) {
            var tokens = PegParser.fromGrammar(grammar)
                                  .unwrap()
                                  .parse(input)
                                  .cst()
                                  .tokens();
            int items = 0;

            for (int i = 0; i < tokens.count(); i++) {
                if (!tokens.isTrivia(i) && "Item".equals(tokens.kindName(i))) {
                    items++;
                }
            }

            return items - 1;
        }

        /** {@code , 999} sits INSIDE the balanced span, so correct nesting yields one item. */
        private static final String INSIDE = "SELECT 1 /* /* */ , 999 -- */\n FROM t;";

        /** {@code , 2} sits OUTSIDE the balanced span, so two items is right either way. */
        private static final String OUTSIDE = "SELECT 1 /* a /* b */ -- */\n , 2 FROM t;";

        @Test
        void textInsideABalancedSpanIsNotContent() {
            assertThat(selectItems(SQL_NESTING, INSIDE))
            .withFailMessage("', 999' is inside the balanced comment; correct nesting sees one select item")
            .isEqualTo(1);
        }

        @Test
        void withoutTheDirectiveThatTextLeaksAsContent() {
            // The divergence, stated as a measurement rather than a claim: two items, no
            // diagnostic about comments, a query meaning something other than what it says.
            assertThat(selectItems(SQL, INSIDE)).isEqualTo(2);
        }

        @Test
        void textOutsideABalancedSpanIsUnaffectedByTheDirective() {
            // The control, and the ticket's own correction. Both lexers must agree here — the old
            // one arrives at the right answer by a different route. If %nest changed this, it
            // would be over-consuming.
            assertThat(selectItems(SQL_NESTING, OUTSIDE)).isEqualTo(2);
            assertThat(selectItems(SQL, OUTSIDE)).isEqualTo(selectItems(SQL_NESTING, OUTSIDE));
        }
    }
}
