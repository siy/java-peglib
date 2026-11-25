package org.pragmatica.peg.token;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.Parser;
import org.pragmatica.peg.cst.CstArray;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.incremental.IncrementalParser;
import org.pragmatica.peg.lexer.DfaBuilder;
import org.pragmatica.peg.lexer.LexerEngine;
import org.pragmatica.peg.lexer.RuleClassifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #49: an incremental re-lex must equal a fresh lex of the edited input, token for token. Before
 * 0.7.5 the splice re-lexed one token either side of the edit and trusted the result, so an edit that
 * changed a token further away (a {@code /} typed before {@code /*}, or a {@code *}{@code /} closing a
 * comment opened far earlier) left stale tokens behind.
 */
class SpliceLexDifferentialTest {
    private static final String COMMENTS = """
        File <- Stmt*
        Stmt <- Name '=' Num ';'
        Name <- [a-z]+
        Num  <- [0-9]+
        %whitespace <- ([ \\t\\r\\n] / '//' [^\\n]* / '/*' (!'*/' .)* '*/')*
        %checkpoint Stmt
        """;
    private static final String NESTED = """
        File <- Item*
        Item <- Name / Num / '(' / ')'
        Name <- [a-z]+
        Num  <- [0-9]+
        %whitespace <- ([ \\n] / '/*' (!'*/' .)* '*/')*
        %nest '/*' '*/'
        """;
    // Testing for a three-character %nest opener reads two characters past a token start, one more than
    // the DFA reads after a one-character token. A '#' typed after '{-' must re-lex the '{'.
    private static final String NEST_ONLY = """
        File <- Item*
        Item <- Name / Num / '{' / '-' / '#' / '}'
        Name <- [a-z]+
        Num  <- [0-9]+
        %whitespace <- [ \\n]*
        %nest '{-#' '#-}'
        """;
    // A trailing guard reads the character after the match, and a string reads to its closing quote.
    private static final String GUARDED = """
        File <- (Kw / Name / Str / Num)*
        Kw   <- < 'end'i ![a-z] >
        Name <- [a-z]+
        Str  <- '"' [^"]* '"'
        Num  <- [0-9]+ ('.' [0-9]+)?
        %whitespace <- [ \\n]*
        """;
    private static final String[] PIECES = {"/", "*", "*/", "/*", "//", "{-#", "#-}", "#", "\n", "a", "z", "1", ".", " ", "\"", "", "end", "(", ")", "x = 1;"};

    private static String tokens(TokenArray tokens) {
        var sb = new StringBuilder();

        for (int i = 0; i < tokens.count(); i++) {
            sb.append(tokens.kindAt(i)).append(':').append(tokens.startAt(i)).append('-').append(tokens.endAt(i)).append(' ');
        }

        return sb.toString();
    }

    @Test
    void issue49_slashTypedBeforeABlockComment_relexesTheRestOfTheLine() {
        var parser = PegParser.fromGrammar(COMMENTS).unwrap();
        var input = "a = 1; b = 2; /* c/*/ c = 3;";
        var lexer = parser.lexer();

        for (var lexFn : new LexFn[]{lexer, lexer::lex}) {
            var spliced = lexFn.lex(input).spliceLex(lexFn, 14, 0, "/");
            var fresh = lexFn.lex(spliced.input());

            assertThat(fresh.count()).isEqualTo(15);
            assertThat(tokens(spliced)).isEqualTo(tokens(fresh));
        }
    }

    /** Testing for a %nest opener reads further past a token start than the DFA does. */
    @Test
    void lastCharacterOfANestOpener_relexesTheTokenItCompletes() {
        var lexer = PegParser.fromGrammar(NEST_ONLY).unwrap().lexer();
        var engine = engine(NEST_ONLY);

        for (var lexFn : new LexFn[]{lexer, lexer::lex, engine, engine::lex}) {
            var spliced = lexFn.lex("a {- b #-} c").spliceLex(lexFn, 4, 0, "#");
            var fresh = lexFn.lex("a {-# b #-} c");

            assertThat(fresh.count()).isEqualTo(5);
            assertThat(tokens(spliced)).isEqualTo(tokens(fresh));
        }
    }

    @Test
    void closingACommentOpenedFarEarlier_relexesFromWhereItOpened() {
        var parser = PegParser.fromGrammar(COMMENTS).unwrap();
        var input = "a = 1; /* b = 2; c = 3; d = 4; e = 5; f = 6;";
        var lexer = parser.lexer();

        for (var lexFn : new LexFn[]{lexer, lexer::lex}) {
            var spliced = lexFn.lex(input).spliceLex(lexFn, input.length(), 0, " */");

            assertThat(tokens(spliced)).isEqualTo(tokens(lexFn.lex(spliced.input())));
        }
    }

    /** The interpreted lexer, which must record scan extents the same way the generated one does. */
    private static LexerEngine engine(String grammarText) {
        var grammar = GrammarParser.parse(grammarText).unwrap();
        var classification = RuleClassifier.classify(grammar).unwrap();
        var built = DfaBuilder.build(grammar, classification).unwrap();

        return new LexerEngine(built.dfa(),
                               built.kinds().kindNameTable(),
                               DfaBuilder.KIND_WHITESPACE,
                               built.kinds().keywordResolutions(),
                               grammar.nestingTrivia());
    }

    // Each lexer twice: as itself, which is resumable, and as a plain LexFn, which takes the windowed path.
    @Test
    void seededEdits_generatedLexer_spliceEqualsFreshLex() {
        for (var grammar : new String[]{COMMENTS, NESTED, NEST_ONLY, GUARDED}) {
            var lexer = PegParser.fromGrammar(grammar).unwrap().lexer();

            assertSeededEditsMatch(lexer);
            assertSeededEditsMatch(lexer::lex);
        }
    }

    @Test
    void seededEdits_interpretedLexer_spliceEqualsFreshLex() {
        for (var grammar : new String[]{COMMENTS, NESTED, NEST_ONLY, GUARDED}) {
            var engine = engine(grammar);

            assertSeededEditsMatch(engine);
            assertSeededEditsMatch(engine::lex);
        }
    }

    private static void assertSeededEditsMatch(LexFn lexFn) {
        var line = "a = 1; /* x */ b = 22; // y\nc = \"s\" 3.5 end; /* /* */ */ d (e) // z\n";

        // The long input is several re-lex windows wide, so a scan cut off at a window's end is exercised.
        for (var start : new String[]{line, line.repeat(12)}) {
            for (int seed = 0; seed < 1000; seed++) {
                var random = new Random(seed);
                var current = lexFn.lex(start);

                for (int step = 0; step < 8; step++) {
                    var before = current.input();
                    int offset = random.nextInt(before.length() + 1);
                    int length = Math.min(random.nextInt(4), before.length() - offset);
                    var text = PIECES[random.nextInt(PIECES.length)];

                    current = current.spliceLex(lexFn, offset, length, text);
                    var fresh = lexFn.lex(current.input());

                    // A splice that fell back to re-lexing everything would pass trivially.
                    assertThat(current.hasScanReach()).as("windowed path taken").isTrue();

                    assertThat(tokens(current)).as("seed %d step %d: '%s' -> '%s'", seed, step, before, current.input())
                                               .isEqualTo(tokens(fresh));
                }
            }
        }
    }

    /**
     * Token parity cannot tell a splice from a whole re-lex, and neither can {@code hasScanReach()}: a fresh
     * lex carries scan extents too. Count what the lexer is actually asked to do for a local edit.
     */
    @Test
    void localEditInALongInput_lexesNearTheEditOnly() {
        var engine = engine(COMMENTS);
        var input = "a = 1; b = 2;\n".repeat(2000);
        int offset = input.length() / 2;
        var tokens = engine.lex(input);
        var resumedFrom = new java.util.ArrayList<Integer>();
        var resumedTokens = new java.util.ArrayList<Integer>();
        var plainLengths = new java.util.ArrayList<Integer>();
        var resumable = new ResumableLexFn() {
            @Override
            public TokenArray lex(String text) {
                plainLengths.add(text.length());
                return engine.lex(text);
            }

            @Override
            public TokenArray lexFrom(String text, int from, TokenStop stop) {
                resumedFrom.add(from);
                var lexed = engine.lexFrom(text, from, stop);
                resumedTokens.add(lexed.count());
                return lexed;
            }
        };
        LexFn plain = text -> {
            plainLengths.add(text.length());
            return engine.lex(text);
        };

        var spliced = tokens.spliceLex(resumable, offset, 0, " ");

        assertThat(tokens(spliced)).isEqualTo(tokens(engine.lex(spliced.input())));
        assertThat(plainLengths).as("the resumable path never lexes the whole input").isEmpty();
        assertThat(resumedFrom).hasSize(1);
        assertThat(resumedFrom.getFirst()).isBetween(offset - 16, offset);
        assertThat(resumedTokens.getFirst()).as("the re-lex stops once boundaries line up again").isLessThan(10);

        tokens.spliceLex(plain, offset, 0, " ");

        assertThat(plainLengths).as("the windowed path lexes windows, not the input").isNotEmpty();
        assertThat(plainLengths.stream().mapToInt(Integer::intValue).sum()).as("characters lexed in all windows")
                                                                        .isLessThan(2048);
    }

    /** The incremental parser must lex as {@link Parser#parse} does, Unicode-escape pre-pass included. */
    @Test
    void javaEditsIntroducingUnicodeEscapes_matchAFreshParse() throws Exception {
        var parser = PegParser.fromGrammar(Files.readString(Path.of("src/test/resources/java25.peg"))).unwrap();
        var input = "class A { void f() { int x = 1; /* c */ g(x); } void h() { return; } }";
        String[] pieces = {"\\u0069", "\\u002a", "\\u002f", "\\", "u", "/", "*", " ", "y", ";"};
        int partial = 0;

        for (int seed = 0; seed < 300; seed++) {
            var random = new Random(seed);
            var ip = new IncrementalParser(parser, input, Set.of("Block", "BlockStmt", "MethodDecl", "ClassBody"));

            for (int step = 0; step < 4; step++) {
                var before = ip.input();
                int offset = random.nextInt(before.length() + 1);
                int length = Math.min(random.nextInt(2), before.length() - offset);
                int count = ip.partialReparseCount();

                ip.edit(offset, length, pieces[random.nextInt(pieces.length)]);
                partial += ip.partialReparseCount() - count;

                var fresh = parser.parse(ip.input());

                assertThat(tokens(ip.currentTokens())).as("seed %d step %d: '%s' -> '%s'", seed, step, before, ip.input())
                                                      .isEqualTo(tokens(fresh.cst().tokens()));
                assertThat(shape(ip.current())).as("seed %d step %d", seed, step).isEqualTo(shape(fresh.cst()));
                assertThat(ip.diagnostics()).isEqualTo(fresh.diagnostics());
            }
        }

        assertThat(partial).as("the partial path must be exercised, or this test compares nothing").isGreaterThan(20);
    }

    private static String shape(CstArray cst) {
        var sb = new StringBuilder();

        for (int i = 0; i < cst.nodeCount(); i++) {
            sb.append(cst.kindAt(i)).append(':').append(cst.parentAt(i)).append(':').append(cst.firstTokenAt(i)).append('-').append(cst.lastTokenAt(i)).append(' ');
        }

        return sb.toString();
    }
}
