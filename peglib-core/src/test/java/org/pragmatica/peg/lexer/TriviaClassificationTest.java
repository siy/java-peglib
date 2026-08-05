package org.pragmatica.peg.lexer;

import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.generator.LexerGenerator;
import org.pragmatica.peg.token.TokenArray;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase A.6 — verify content-based trivia classification reclassifies
 * WHITESPACE tokens whose text starts with {@code //} or {@code /*} into
 * {@link TokenArray#KIND_LINE_COMMENT} or {@link TokenArray#KIND_BLOCK_COMMENT}
 * respectively. Pure whitespace runs remain {@link TokenArray#KIND_WHITESPACE}.
 *
 * <p>Sound prefix check: a {@code %whitespace} body that absorbs whitespace,
 * line comments, and block comments produces one trivia token per maximal
 * match. The first character disambiguates: {@code //} → LINE_COMMENT,
 * {@code /*} → BLOCK_COMMENT, anything else → WHITESPACE.
 */
class TriviaClassificationTest {
    // Same shape as java25.peg's %whitespace: a Choice over (whitespace char | line
    // comment | block comment) wrapped in a Kleene closure. The line-comment branch
    // uses the negated char class [^\n] (DFA-friendly) and the block-comment branch
    // matches the canonical "delimited block" pattern handled by DfaBuilder.
    private static final String GRAMMAR_WITH_COMMENTS = """
        Word <- [a-zA-Z]+
        %whitespace <- ([ \\t\\n] / '//' [^\\n]* / '/*' (!'*/' .)* '*/')*
        """;

    private static final String GRAMMAR_WS_ONLY = """
        Word <- [a-zA-Z]+
        %whitespace <- [ \\t\\n]*
        """;

    private static LexerEngine engineFor(String grammarText) {
        var grammar = GrammarParser.parse(grammarText)
                                   .unwrap();
        var classification = RuleClassifier.classify(grammar)
                                           .unwrap();
        var built = DfaBuilder.build(grammar, classification)
                              .unwrap();
        int wsKind = grammar.whitespace()
                            .isPresent()
                     ? DfaBuilder.KIND_WHITESPACE
                     : - 1;
        return new LexerEngine(built.dfa(),
                               built.kinds()
                                    .kindNameTable(),
                               wsKind,
                               built.kinds()
                                    .keywordResolutions());
    }

    private static int countByKind(TokenArray tokens, int kind) {
        int n = 0;
        for (int i = 0; i < tokens.count(); i++ ) {
            if (tokens.kindAt(i) == kind) {
                n++ ;
            }
        }
        return n;
    }

    private static String reconstruct(TokenArray tokens) {
        var sb = new StringBuilder();
        for (int i = 0; i < tokens.count(); i++ ) {
            sb.append(tokens.textAt(i));
        }
        return sb.toString();
    }

    @Test
    void pureWhitespace_remainsClassifiedAsWhitespace() {
        var engine = engineFor(GRAMMAR_WITH_COMMENTS);
        var input = "  \t\n  ";
        var tokens = engine.lex(input);
        assertThat(tokens.count())
        .isGreaterThan(0);
        // All trivia tokens are WHITESPACE; no LINE_COMMENT/BLOCK_COMMENT promoted.
        assertThat(countByKind(tokens, TokenArray.KIND_LINE_COMMENT))
        .isZero();
        assertThat(countByKind(tokens, TokenArray.KIND_BLOCK_COMMENT))
        .isZero();
        for (int i = 0; i < tokens.count(); i++ ) {
            assertThat(tokens.kindAt(i))
            .as("token %d is whitespace", i)
            .isEqualTo(TokenArray.KIND_WHITESPACE);
        }
        assertThat(reconstruct(tokens))
        .isEqualTo(input);
    }

    @Test
    void lineComment_isClassifiedAsLineComment() {
        var engine = engineFor(GRAMMAR_WITH_COMMENTS);
        var input = "// hello\n";
        var tokens = engine.lex(input);
        assertThat(tokens.count())
        .isGreaterThan(0);
        // The leading "//" prefix promotes the trivia token to LINE_COMMENT.
        assertThat(tokens.kindAt(0))
        .isEqualTo(TokenArray.KIND_LINE_COMMENT);
        assertThat(tokens.textAt(0)
                         .toString())
        .startsWith("//");
        assertThat(reconstruct(tokens))
        .isEqualTo(input);
    }

    @Test
    void blockComment_isClassifiedAsBlockComment() {
        var engine = engineFor(GRAMMAR_WITH_COMMENTS);
        var input = "/* multi\nline */";
        var tokens = engine.lex(input);
        assertThat(tokens.count())
        .isGreaterThan(0);
        // The leading "/*" prefix promotes the trivia token to BLOCK_COMMENT.
        assertThat(tokens.kindAt(0))
        .isEqualTo(TokenArray.KIND_BLOCK_COMMENT);
        assertThat(tokens.textAt(0)
                         .toString())
        .startsWith("/*");
        assertThat(reconstruct(tokens))
        .isEqualTo(input);
    }

    @Test
    void mixedContent_classifiesEachTriviaTokenByItsPrefix() {
        var engine = engineFor(GRAMMAR_WITH_COMMENTS);
        // foo, then "//c1\n" which is a comment+newline, then bar, then "/* c2 */", then baz.
        var input = "foo // c1\nbar /* c2 */ baz";
        var tokens = engine.lex(input);
        // Round-trip must hold.
        assertThat(reconstruct(tokens))
        .isEqualTo(input);
        // We expect at least one of each comment kind to appear in the stream.
        assertThat(countByKind(tokens, TokenArray.KIND_LINE_COMMENT))
        .as("at least one LINE_COMMENT token from '// c1\\n'")
        .isGreaterThanOrEqualTo(1);
        assertThat(countByKind(tokens, TokenArray.KIND_BLOCK_COMMENT))
        .as("at least one BLOCK_COMMENT token from '/* c2 */'")
        .isGreaterThanOrEqualTo(1);
        // And the three Word tokens (foo, bar, baz) are present as non-trivia.
        int nonTrivia = 0;
        for (int i = 0; i < tokens.count(); i++ ) {
            if (!tokens.isTrivia(i)) {
                nonTrivia++ ;
            }
        }
        assertThat(nonTrivia)
        .isEqualTo(3);
    }

    @Test
    void singleSlashOutsideComment_neverPromoted() {
        // Grammar without comment branches: a lone '/' is no longer absorbed by
        // %whitespace; instead make it part of the Word rule's alphabet so the
        // lexer accepts it without classifying it as trivia.
        var engine = engineFor("""
            Punct <- [/]
            %whitespace <- [ \\t\\n]*
            """);
        var input = "/";
        var tokens = engine.lex(input);
        // Single '/' is one Punct token, NOT trivia.
        assertThat(tokens.count())
        .isEqualTo(1);
        assertThat(tokens.isTrivia(0))
        .isFalse();
        // The classification pass never fires on non-WHITESPACE tokens.
        assertThat(tokens.kindAt(0))
        .isNotEqualTo(TokenArray.KIND_LINE_COMMENT);
        assertThat(tokens.kindAt(0))
        .isNotEqualTo(TokenArray.KIND_BLOCK_COMMENT);
    }

    @Test
    void singleCharWhitespace_notMisclassifiedByPrefixCheck() {
        // The classification guard is `lastAcceptEnd > pos + 1`, so a 1-char
        // whitespace token is never inspected — guarantees no IndexOutOfBounds
        // and no spurious classification.
        var engine = engineFor(GRAMMAR_WS_ONLY);
        var input = " ";
        var tokens = engine.lex(input);
        assertThat(tokens.count())
        .isEqualTo(1);
        assertThat(tokens.kindAt(0))
        .isEqualTo(TokenArray.KIND_WHITESPACE);
        assertThat(reconstruct(tokens))
        .isEqualTo(input);
    }

    // ---- 0.6.1 — DOC line/block classification --------------------------------
    //
    // The line-comment alternative in the grammar above (`'//' [^\n]*`) lexes
    // any `/+` line including `///` and `////`. Post-classification distinguishes
    // them by inspecting the third char. Doc-block (`/** */`) is exercised via a
    // grammar that routes the block via compileDelimitedBlock (see comment on
    // the disabled blockComment tests above for the regular path's caveat).
    //
    // We deliberately exercise the line variants through the existing GRAMMAR_WITH_COMMENTS
    // (which lexes `//` runs cleanly via the negated-char-class branch), and exercise the
    // block variants through a grammar whose %whitespace IS the block-comment delimited
    // pattern alone, so the DFA routes it through compileDelimitedBlock.

    /** %whitespace is a single block-comment match — DfaBuilder routes via compileDelimitedBlock. */
    private static final String GRAMMAR_BLOCK_ONLY = """
        Word <- [a-zA-Z]+
        %whitespace <- ([ \\t\\n] / '/*' (!'*/' .)* '*/')*
        """;

    @Test
    void tripleSlashComment_isClassifiedAsDocLineComment() {
        var engine = engineFor(GRAMMAR_WITH_COMMENTS);
        var input = "/// doc\n";
        var tokens = engine.lex(input);
        assertThat(tokens.count())
        .isGreaterThan(0);
        assertThat(tokens.kindAt(0))
        .isEqualTo(TokenArray.KIND_DOC_LINE_COMMENT);
        assertThat(tokens.textAt(0)
                         .toString())
        .startsWith("///");
        assertThat(reconstruct(tokens))
        .isEqualTo(input);
        assertThat(tokens.isTrivia(0))
        .isTrue();
    }

    @Test
    void quadrupleSlashComment_isClassifiedAsDocLineComment() {
        // 4+ slashes still qualify as doc — the rule is "3 or more slashes".
        var engine = engineFor(GRAMMAR_WITH_COMMENTS);
        var input = "//// still doc\n";
        var tokens = engine.lex(input);
        assertThat(tokens.kindAt(0))
        .isEqualTo(TokenArray.KIND_DOC_LINE_COMMENT);
        assertThat(reconstruct(tokens))
        .isEqualTo(input);
    }

    @Test
    void doubleSlashComment_remainsLineComment() {
        // Regression: the doc-line classification must not catch a regular `//`.
        var engine = engineFor(GRAMMAR_WITH_COMMENTS);
        var input = "// regular\n";
        var tokens = engine.lex(input);
        assertThat(tokens.kindAt(0))
        .isEqualTo(TokenArray.KIND_LINE_COMMENT);
    }

    @Test
    void blockComment_isClassifiedAsBlockComment_viaDelimitedBlockGrammar() {
        var engine = engineFor(GRAMMAR_BLOCK_ONLY);
        var input = "/* plain */";
        var tokens = engine.lex(input);
        assertThat(tokens.count())
        .isGreaterThan(0);
        assertThat(tokens.kindAt(0))
        .isEqualTo(TokenArray.KIND_BLOCK_COMMENT);
        assertThat(reconstruct(tokens))
        .isEqualTo(input);
    }

    @Test
    void docBlockComment_isClassifiedAsDocBlockComment() {
        var engine = engineFor(GRAMMAR_BLOCK_ONLY);
        var input = "/** doc */";
        var tokens = engine.lex(input);
        assertThat(tokens.kindAt(0))
        .isEqualTo(TokenArray.KIND_DOC_BLOCK_COMMENT);
        assertThat(reconstruct(tokens))
        .isEqualTo(input);
    }

    @Test
    void tripleStarBlockComment_isClassifiedAsDocBlockComment() {
        // `/*** foo */` starts with `/**` followed by `*` (not `/`), so it qualifies.
        var engine = engineFor(GRAMMAR_BLOCK_ONLY);
        var input = "/*** foo */";
        var tokens = engine.lex(input);
        assertThat(tokens.kindAt(0))
        .isEqualTo(TokenArray.KIND_DOC_BLOCK_COMMENT);
    }

    @Test
    void smallestEmptyBlockComment_isRegularBlockComment_notDoc() {
        // `/**/` is the canonical "smallest empty block" — NOT Javadoc.
        var engine = engineFor(GRAMMAR_BLOCK_ONLY);
        var input = "/**/";
        var tokens = engine.lex(input);
        assertThat(tokens.kindAt(0))
        .isEqualTo(TokenArray.KIND_BLOCK_COMMENT);
        assertThat(reconstruct(tokens))
        .isEqualTo(input);
    }

    @Test
    void closingStarSlashBlockComment_isDocBlockComment() {
        // `/***/` is length 5: starts with `/**`, char[3] is `*`, char[4] is `/`.
        // Doc rule: `/**` + anything except `/` at char[3]; here char[3]='*' qualifies.
        var engine = engineFor(GRAMMAR_BLOCK_ONLY);
        var input = "/***/";
        var tokens = engine.lex(input);
        assertThat(tokens.kindAt(0))
        .isEqualTo(TokenArray.KIND_DOC_BLOCK_COMMENT);
    }

    @Test
    void docKinds_areTrivia() {
        // The TokenArray.isTrivia helper must include both DOC variants.
        var engine = engineFor(GRAMMAR_WITH_COMMENTS);
        var input = "/// doc\n";
        var tokens = engine.lex(input);
        assertThat(tokens.isTrivia(0))
        .as("DOC_LINE_COMMENT is trivia")
        .isTrue();

        var engine2 = engineFor(GRAMMAR_BLOCK_ONLY);
        var tokens2 = engine2.lex("/** doc */");
        assertThat(tokens2.isTrivia(0))
        .as("DOC_BLOCK_COMMENT is trivia")
        .isTrue();
    }

    // ---- 0.6.2 — unsplit (folded) %whitespace per-kind absorption -------------
    //
    // The unsplit form `([ \t\r\n] / '//' [^\n]* / '/*' (!'*/' .)* '*/')*` used to
    // coalesce an entire mixed-trivia run into ONE KIND_WHITESPACE token. 0.6.2's
    // DfaBuilder.absorbWhitespace absorbs each Choice alternative at its own trivia
    // kind so the lexer emits one correctly-classified token per chunk — producing
    // a token stream byte-identical to the split form.

    /** Unsplit folded form — the shape this task makes work. */
    private static final String GRAMMAR_UNSPLIT = """
        Word <- [a-zA-Z]+
        %whitespace <- ([ \\t\\r\\n] / '//' [^\\n]* / '/*' (!'*/' .)* '*/')*
        """;

    /** Split per-kind form — the c4169b6 workaround shape, used as the parity oracle. */
    private static final String GRAMMAR_SPLIT = """
        Word <- [a-zA-Z]+
        %whitespace <- [ \\t\\r\\n]+ / '//' [^\\n]* / '/*' (!'*/' .)* '*/'
        """;

    private static void assertTokenArraysIdentical(TokenArray a, TokenArray b, String input) {
        assertThat(a.count())
        .as("token count for input <%s>", input)
        .isEqualTo(b.count());
        for (int i = 0; i < a.count(); i++ ) {
            assertThat(a.kindAt(i))
            .as("kind of token %d for input <%s>", i, input)
            .isEqualTo(b.kindAt(i));
            assertThat(a.startAt(i))
            .as("start of token %d for input <%s>", i, input)
            .isEqualTo(b.startAt(i));
            assertThat(a.endAt(i))
            .as("end of token %d for input <%s>", i, input)
            .isEqualTo(b.endAt(i));
        }
    }

    private static void assertSplitUnsplitParity(String input) {
        var unsplit = engineFor(GRAMMAR_UNSPLIT)
                          .lex(input);
        var split = engineFor(GRAMMAR_SPLIT)
                        .lex(input);
        assertTokenArraysIdentical(unsplit, split, input);
        assertThat(reconstruct(unsplit))
        .isEqualTo(input);
    }

    @Test
    void unsplitWhitespace_producesIdenticalTokenArrayToSplit_mixedTrivia() {
        assertSplitUnsplitParity("  // hi\n/* blk */  hello");
    }

    @Test
    void unsplitWhitespace_producesIdenticalTokenArrayToSplit_docVariants() {
        assertSplitUnsplitParity("/// doc\n/** doc */ x");
    }

    @Test
    void unsplitWhitespace_producesIdenticalTokenArrayToSplit_consecutiveBlockComments() {
        assertSplitUnsplitParity("/*a*//*b*/");
    }

    @Test
    void unsplitWhitespace_producesIdenticalTokenArrayToSplit_consecutiveLineComments() {
        assertSplitUnsplitParity("//x\n//y\n");
    }

    @Test
    void unsplitWhitespace_classifiesEachTriviaTokenByKind() {
        var tokens = engineFor(GRAMMAR_UNSPLIT)
                         .lex("  // hi\n/* blk */  x");
        // Expected: WS, LINE_COMMENT, WS(\n), BLOCK_COMMENT, WS, Word(x).
        assertThat(countByKind(tokens, TokenArray.KIND_LINE_COMMENT))
        .as("one LINE_COMMENT")
        .isEqualTo(1);
        assertThat(countByKind(tokens, TokenArray.KIND_BLOCK_COMMENT))
        .as("one BLOCK_COMMENT")
        .isEqualTo(1);
        int nonTrivia = 0;
        for (int i = 0; i < tokens.count(); i++ ) {
            if (!tokens.isTrivia(i)) {
                nonTrivia++ ;
            }
        }
        assertThat(nonTrivia)
        .as("only the Word token is non-trivia")
        .isEqualTo(1);
        assertThat(reconstruct(tokens))
        .isEqualTo("  // hi\n/* blk */  x");
    }

    @Test
    void unsplitWhitespace_zeroTrivia_noEmptyTokensNoInfiniteLoop() {
        var tokens = engineFor(GRAMMAR_UNSPLIT)
                         .lex("hello");
        assertThat(tokens.count())
        .isEqualTo(1);
        assertThat(tokens.isTrivia(0))
        .isFalse();
        for (int i = 0; i < tokens.count(); i++ ) {
            assertThat(tokens.endAt(i))
            .as("token %d is non-empty", i)
            .isGreaterThan(tokens.startAt(i));
        }
        assertThat(reconstruct(tokens))
        .isEqualTo("hello");
    }

    @Test
    void unsplitWhitespace_roundTripsViaReconstruct_mixedTrivia() {
        var input = "  // hi\n/* blk */\n/// doc\n/** db */  end";
        var tokens = engineFor(GRAMMAR_UNSPLIT)
                         .lex(input);
        assertThat(reconstruct(tokens))
        .isEqualTo(input);
    }

    @Test
    void unsplitWhitespace_doesNotEmitEmptyMatchWarning() {
        assertThat(generationWarnings(GRAMMAR_UNSPLIT))
        .as("unsplit %whitespace must not trip the empty-match warning")
        .isEmpty();
    }

    private static java.util.List<String> generationWarnings(String grammarText) {
        var grammar = GrammarParser.parse(grammarText)
                                   .unwrap();
        var classification = RuleClassifier.classify(grammar)
                                           .unwrap();
        var built = DfaBuilder.build(grammar, classification)
                              .unwrap();
        return LexerGenerator.generate(grammar,
                                       classification,
                                       built.dfa(),
                                       built.kinds(),
                                       "test.pkg",
                                       "GLexer")
                             .unwrap()
                             .warnings();
    }
}
