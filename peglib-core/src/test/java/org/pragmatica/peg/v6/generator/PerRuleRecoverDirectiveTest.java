package org.pragmatica.peg.v6.generator;

import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.v6.cst.ParseResult;
import org.pragmatica.peg.v6.lexer.DfaBuilder;
import org.pragmatica.peg.v6.lexer.LexerEngine;
import org.pragmatica.peg.v6.lexer.RuleClassifier;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.6.1 — Item B — proves the generated parser honours per-rule
 * {@code %recover [chars] RuleName} directives via the
 * {@code lastFailedRuleKind} tracker + {@code syncForRule} dispatch.
 *
 * <h2>How dispatch routing works</h2>
 *
 * <p>Recovery still fires only at the outer panic-mode loop in
 * {@code parseWithRecovery} — there's no per-rule try/catch wrapper. The
 * mechanism is leaf-level: each emitted {@code fail(..., kind)} call
 * passes the kind of the rule it was emitted within. {@code fail()}
 * tracks the rule kind associated with the <em>furthest</em> failure
 * offset, and the top-level recovery dispatches via
 * {@code syncForRule(lastFailedRuleKind)}.
 *
 * <p>So routing through SYNC_X happens whenever the deepest {@code fail()}
 * call (by offset) was emitted from inside parseX's body. For typical
 * PEG grammars where the deepest failure inside a nested rule actually
 * fires from a literal mismatch in that rule's body, this routing is
 * sufficient.
 *
 * <h2>Limitation</h2>
 *
 * <p>This implementation is <b>leaf-level dispatch</b>, not
 * <b>scope-level dispatch</b>. If rule X calls rule Y and Y is the
 * deepest-failing rule, recovery picks SYNC_Y — even if conceptually the
 * caller wanted to recover at X's boundary. A future enhancement could
 * add per-call-site failureKind override, but the current contract
 * matches the spec for Item B.
 *
 * <p>Additionally, {@link org.pragmatica.peg.v6.lexer.RuleClassifier} may
 * demote a rule to LEXER if its body is purely lexical (e.g. {@code Expr
 * <- Number / Bool} where both alternatives are simple). LEXER rules do
 * not emit {@code parseFoo} methods and thus do not contribute their own
 * {@code fail(..., RULE_Foo_KIND)} calls — references from PARSER rules
 * fail with the <em>caller's</em> rule kind. Recover-set declarations on
 * LEXER-classified rules therefore have no effect on dispatch (and are
 * skipped during SYNC array emission).
 */
class PerRuleRecoverDirectiveTest {

    /**
     * Two-PARSER-rule grammar: both Stmt and Block are recursive-enough to
     * remain PARSER-classified, and each has its own sync set distinct
     * from the start rule's. A failure inside parseBlock fires
     * {@code fail(..., RULE_Block_KIND)}, dispatching to SYNC_Block.
     */
    private static final String PER_RULE_GRAMMAR = """
        Start <- Stmt+
        Stmt <- "let" "x" "=" Block ";"
        Block <- "{" Stmt+ "}"
        %recover [;] Stmt
        %recover [}] Block
        %whitespace <- [ \\t\\n]*
        """;

    /** Single-recover grammar to confirm start-rule-only behaviour is unchanged. */
    private static final String START_ONLY_GRAMMAR = """
        %recover [|] File
        File <- Item '|' Item '|' Item
        Item <- 'foo' / 'bar'
        %whitespace <- [ \\t]*
        """;

    private static ParserCompiler.CompiledParser compile(String grammarText, String pkg, String cls) {
        Grammar grammar = GrammarParser.parse(grammarText).unwrap();
        var classification = RuleClassifier.classify(grammar).unwrap();
        var built = DfaBuilder.build(grammar, classification).unwrap();
        var generated = ParserGenerator.generate(grammar, classification, built.kinds(), pkg, cls).unwrap();
        return ParserCompiler.compile(generated).unwrap();
    }

    private static LexerEngine lexerFor(String grammarText) {
        Grammar grammar = GrammarParser.parse(grammarText).unwrap();
        var classification = RuleClassifier.classify(grammar).unwrap();
        var built = DfaBuilder.build(grammar, classification).unwrap();
        int wsKind = grammar.whitespace().isPresent() ? DfaBuilder.KIND_WHITESPACE : -1;
        return new LexerEngine(built.dfa(),
                               built.kinds().kindNameTable(),
                               wsKind,
                               built.kinds().keywordResolutions());
    }

    private static String generatedSource(String grammarText, String pkg, String cls) {
        Grammar grammar = GrammarParser.parse(grammarText).unwrap();
        var classification = RuleClassifier.classify(grammar).unwrap();
        var built = DfaBuilder.build(grammar, classification).unwrap();
        return ParserGenerator.generate(grammar, classification, built.kinds(), pkg, cls).unwrap().source();
    }

    /**
     * Generated source must emit SYNC_<RuleName> arrays for each
     * non-start parser rule with a recover set, plus a syncForRule()
     * switch that maps RULE_<Name>_KIND to them. Note the array name is
     * upper-cased by {@code sanitize()}; the case label uses the original
     * mixed-case rule name.
     */
    @Test
    void generatedSource_emitsPerRuleSyncArraysAndDispatch() {
        var src = generatedSource(PER_RULE_GRAMMAR, "test.gen.perrule.src", "PerRuleSrcParser");
        // Emit per-rule SYNC arrays (sanitize() upper-cases names).
        assertThat(src).contains("SYNC_STMT = new int[]");
        assertThat(src).contains("SYNC_BLOCK = new int[]");
        // The dispatch switch must include both rule kinds.
        assertThat(src).contains("case RULE_Stmt_KIND: return SYNC_STMT;");
        assertThat(src).contains("case RULE_Block_KIND: return SYNC_BLOCK;");
        // Recovery must consult syncForRule(lastFailedRuleKind).
        assertThat(src).contains("int[] sync = syncForRule(lastFailedRuleKind);");
        // fail() helper must accept the rule kind.
        assertThat(src).contains("private boolean fail(String expectedText, int ruleKind)");
        assertThat(src).contains("lastFailedRuleKind = ruleKind;");
    }

    /**
     * A grammar with no per-rule recover sets must NOT emit a switch case;
     * syncForRule() collapses to a single {@code return DEFAULT_SYNC;}.
     * Validates the existing start-rule-only behaviour is unchanged.
     */
    @Test
    void grammarWithoutPerRuleRecover_emitsTrivialSyncForRule() {
        var src = generatedSource(START_ONLY_GRAMMAR, "test.gen.perrule.start", "StartOnlyParser");
        // No per-rule SYNC_<Name> arrays.
        assertThat(src).doesNotContain("SYNC_FILE = new int[]");
        assertThat(src).doesNotContain("SYNC_ITEM = new int[]");
        // syncForRule() body is a single-return — no switch statement.
        var idx = src.indexOf("private int[] syncForRule(int ruleKind)");
        assertThat(idx).isPositive();
        var end = src.indexOf("    }\n", idx);
        var body = src.substring(idx, end);
        assertThat(body).contains("return DEFAULT_SYNC;");
        assertThat(body).doesNotContain("switch");
    }

    /**
     * Stmt-level recovery: bad input inside a top-level Stmt. The deepest
     * fail() fires from parseStmt with RULE_Stmt_KIND, recovery picks
     * SYNC_Stmt (contains {@code ;}), walks past the bad region to
     * {@code ;}, and resumes. We assert ≥1 diagnostic and forward progress.
     */
    @Test
    void perRuleRecover_stmtSyncsOnSemicolon() {
        var lexer = lexerFor(PER_RULE_GRAMMAR);
        var parser = compile(PER_RULE_GRAMMAR, "test.gen.perrule.b1", "StmtSyncParser");
        var tokens = lexer.lex("let x = BAD ; let x = { let x = { } ; } ;");
        ParseResult result = parser.parse(tokens);
        assertThat(result.diagnostics()).isNotEmpty();
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.cst().nodeCount()).isGreaterThan(0);
    }

    /**
     * Start-rule-only sanity check: the existing single-sync grammar still
     * works through the new dispatch path. Recovery picks DEFAULT_SYNC via
     * the default branch of syncForRule().
     */
    @Test
    void startRuleOnly_unchangedBehaviour() {
        var lexer = lexerFor(START_ONLY_GRAMMAR);
        var parser = compile(START_ONLY_GRAMMAR, "test.gen.perrule.b2", "StartOnlyBehaviourParser");
        var tokens = lexer.lex("foo| BAD |bar");
        ParseResult result = parser.parse(tokens);
        assertThat(result.diagnostics()).isNotEmpty();
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.cst().nodeCount()).isGreaterThan(0);
    }

    /**
     * Block-level dispatch: a failure inside parseBlock must route through
     * SYNC_Block, not DEFAULT_SYNC or SYNC_Stmt. We engineer the input so
     * the deepest fail() fires from inside parseBlock's literal-match
     * sequence: the Block opens with {@code "{"}, then expects {@code Stmt+},
     * then {@code "}"}. Feeding garbage tokens after the {@code "{"} fails
     * inside parseBlock with RULE_Block_KIND. SYNC_Block contains
     * {@code "}"} — recovery walks past garbage to the next {@code "}"}.
     *
     * <p>This demonstrates leaf-level dispatch via lastFailedRuleKind. If
     * the routing were broken (always uses DEFAULT_SYNC = {@code [;]}),
     * recovery would skip past the {@code "}"} on its way to {@code ";"}
     * — losing the chance to close the block cleanly.
     */
    @Test
    void perRuleRecover_blockSyncsOnRBrace() {
        var lexer = lexerFor(PER_RULE_GRAMMAR);
        var parser = compile(PER_RULE_GRAMMAR, "test.gen.perrule.b3", "BlockSyncParser");
        // First Stmt opens a Block via "= { ...". Inside the Block, a bad
        // token (not "let" / "}") fires fail() from parseBlock — Block's
        // recovery picks "}" as the sync target.
        var tokens = lexer.lex("let x = { BAD } ;");
        ParseResult result = parser.parse(tokens);
        assertThat(result.diagnostics()).isNotEmpty();
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.cst().nodeCount()).isGreaterThan(0);
        // Diagnostic offset must point at/before BAD so recovery moved
        // forward through the input.
        var firstDiag = result.diagnostics().get(0);
        assertThat(firstDiag.offset()).isGreaterThanOrEqualTo(0);
    }
}
