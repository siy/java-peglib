package org.pragmatica.peg.v6.generator;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.Rule;
import org.pragmatica.peg.v6.lexer.DfaBuilder;
import org.pragmatica.peg.v6.lexer.RuleClassifier;
import org.pragmatica.peg.v6.lexer.RuleKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Phase B.3 — emit a standalone Java source file that mirrors recursive descent
 * over a {@link org.pragmatica.peg.v6.token.TokenArray TokenArray}, building a
 * {@link org.pragmatica.peg.v6.cst.CstArray CstArray} via
 * {@link org.pragmatica.peg.v6.cst.CstArrayBuilder CstArrayBuilder}.
 *
 * <p>One {@code parse<RuleName>(int parent)} method is emitted per PARSER- (or
 * MIXED-) classified rule. Rule references in expression bodies dispatch to the
 * sibling {@code parse<Reference>} method; lexer-rule references and inline
 * literals consume one token of the matching kind. The entry point
 * {@code parse(TokenArray tokens)} parses the grammar's effective start rule
 * and returns the populated {@code CstArray}.
 *
 * <p>Phase B.4 — the public entry point is now
 * {@code static ParseResult parse(TokenArray tokens)}: a CST is always
 * returned, with an empty diagnostics list on the happy path. On error the
 * generator's panic-mode recovery walks forward to the next default sync
 * token (one of {@code ; , } ) ]} when the grammar uses those literals),
 * appends an Error-flagged node spanning the skipped range, records a
 * {@link org.pragmatica.peg.v6.diagnostic.Diagnostic Diagnostic}, and
 * resumes parsing from the post-sync position. {@code ParseException} stays
 * an internal control-flow vehicle and is never observable to callers.
 */
public final class ParserGenerator {
    /**
     * Phase B.4 default sync-set: punctuation literals every reasonable grammar
     * uses as a statement / clause terminator. Any literal not present in the
     * grammar's inline-literal table is silently skipped — sync-set membership
     * is best-effort and a missing entry simply means recovery falls through to
     * the next sync token (or EOF).
     */
    private static final List<String> DEFAULT_SYNC_LITERALS = List.of(";", ",", "}", ")", "]");

    private ParserGenerator() {}

    public sealed interface ParserGenerationError extends Cause permits ParserGenerationError.InvalidIdentifier,
    ParserGenerationError.NoStartRule,
    ParserGenerationError.StartRuleNotParser,
    ParserGenerationError.UnsupportedExpression,
    ParserGenerationError.UnknownLiteral,
    ParserGenerationError.UnknownReference {
        record InvalidIdentifier(String component, String value) implements ParserGenerationError {
            @Override public String message() {
                return "Invalid Java identifier for " + component + ": '" + value + "'";
            }
        }

        record NoStartRule() implements ParserGenerationError {
            @Override public String message() {
                return "Grammar has no rules; cannot determine start rule";
            }
        }

        record StartRuleNotParser(String name, RuleKind actual) implements ParserGenerationError {
            @Override public String message() {
                return "Start rule '" + name + "' is classified as " + actual + " (expected PARSER or MIXED); generated parser cannot dispatch into it";
            }
        }

        record UnsupportedExpression(String ruleName, String expressionKind, String detail)
        implements ParserGenerationError {
            @Override public String message() {
                return "Cannot emit parser for rule '" + ruleName + "': unsupported expression " + expressionKind + " (" + detail + ")";
            }
        }

        record UnknownLiteral(String ruleName, String literalText) implements ParserGenerationError {
            @Override public String message() {
                return "Rule '" + ruleName + "' references inline literal '" + literalText + "' that has no allocated token kind — DFA build inconsistent";
            }
        }

        record UnknownReference(String ruleName, String referencedRule) implements ParserGenerationError {
            @Override public String message() {
                return "Rule '" + ruleName + "' references undefined rule '" + referencedRule + "'";
            }
        }
    }

    public record GeneratedParser(String packageName, String className, String source) {
        public String fullyQualifiedName() {
            return packageName.isEmpty()
                   ? className
                   : packageName + "." + className;
        }
    }

    public static Result<GeneratedParser> generate(Grammar grammar,
                                                   RuleClassifier.Classification classification,
                                                   DfaBuilder.TokenKindAssignment kinds,
                                                   String packageName,
                                                   String className) {
        // Internal entry: callers are PegParser/tests with validated inputs.
        if ( !isValidQualifiedPackage(packageName)) {
        return new ParserGenerationError.InvalidIdentifier("packageName", String.valueOf(packageName)).result();}
        if ( !isValidIdentifier(className)) {
        return new ParserGenerationError.InvalidIdentifier("className", String.valueOf(className)).result();}
        var startRuleOpt = grammar.effectiveStartRule();
        if ( startRuleOpt.isEmpty()) {
        return new ParserGenerationError.NoStartRule().result();}
        var startRule = startRuleOpt.unwrap();
        var startKind = classification.kinds().get(startRule.name());
        if ( startKind != RuleKind.PARSER && startKind != RuleKind.MIXED) {
        return new ParserGenerationError.StartRuleNotParser(startRule.name(), startKind).result();}
        return new Renderer(grammar, classification, kinds, packageName, className).render();
    }

    /** Render the full parser source. Stateful to keep helper methods readable. */
    private static final class Renderer {
        private final Grammar grammar;
        private final RuleClassifier.Classification classification;
        private final DfaBuilder.TokenKindAssignment kinds;
        private final String packageName;
        private final String className;
        private final Map<String, Rule> ruleMap;
        private final List<Rule> parserRules;
        private final Map<String, Integer> parserRuleKinds;
        private final String[] ruleTable;
        private final Set<Integer> usedTokenKinds;

        // Phase B.5 — names of rules whose alias set is large enough that
        // emitAliasMatch chose the binarySearch path. We collect them so the
        // header can emit the corresponding sorted constant arrays.
        private final Set<String> aliasArrays;

        // Phase 0.6.0 — identifier-fallback constant arrays. Keyed by the
        // skip-prefix rule name (e.g. "Identifier"); value is the sorted
        // int[] of acceptable kinds (idKind + all fallback kinds).
        private final Map<String, int[]> idFallbackArrays;

        Renderer(Grammar grammar,
                 RuleClassifier.Classification classification,
                 DfaBuilder.TokenKindAssignment kinds,
                 String packageName,
                 String className) {
            this.grammar = grammar;
            this.classification = classification;
            this.kinds = kinds;
            this.packageName = packageName;
            this.className = className;
            this.ruleMap = grammar.ruleMap();
            this.parserRules = collectParserRules(grammar, classification);
            // Phase E.1 — share the kind allocation with VisitorGenerator via the
            // public static helper so both generators agree on RULE_*_KIND indices.
            this.parserRuleKinds = allocateParserRuleKinds(grammar, classification);
            this.ruleTable = parserRules.stream().map(Rule::name)
                                               .toArray(String[]::new);
            this.usedTokenKinds = new LinkedHashSet<>();
            this.aliasArrays = new LinkedHashSet<>();
            this.idFallbackArrays = new LinkedHashMap<>();
        }

        Result<GeneratedParser> render() {
            // First pass: emit each rule body into a per-rule buffer. We collect the
            // bodies before stitching the source so the header (KIND constants) can
            // include only kinds that are actually referenced.
            var bodies = new ArrayList<String>(parserRules.size());
            for ( var rule : parserRules) {
                var bodyResult = renderRuleBody(rule);
                if ( !bodyResult.isSuccess()) {
                return bodyResult.map(__ -> (GeneratedParser) null);}
                bodies.add(bodyResult.unwrap());
            }
            // Phase B.4 — compute the default sync-set token kinds. We look up each
            // default punctuation literal in the inline-literal table; missing literals
            // (the grammar didn't use them) are silently skipped — sync-set membership
            // is best-effort. Newline-bearing whitespace is left for a follow-up.
            //
            // 0.6.0 — if the grammar declares a {@code %recover [chars] StartRule}
            // directive for the effective start rule, the per-rule sync set
            // overrides the default for DEFAULT_SYNC.
            //
            // 0.6.1 — Item B — per-rule sync sets. For every rule with a
            // non-empty {@code %recover} entry resolving to ≥1 known inline-literal
            // kind, emit a {@code SYNC_<RuleName>} array. The generated parser
            // tracks {@code lastFailedRuleKind} (updated by {@code fail()} when
            // it advances the furthest-failure position) and dispatches via
            // {@code syncForRule(ruleKind)} at recovery time. This lets nested
            // rule failures recover on rule-specific sync sets while the outer
            // panic-mode loop remains top-level.
            var inlineLiterals = kinds.inlineLiteralToKind();
            var syncKindSet = new TreeSet<Integer>();
            var startRuleName = grammar.effectiveStartRule().unwrap()
                                                          .name();
            var ruleRecoverSet = grammar.recoverSets().get(startRuleName);
            if ( ruleRecoverSet != null && !ruleRecoverSet.isEmpty()) {
            for ( var ch : ruleRecoverSet) {
                var k = inlineLiterals.get(String.valueOf(ch) + "/cs");
                if ( k != null) {
                    syncKindSet.add(k);
                    usedTokenKinds.add(k);
                }
            }} else
            {
            for ( var literal : DEFAULT_SYNC_LITERALS) {
                var k = inlineLiterals.get(literal + "/cs");
                if ( k != null) {
                    syncKindSet.add(k);
                    usedTokenKinds.add(k);
                }
            }}
            var syncKinds = syncKindSet.stream().mapToInt(Integer::intValue)
                                              .toArray();
            // Per-rule sync sets: only emit for rules with non-empty recoverSets
            // that resolve to at least one inline-literal kind AND that are
            // parser/mixed rules (the lookup is keyed by RULE_<Name>_KIND).
            // Start rule is excluded — its set IS the DEFAULT_SYNC above.
            var perRuleSync = new LinkedHashMap<String, int[]>();
            for ( var entry : grammar.recoverSets().entrySet()) {
                var ruleName = entry.getKey();
                if ( ruleName.equals(startRuleName)) {
                continue;}
                if ( !parserRuleKinds.containsKey(ruleName)) {
                continue;}
                var chars = entry.getValue();
                if ( chars == null || chars.isEmpty()) {
                continue;}
                var perKindSet = new TreeSet<Integer>();
                for ( var ch : chars) {
                    var k = inlineLiterals.get(String.valueOf(ch) + "/cs");
                    if ( k != null) {
                        perKindSet.add(k);
                        usedTokenKinds.add(k);
                    }
                }
                if ( !perKindSet.isEmpty()) {
                perRuleSync.put(ruleName, perKindSet.stream().mapToInt(Integer::intValue).toArray());}
            }
            // ERROR sentinel kind sits at index ruleTable.length (one past the user
            // rules); we append "ERROR" to the emitted RULE_TABLE so kindNameAt
            // resolves it.
            var errorKindIndex = ruleTable.length;
            var sb = new StringBuilder(8 * 1024);
            if ( !packageName.isEmpty()) {
            sb.append("package ").append(packageName)
                     .append(";\n\n");}
            sb.append("import java.util.ArrayList;\n");
            sb.append("import java.util.LinkedHashMap;\n");
            sb.append("import java.util.List;\n");
            sb.append("import java.util.Map;\n");
            sb.append("import org.pragmatica.peg.v6.token.TokenArray;\n");
            sb.append("import org.pragmatica.peg.v6.cst.CstArray;\n");
            sb.append("import org.pragmatica.peg.v6.cst.CstArrayBuilder;\n");
            sb.append("import org.pragmatica.peg.v6.cst.ParseResult;\n");
            sb.append("import org.pragmatica.peg.v6.diagnostic.Diagnostic;\n\n");
            sb.append("public final class ").append(className)
                     .append(" {\n\n");
            // RULE_TABLE — names, used by CstArray for kindNameAt. The trailing
            // "ERROR" entry is the sentinel kind for recovery-emitted nodes; the
            // very last entry "_ROOT" is the synthetic-root sentinel under which
            // the start-rule call(s) and any recovery Error nodes are attached.
            sb.append("    private static final String[] RULE_TABLE = {");
            for ( var i = 0; i < ruleTable.length; i++) {
                if ( i > 0) {
                sb.append(", ");}
                sb.append('"').append(escapeJavaString(ruleTable[i]))
                         .append('"');
            }
            if ( ruleTable.length > 0) {
            sb.append(", ");}
            sb.append("\"ERROR\", \"_ROOT\"};\n\n");
            // Per-rule kind constants.
            for ( var entry : parserRuleKinds.entrySet()) {
            sb.append("    private static final int RULE_").append(entry.getKey())
                     .append("_KIND = ")
                     .append(entry.getValue())
                     .append(";\n");}
            sb.append("    private static final int RULE_ERROR_KIND = ").append(errorKindIndex)
                     .append(";\n");
            sb.append("    private static final int RULE_ROOT_KIND = ").append(errorKindIndex + 1)
                     .append(";\n");
            sb.append("\n");
            // Per-token-kind constants — referenced by parse methods.
            // Use kindNameTable for stable, debuggable names.
            var nameTable = kinds.kindNameTable();
            for ( var k : usedTokenKinds) {
                if ( k < 0 || k >= nameTable.length) {
                continue;}
                sb.append("    private static final int KIND_").append(sanitize(nameTable[k]))
                         .append(" = ")
                         .append(k)
                         .append(";\n");
            }
            sb.append("\n");
            // Sync-set: sorted ascending so we can use binarySearch for contains-check.
            sb.append("    private static final int[] DEFAULT_SYNC = new int[] {");
            for ( var i = 0; i < syncKinds.length; i++) {
                if ( i > 0) {
                sb.append(", ");}
                sb.append(syncKinds[i]);
            }
            sb.append("};\n\n");
            // 0.6.1 — Item B — per-rule SYNC_<RuleName> arrays. Sorted ascending
            // for binarySearch in nextSyncToken.
            for ( var entry : perRuleSync.entrySet()) {
                var ruleName = entry.getKey();
                var arr = entry.getValue();
                sb.append("    private static final int[] SYNC_").append(sanitize(ruleName))
                         .append(" = new int[] {");
                for ( var i = 0; i < arr.length; i++) {
                    if ( i > 0) {
                    sb.append(", ");}
                    sb.append(arr[i]);
                }
                sb.append("};\n");
            }
            if ( !perRuleSync.isEmpty()) {
            sb.append("\n");}
            // Phase B.5 — emit per-rule sorted alias arrays for binarySearch path.
            // Linear-OR alias guards (≤4 entries) inline their kinds and don't need
            // an array.
            if ( !aliasArrays.isEmpty()) {
                var aliasMap = kinds.ruleNameToAliasKinds();
                for ( var ruleName : aliasArrays) {
                    var aliasKinds = aliasMap.get(ruleName);
                    if ( aliasKinds == null) {
                    continue;}
                    sb.append("    private static final int[] ALIAS_").append(sanitize(ruleName))
                             .append(" = new int[] {");
                    for ( var i = 0; i < aliasKinds.length; i++) {
                        if ( i > 0) {
                        sb.append(", ");}
                        sb.append(aliasKinds[i]);
                    }
                    sb.append("};\n");
                }
                sb.append("\n");
            }
            // Phase 0.6.0 — emit identifier-fallback constant arrays.
            if ( !idFallbackArrays.isEmpty()) {
                for ( var entry : idFallbackArrays.entrySet()) {
                    var ruleName = entry.getKey();
                    var arr = entry.getValue();
                    sb.append("    private static final int[] IDFALL_").append(sanitize(ruleName))
                             .append(" = new int[] {");
                    for ( var i = 0; i < arr.length; i++) {
                        if ( i > 0) {
                        sb.append(", ");}
                        sb.append(arr[i]);
                    }
                    sb.append("};\n");
                }
                sb.append("\n");
            }
            // Instance state.
            sb.append("    private final TokenArray tokens;\n");
            sb.append("    private final CstArrayBuilder cst;\n");
            sb.append("    private final List<Diagnostic> diagnostics;\n");
            sb.append("    private int pos;\n");
            // Phase 0.6.0-perf — mutable furthest-failure state. fail() updates
            // these; emitRecoveryError reads them. Replaces the throw-on-fail
            // ParseException control flow.
            sb.append("    private int errorPos;\n");
            sb.append("    private String expected;\n");
            sb.append("    private int found;\n");
            // 0.6.1 — Item B — rule-kind of the enclosing rule at the deepest
            // recorded failure. Used by syncForRule() at recovery time to pick
            // the appropriate SYNC_<RuleName> array.
            sb.append("    private int lastFailedRuleKind;\n\n");
            // Constructor.
            sb.append("    private ").append(className)
                     .append("(TokenArray tokens) {\n");
            sb.append("        this.tokens = tokens;\n");
            sb.append("        this.cst = new CstArrayBuilder(tokens.input(), tokens, RULE_TABLE);\n");
            sb.append("        this.diagnostics = new ArrayList<>();\n");
            sb.append("        this.pos = tokens.nextNonTrivia(0);\n");
            sb.append("        this.errorPos = -1;\n");
            sb.append("        this.expected = null;\n");
            sb.append("        this.found = -1;\n");
            sb.append("        this.lastFailedRuleKind = -1;\n");
            sb.append("    }\n\n");
            // Public entry point — Phase B.4 returns ParseResult unconditionally.
            var startName = grammar.effectiveStartRule().unwrap()
                                                      .name();
            sb.append("    public static ParseResult parse(TokenArray tokens) {\n");
            // No defensive null check on tokens: the only public caller path is
            // CompiledParser.parse(TokenArray), which receives a TokenArray
            // freshly produced by the lexer.
            sb.append("        ").append(className)
                     .append(" p = new ")
                     .append(className)
                     .append("(tokens);\n");
            sb.append("        int rootIdx = p.parseWithRecovery();\n");
            sb.append("        CstArray cstArr = p.cst.build(rootIdx);\n");
            sb.append("        return new ParseResult(cstArr, p.diagnostics);\n");
            sb.append("    }\n\n");
            // Phase D.1.2 — partial parse from a specific rule starting at a specific
            // token index. Used by the incremental engine to reparse only the subtree
            // rooted at a checkpoint after an edit. The returned CST always wraps the
            // parsed subtree under the synthetic _ROOT so error recovery can attach
            // sibling Error nodes the same way the full parse does.
            sb.append("    public static ParseResult parseRuleFrom(TokenArray tokens, int fromTokenIdx, int ruleKind) {\n");
            // No defensive null/range checks: the only caller is the incremental
            // engine, which passes a validated tokens array and an index in
            // [0, tokens.count()]. An out-of-range fromTokenIdx surfaces via the
            // !ok recovery branch (synthetic Error node + diagnostic).
            sb.append("        ").append(className)
                     .append(" p = new ")
                     .append(className)
                     .append("(tokens);\n");
            sb.append("        p.pos = tokens.nextNonTrivia(fromTokenIdx);\n");
            sb.append("        int rootFirstTok = p.pos < tokens.count() ? p.pos : (tokens.count() == 0 ? 0 : tokens.count() - 1);\n");
            sb.append("        int rootIdx = p.cst.beginNode(RULE_ROOT_KIND, rootFirstTok, -1);\n");
            sb.append("        boolean ok = parseByKind(p, ruleKind, rootIdx);\n");
            sb.append("        if (!ok) {\n");
            sb.append("            // Mirror the full-parse recovery contract: emit an Error node\n");
            sb.append("            // covering the failing token plus a diagnostic.\n");
            sb.append("            int failedTok = p.pos < tokens.count() ? p.pos : tokens.count() - 1;\n");
            sb.append("            int diagOffset = failedTok >= 0 && failedTok < tokens.count()\n");
            sb.append("                ? tokens.startAt(failedTok) : tokens.input().length();\n");
            sb.append("            int diagLen = failedTok >= 0 && failedTok < tokens.count()\n");
            sb.append("                ? Math.max(1, tokens.endAt(failedTok) - tokens.startAt(failedTok)) : 1;\n");
            sb.append("            String foundText = failedTok >= 0 && failedTok < tokens.count()\n");
            sb.append("                ? String.valueOf(tokens.textAt(failedTok)) : \"<end-of-input>\";\n");
            sb.append("            String expectedText = p.expected != null ? p.expected : \"valid input\";\n");
            sb.append("            p.diagnostics.add(Diagnostic.error(diagOffset, diagLen,\n");
            sb.append("                \"syntax error\", expectedText, foundText));\n");
            sb.append("        }\n");
            sb.append("        int rootLastTok;\n");
            sb.append("        if (tokens.count() == 0) {\n");
            sb.append("            rootLastTok = 0;\n");
            sb.append("        } else if (p.pos > rootFirstTok && p.pos <= tokens.count()) {\n");
            sb.append("            rootLastTok = p.pos - 1;\n");
            sb.append("        } else {\n");
            sb.append("            rootLastTok = rootFirstTok;\n");
            sb.append("        }\n");
            sb.append("        if (rootLastTok < rootFirstTok) rootLastTok = rootFirstTok;\n");
            sb.append("        p.cst.endNode(rootIdx, rootLastTok);\n");
            sb.append("        CstArray cstArr = p.cst.build(rootIdx);\n");
            sb.append("        return new ParseResult(cstArr, p.diagnostics);\n");
            sb.append("    }\n\n");
            // Switch table for partial-parse dispatch: maps rule-kind constant to
            // the corresponding parseFoo invocation.
            sb.append("    private static boolean parseByKind(").append(className)
                     .append(" p, int kind, int parent) {\n");
            sb.append("        switch (kind) {\n");
            for ( var entry : parserRuleKinds.entrySet()) {
            sb.append("            case RULE_").append(entry.getKey())
                     .append("_KIND: return p.parse")
                     .append(entry.getKey())
                     .append("(parent);\n");}
            // Unknown rule-kind: surface as a parse failure so the caller's
            // recovery branch emits an Error node + diagnostic, matching the
            // contract for any other parse-time mismatch. No exception thrown.
            sb.append("            default: return false;\n");
            sb.append("        }\n");
            sb.append("    }\n\n");
            // ruleKinds() — exposes the rule-name to kind mapping so callers
            // (incremental engine, tooling) can look up the kind constant for a
            // rule by name without reading the generated source.
            sb.append("    public static Map<String, Integer> ruleKinds() {\n");
            sb.append("        Map<String, Integer> m = new LinkedHashMap<>();\n");
            for ( var entry : parserRuleKinds.entrySet()) {
            sb.append("        m.put(\"").append(escapeJavaString(entry.getKey()))
                     .append("\", RULE_")
                     .append(entry.getKey())
                     .append("_KIND);\n");}
            sb.append("        return m;\n");
            sb.append("    }\n\n");
            // Phase B.3.1 — full-consumption recovery loop.
            //
            // We always synthesize a top-level "_ROOT" wrapper node so the CST has a
            // single root regardless of how many start-rule attempts and recovery
            // Error nodes accumulate. Each iteration tries the start rule from the
            // current position; on success we skip trailing trivia and either stop
            // (all consumed) or loop again to attack the remaining tokens. On
            // failure (ParseException) we roll back partial CST, walk to the next
            // sync token (or EOF), emit an Error node spanning the skipped range,
            // record a Diagnostic, and resume.
            //
            // Critically, a SUCCESSFUL start-rule call that leaves trailing content
            // tokens unconsumed is NOT silently accepted: we record a "trailing
            // input" diagnostic, emit an Error node covering the unconsumed region,
            // and continue. This surfaces parser/grammar gaps that the previous
            // permissive contract was hiding.
            sb.append("    private int parseWithRecovery() {\n");
            sb.append("        // Synthetic root spanning the whole token stream. All start-rule\n");
            sb.append("        // attempts and recovery Error nodes attach to it as children.\n");
            sb.append("        int rootFirstTok = pos < tokens.count() ? pos : 0;\n");
            sb.append("        int root = cst.beginNode(RULE_ROOT_KIND, rootFirstTok, -1);\n");
            sb.append("        boolean firstAttempt = true;\n");
            sb.append("        while (true) {\n");
            sb.append("            // Skip any leading trivia at the current position before deciding\n");
            sb.append("            // whether anything remains to parse.\n");
            sb.append("            while (pos < tokens.count() && tokens.isTrivia(pos)) pos++;\n");
            sb.append("            if (pos >= tokens.count()) {\n");
            sb.append("                if (firstAttempt) {\n");
            sb.append("                    // Empty / all-trivia input — record a diagnostic so callers\n");
            sb.append("                    // know the parse couldn't even attempt the start rule.\n");
            sb.append("                    int off = tokens.count() == 0 ? 0 : tokens.startAt(0);\n");
            sb.append("                    diagnostics.add(Diagnostic.error(off, 1,\n");
            sb.append("                        \"empty input\", \"start of " + escapeJavaString(startName) + "\", \"<end-of-input>\"));\n");
            sb.append("                }\n");
            sb.append("                break;\n");
            sb.append("            }\n");
            sb.append("            firstAttempt = false;\n");
            sb.append("            int beforeNodes = cst.currentNodeCount();\n");
            sb.append("            int beforePos = pos;\n");
            sb.append("            // Phase 0.6.0-perf — reset furthest-failure tracker before each\n");
            sb.append("            // attempt so the recorded diagnostic reflects this iteration.\n");
            sb.append("            errorPos = -1;\n");
            sb.append("            expected = null;\n");
            sb.append("            found = -1;\n");
            sb.append("            lastFailedRuleKind = -1;\n");
            sb.append("            boolean parsedOk = parse").append(startName)
                     .append("(root);\n");
            sb.append("            if (!parsedOk) {\n");
            sb.append("                // Roll back any partial CST built by the failed start-rule call.\n");
            sb.append("                cst.truncate(beforeNodes);\n");
            sb.append("                emitRecoveryError(root, beforePos);\n");
            sb.append("            } else if (pos == beforePos) {\n");
            sb.append("                // Start rule succeeded without consuming any token. Force\n");
            sb.append("                // progress by skipping one token under an Error node, else we\n");
            sb.append("                // loop forever on the same position.\n");
            sb.append("                emitForcedAdvanceError(root, beforePos);\n");
            sb.append("            }\n");
            sb.append("            if (!parsedOk && pos == beforePos) {\n");
            sb.append("                // Recovery couldn't move past the failing token (no sync, no EOF\n");
            sb.append("                // beyond, etc.); break to avoid an infinite loop.\n");
            sb.append("                break;\n");
            sb.append("            }\n");
            sb.append("            // Loop to either consume more input via another start-rule call or\n");
            sb.append("            // to record additional trailing-input diagnostics.\n");
            sb.append("        }\n");
            sb.append("        // Close the synthetic root over [rootFirstTok, lastConsumedTok]. If\n");
            sb.append("        // no token was consumed (empty input) the span is a degenerate\n");
            sb.append("        // [rootFirstTok, rootFirstTok] which the builder accepts.\n");
            sb.append("        int rootLastTok;\n");
            sb.append("        if (tokens.count() == 0) {\n");
            sb.append("            rootLastTok = 0;\n");
            sb.append("        } else if (pos > 0 && pos <= tokens.count()) {\n");
            sb.append("            rootLastTok = pos - 1;\n");
            sb.append("        } else {\n");
            sb.append("            rootLastTok = rootFirstTok;\n");
            sb.append("        }\n");
            sb.append("        if (rootLastTok < rootFirstTok) rootLastTok = rootFirstTok;\n");
            sb.append("        cst.endNode(root, rootLastTok);\n");
            sb.append("        return root;\n");
            sb.append("    }\n\n");
            // Recovery-error helper: emit Error node spanning [failedTok..syncTok]
            // and record a Diagnostic. Used by parseWithRecovery on a false return
            // from the start rule. Reads furthest-failure context (errorPos /
            // expected / found) recorded by fail() during the failed parse attempt.
            sb.append("    private void emitRecoveryError(int parent, int beforePos) {\n");
            sb.append("        int failedTok = pos < tokens.count() ? pos : tokens.count() - 1;\n");
            sb.append("        int syncTok = nextSyncToken(pos);\n");
            sb.append("        int skipFirst = failedTok >= 0 ? failedTok : 0;\n");
            sb.append("        int skipLast;\n");
            sb.append("        int newPos;\n");
            sb.append("        if (syncTok < tokens.count()) {\n");
            sb.append("            skipLast = syncTok;\n");
            sb.append("            newPos = tokens.nextNonTrivia(syncTok + 1);\n");
            sb.append("        } else {\n");
            sb.append("            skipLast = tokens.count() - 1;\n");
            sb.append("            newPos = tokens.count();\n");
            sb.append("        }\n");
            sb.append("        if (skipLast < skipFirst) skipLast = skipFirst;\n");
            sb.append("        if (skipFirst >= 0 && skipFirst < tokens.count()) {\n");
            sb.append("            int errIdx = cst.beginNode(RULE_ERROR_KIND, skipFirst, parent);\n");
            sb.append("            cst.endNode(errIdx, skipLast);\n");
            sb.append("            cst.setFlag(errIdx, CstArray.FLAG_ERROR);\n");
            sb.append("        }\n");
            sb.append("        int diagOffset;\n");
            sb.append("        if (errorPos >= 0) {\n");
            sb.append("            diagOffset = errorPos;\n");
            sb.append("        } else if (failedTok >= 0 && failedTok < tokens.count()) {\n");
            sb.append("            diagOffset = tokens.startAt(failedTok);\n");
            sb.append("        } else {\n");
            sb.append("            diagOffset = tokens.input().length();\n");
            sb.append("        }\n");
            sb.append("        int diagLen;\n");
            sb.append("        if (skipFirst >= 0 && skipFirst < tokens.count() && skipLast < tokens.count()) {\n");
            sb.append("            diagLen = tokens.endAt(skipLast) - tokens.startAt(skipFirst);\n");
            sb.append("            if (diagLen < 1) diagLen = 1;\n");
            sb.append("        } else {\n");
            sb.append("            diagLen = 1;\n");
            sb.append("        }\n");
            sb.append("        String foundText;\n");
            sb.append("        if (failedTok >= 0 && failedTok < tokens.count()) {\n");
            sb.append("            foundText = String.valueOf(tokens.textAt(failedTok));\n");
            sb.append("        } else {\n");
            sb.append("            foundText = \"<end-of-input>\";\n");
            sb.append("        }\n");
            sb.append("        String expectedText = expected != null ? expected : \"valid input\";\n");
            sb.append("        diagnostics.add(Diagnostic.error(diagOffset, diagLen,\n");
            sb.append("            \"syntax error\", expectedText, foundText));\n");
            sb.append("        pos = newPos;\n");
            sb.append("    }\n\n");
            // Forced-advance helper: when the start rule succeeded but consumed no
            // tokens we still must move forward to terminate the loop. Skip one
            // content token under an Error node and record a diagnostic so the
            // caller knows the parse couldn't progress.
            sb.append("    private void emitForcedAdvanceError(int parent, int atPos) {\n");
            sb.append("        if (atPos < 0 || atPos >= tokens.count()) return;\n");
            sb.append("        int errIdx = cst.beginNode(RULE_ERROR_KIND, atPos, parent);\n");
            sb.append("        cst.endNode(errIdx, atPos);\n");
            sb.append("        cst.setFlag(errIdx, CstArray.FLAG_ERROR);\n");
            sb.append("        int diagOffset = tokens.startAt(atPos);\n");
            sb.append("        int diagLen = tokens.endAt(atPos) - tokens.startAt(atPos);\n");
            sb.append("        if (diagLen < 1) diagLen = 1;\n");
            sb.append("        String foundText = String.valueOf(tokens.textAt(atPos));\n");
            sb.append("        diagnostics.add(Diagnostic.error(diagOffset, diagLen,\n");
            sb.append("            \"trailing input not consumed\", \"end of input\", foundText));\n");
            sb.append("        pos = tokens.nextNonTrivia(atPos + 1);\n");
            sb.append("    }\n\n");
            // Helpers used by the recovery loop. nextSyncToken walks forward from
            // {@code from} through content tokens (skipping trivia) returning the
            // index of the first sync-set token, or tokens.count() at EOF.
            sb.append("    private int nextSyncToken(int from) {\n");
            sb.append("        int[] sync = syncForRule(lastFailedRuleKind);\n");
            sb.append("        int i = from;\n");
            sb.append("        int n = tokens.count();\n");
            sb.append("        while (i < n) {\n");
            sb.append("            if (tokens.isTrivia(i)) { i++; continue; }\n");
            sb.append("            if (java.util.Arrays.binarySearch(sync, tokens.kindAt(i)) >= 0) {\n");
            sb.append("                return i;\n");
            sb.append("            }\n");
            sb.append("            i++;\n");
            sb.append("        }\n");
            sb.append("        return n;\n");
            sb.append("    }\n\n");
            // 0.6.1 — Item B — pick the SYNC array tied to the rule kind at the
            // deepest failure. Falls back to DEFAULT_SYNC if no rule-specific
            // sync set exists (the common case — most grammars use DEFAULT_SYNC
            // only). The switch is omitted entirely when perRuleSync is empty.
            sb.append("    private int[] syncForRule(int ruleKind) {\n");
            if ( !perRuleSync.isEmpty()) {
                sb.append("        switch (ruleKind) {\n");
                for ( var ruleName : perRuleSync.keySet()) {
                    sb.append("            case RULE_").append(ruleName)
                             .append("_KIND: return SYNC_")
                             .append(sanitize(ruleName))
                             .append(";\n");
                }
                sb.append("            default: return DEFAULT_SYNC;\n");
                sb.append("        }\n");
            } else
            {
                sb.append("        return DEFAULT_SYNC;\n");
            }
            sb.append("    }\n\n");
            // Token-advance helper: skips trivia after consuming a token.
            sb.append("    private void advance() {\n");
            sb.append("        pos = tokens.nextNonTrivia(pos + 1);\n");
            sb.append("    }\n\n");
            // Lookahead helper: kind at pos, or -1 at end-of-stream.
            sb.append("    private int peek() {\n");
            sb.append("        return pos < tokens.count() ? tokens.kindAt(pos) : -1;\n");
            sb.append("    }\n\n");
            // Phase 0.6.0-perf — record the furthest-failure point and return
            // false. PEG convention: track the most-distant failure offset since
            // alternatives explored beyond an earlier failure usually yield more
            // useful diagnostics. Replaces the old throwing error() helper.
            sb.append("    private boolean fail(String expectedText, int ruleKind) {\n");
            sb.append("        int offset = pos < tokens.count() ? tokens.startAt(pos) : tokens.input().length();\n");
            sb.append("        if (offset >= errorPos) {\n");
            sb.append("            errorPos = offset;\n");
            sb.append("            expected = expectedText;\n");
            sb.append("            found = peek();\n");
            sb.append("            lastFailedRuleKind = ruleKind;\n");
            sb.append("        }\n");
            sb.append("        return false;\n");
            sb.append("    }\n\n");
            // Per-rule methods.
            for ( var i = 0; i < parserRules.size(); i++) {
                sb.append(bodies.get(i));
                sb.append("\n");
            }
            sb.append("}\n");
            return Result.success(new GeneratedParser(packageName, className, sb.toString()));
        }

        private Result<String> renderRuleBody(Rule rule) {
            var sb = new StringBuilder(512);
            // Phase 0.6.0-perf — rule methods return boolean. The CST node is
            // opened up-front; on failure the saved state is restored (which
            // truncates the CST back, dropping {@code self} as well). Callers
            // do not need the node index, only success/failure.
            sb.append("    private boolean parse").append(rule.name())
                     .append("(int parent) {\n");
            // pos at entry must be a non-trivia token (callers ensure this; constructor
            // also seeds pos that way). The first-token of the new node is the current
            // pos. If the rule body matches zero tokens, lastToken stays = firstToken
            // (degenerate), which the builder accepts.
            sb.append("        int firstTok = pos;\n");
            sb.append("        int savedPos = pos;\n");
            sb.append("        int savedNodes = cst.currentNodeCount();\n");
            sb.append("        int self = cst.beginNode(RULE_").append(rule.name())
                     .append("_KIND, firstTok, parent);\n");
            var ctx = new EmitContext(rule.name(), 1, sb);
            var bodyResult = emitExpression(rule.expression(), ctx);
            if ( !bodyResult.isSuccess()) {
            return bodyResult.map(__ -> "");}
            // lastToken = pos - 1 if any token consumed, else firstTok (zero-width match).
            sb.append("        int lastTok = pos > firstTok ? pos - 1 : firstTok;\n");
            sb.append("        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;\n");
            sb.append("        if (lastTok < firstTok) lastTok = firstTok;\n");
            sb.append("        cst.endNode(self, lastTok);\n");
            sb.append("        return true;\n");
            sb.append("    }\n");
            return Result.success(sb.toString());
        }

        /**
         * Emit code for one expression node into {@code ctx.sb}. The emitted code
         * advances {@code pos} on match; on a mandatory-leaf failure it calls
         * {@code fail("...")} (which records the furthest-failure context) and
         * then dispatches {@code ctx.failAction} — typically {@code break;} to
         * exit the immediately-enclosing {@code do { ... } while (false)} body,
         * or {@code pos = savedPos; cst.truncate(savedNodes); return false;} at
         * the rule-body root.
         */
        private Result<Unit> emitExpression(Expression expr, EmitContext ctx) {
            return switch (expr) {case Expression.Reference ref -> emitReference(ref, ctx);case Expression.Literal lit -> emitLiteral(lit,
                                                                                                                                      ctx);case Expression.Sequence seq -> emitSequence(seq,
                                                                                                                                                                                        ctx);case Expression.Choice ch -> emitChoice(ch,
                                                                                                                                                                                                                                     ctx);case Expression.ZeroOrMore zom -> emitZeroOrMore(zom.expression(),
                                                                                                                                                                                                                                                                                           ctx);case Expression.OneOrMore oom -> emitOneOrMore(oom.expression(),
                                                                                                                                                                                                                                                                                                                                               ctx);case Expression.Optional opt -> emitOptional(opt.expression(),
                                                                                                                                                                                                                                                                                                                                                                                                 ctx);case Expression.Repetition rep -> emitRepetition(rep,
                                                                                                                                                                                                                                                                                                                                                                                                                                                       ctx);case Expression.And a -> isCharLevelOnly(a.expression())
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    ? emitParseTimeNoop(ctx,
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        "and-predicate over char-level expression — handled by lexer")
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    : emitAnd(a.expression(),
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              ctx);case Expression.Not n -> isCharLevelOnly(n.expression())
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           ? emitParseTimeNoop(ctx,
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               "not-predicate over char-level expression — handled by lexer")
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           : emitNot(n.expression(),
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     ctx);case Expression.TokenBoundary tb -> emitExpression(tb.expression(),
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             ctx);case Expression.Ignore ig -> emitExpression(ig.expression(),
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              ctx);case Expression.Capture cap -> emitExpression(cap.expression(),
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 ctx);case Expression.CaptureScope cs -> emitExpression(cs.expression(),
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        ctx);case Expression.Group g -> emitExpression(g.expression(),
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       ctx);case Expression.Cut __ -> emitCut(ctx);case Expression.Any __ -> emitAnyToken(ctx);case Expression.CharClass cc -> emitParseTimeNoop(ctx,
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 "char-class '" + cc.pattern() + "' inside parser rule — handled by lexer (Phase B.3 no-op)");case Expression.BackReference br -> emitParseTimeNoop(ctx,
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    "BackReference '" + br.name() + "' (Phase B.3 no-op)");case Expression.Dictionary __ -> emitParseTimeNoop(ctx,
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              "Dictionary (Phase B.3 no-op)");};
        }

        private Result<Unit> emitReference(Expression.Reference ref, EmitContext ctx) {
            var referenced = ruleMap.get(ref.ruleName());
            if ( referenced == null) {
            return new ParserGenerationError.UnknownReference(ctx.ruleName, ref.ruleName()).result();}
            var refKind = classification.kinds().get(ref.ruleName());
            if ( refKind == RuleKind.PARSER || refKind == RuleKind.MIXED) {
                // Phase B.5 — a MIXED rule whose body simplifies to literals is also
                // aliased. Prefer the alias path (one token of any matching kind) over
                // recursing into a parser method that would emit char-level no-ops.
                var aliasKindsForMixed = kinds.ruleNameToAliasKinds().get(ref.ruleName());
                if ( aliasKindsForMixed != null && aliasKindsForMixed.length > 0) {
                    emitAliasMatch(ref.ruleName(), aliasKindsForMixed, ctx);
                    return Result.unitResult();
                }
                ctx.sb.append(indent(ctx.depth)).append("if (!parse")
                             .append(ref.ruleName())
                             .append("(self)) { ")
                             .append(ctx.failAction)
                             .append(" }\n");
                return Result.unitResult();
            }
            // Phase B.5 — LEXER rule that aliases to a set of inline literals.
            // Accept any of the alias kinds; no separate DFA accept state exists.
            var aliasKinds = kinds.ruleNameToAliasKinds().get(ref.ruleName());
            if ( aliasKinds != null && aliasKinds.length > 0) {
                emitAliasMatch(ref.ruleName(), aliasKinds, ctx);
                return Result.unitResult();
            }
            // LEXER reference — consume one token of the matching kind.
            var kind = kinds.ruleNameToKind().get(ref.ruleName());
            if ( kind == null) {
            // Rule was demoted to LEXER but DFA didn't pick it up (e.g. skipped).
            // Fall back to ANY_CHAR or report.
            return new ParserGenerationError.UnknownReference(ctx.ruleName, ref.ruleName()).result();}
            usedTokenKinds.add(kind);
            // Phase 0.6.0 — identifier fallback. If the referenced rule is a
            // skip-prefix rule (e.g. {@code Identifier <- !Keyword [a-zA-Z_$]...}),
            // accept either its dedicated kind OR any identifier-shaped inline
            // literal / *KW kind that is NOT in the hard-keyword set. This
            // recovers the 0.5.x behavior where contextual keywords (record,
            // sealed, module, open, etc.) fall through to Identifier via PEG
            // ordered choice.
            var fallback = kinds.identifierFallbackKinds().get(ref.ruleName());
            if ( fallback != null && fallback.length > 0) {
                emitIdentifierFallback(ref.ruleName(), kind, fallback, ctx);
                return Result.unitResult();
            }
            var kindConst = "KIND_" + sanitize(kinds.kindNameTable() [kind]);
            ctx.sb.append(indent(ctx.depth)).append("if (peek() != ")
                         .append(kindConst)
                         .append(") { fail(\"")
                         .append(escapeJavaString(ref.ruleName()))
                         .append("\", ")
                         .append(ruleKindConst(ctx))
                         .append("); ")
                         .append(ctx.failAction)
                         .append(" }\n");
            ctx.sb.append(indent(ctx.depth)).append("advance();\n");
            return Result.unitResult();
        }

        /**
         * 0.6.1 — Item B — the RULE_<ctx.ruleName>_KIND constant string, used as
         * the rule-kind argument to emitted {@code fail(..., kind)} calls so the
         * generated parser can route to the correct {@code SYNC_<RuleName>} array
         * at recovery time.
         */
        private static String ruleKindConst(EmitContext ctx) {
            return "RULE_" + ctx.ruleName + "_KIND";
        }

        /**
         * Phase 0.6.0 — emit a guard that accepts the identifier's dedicated kind
         * OR any of the fallback kinds (contextual keywords / non-hard-keyword
         * inline literals). For small fallback sets the check is inlined as a
         * short OR-chain; larger sets use {@code Arrays.binarySearch} against a
         * sorted constant array {@code IDFALL_<RuleName>}.
         */
        private void emitIdentifierFallback(String ruleName, int idKind, int[] fallback, EmitContext ctx) {
            for ( var k : fallback) {
            usedTokenKinds.add(k);}
            var indent = indent(ctx.depth);
            var idKindConst = "KIND_" + sanitize(kinds.kindNameTable() [idKind]);
            // The id rule's own kind plus each fallback kind. For ≤4 total
            // alternatives we emit linear OR; otherwise binary search.
            int total = 1 + fallback.length;
            if ( total <= 4) {
                var buf = new StringBuilder();
                buf.append(indent).append("{ int __k = peek(); if (__k != ").append(idKindConst);
                for ( var k : fallback) {
                buf.append(" && __k != KIND_").append(sanitize(kinds.kindNameTable() [k]));}
                buf.append(") { fail(\"").append(escapeJavaString(ruleName))
                          .append("\", ")
                          .append(ruleKindConst(ctx))
                          .append("); ")
                          .append(ctx.failAction)
                          .append(" } }\n");
                ctx.sb.append(buf);
            } else
            {
                idFallbackArrays.put(ruleName, mergeAndSort(idKind, fallback));
                ctx.sb.append(indent).append("if (java.util.Arrays.binarySearch(IDFALL_")
                              .append(sanitize(ruleName))
                              .append(", peek()) < 0) { fail(\"")
                              .append(escapeJavaString(ruleName))
                              .append("\", ")
                              .append(ruleKindConst(ctx))
                              .append("); ")
                              .append(ctx.failAction)
                              .append(" }\n");
            }
            ctx.sb.append(indent).append("advance();\n");
        }

        /** Merge {@code idKind} into the sorted {@code fallback} array and re-sort. */
        private static int[] mergeAndSort(int idKind, int[] fallback) {
            var merged = new int[fallback.length + 1];
            System.arraycopy(fallback, 0, merged, 0, fallback.length);
            merged[fallback.length] = idKind;
            java.util.Arrays.sort(merged);
            // Dedupe in-place (idKind might already be present, shouldn't be but
            // be defensive — fallback can't overlap idKind by construction since
            // identifier rules aren't aliased, but be safe).
            int w = 0;
            for ( int i = 0; i < merged.length; i++) {
            if ( w == 0 || merged[w - 1] != merged[i]) {
                merged[w++] = merged[i];
            }}
            if ( w == merged.length) {
            return merged;}
            return java.util.Arrays.copyOf(merged, w);
        }

        /**
         * Phase B.5 — emit a guard that accepts any token kind in the alias set.
         * For ≤4 kinds we emit a linear OR-chain (cheap, branch-predictor friendly);
         * for larger sets we emit an {@code Arrays.binarySearch} call against a
         * sorted constant array {@code ALIAS_<RuleName>}.
         */
        private void emitAliasMatch(String ruleName, int[] aliasKinds, EmitContext ctx) {
            for ( var k : aliasKinds) {
            usedTokenKinds.add(k);}
            var indent = indent(ctx.depth);
            if ( aliasKinds.length <= 4) {
                var sb = new StringBuilder();
                sb.append(indent).append("{ int __k = peek(); if (");
                for ( int i = 0; i < aliasKinds.length; i++) {
                    if ( i > 0) {
                    sb.append(" && ");}
                    sb.append("__k != KIND_").append(sanitize(kinds.kindNameTable() [aliasKinds[i]]));
                }
                sb.append(") { fail(\"").append(escapeJavaString(ruleName))
                         .append("\", ")
                         .append(ruleKindConst(ctx))
                         .append("); ")
                         .append(ctx.failAction)
                         .append(" } }\n");
                ctx.sb.append(sb);
            } else



            {
                aliasArrays.add(ruleName);
                ctx.sb.append(indent).append("if (java.util.Arrays.binarySearch(ALIAS_")
                             .append(sanitize(ruleName))
                             .append(", peek()) < 0) { fail(\"")
                             .append(escapeJavaString(ruleName))
                             .append("\", ")
                             .append(ruleKindConst(ctx))
                             .append("); ")
                             .append(ctx.failAction)
                             .append(" }\n");
            }
            ctx.sb.append(indent).append("advance();\n");
        }

        private Result<Unit> emitLiteral(Expression.Literal lit, EmitContext ctx) {
            var key = lit.text() + (lit.caseInsensitive()
                                    ? "/i"
                                    : "/cs");
            var kind = kinds.inlineLiteralToKind().get(key);
            if ( kind == null) {
            return new ParserGenerationError.UnknownLiteral(ctx.ruleName, lit.text()).result();}
            usedTokenKinds.add(kind);
            var kindConst = "KIND_" + sanitize(kinds.kindNameTable() [kind]);
            ctx.sb.append(indent(ctx.depth)).append("if (peek() != ")
                         .append(kindConst)
                         .append(") { fail(\"'")
                         .append(escapeJavaString(lit.text()))
                         .append("'\", ")
                         .append(ruleKindConst(ctx))
                         .append("); ")
                         .append(ctx.failAction)
                         .append(" }\n");
            ctx.sb.append(indent(ctx.depth)).append("advance();\n");
            return Result.unitResult();
        }

        private Result<Unit> emitAnyToken(EmitContext ctx) {
            ctx.sb.append(indent(ctx.depth)).append("if (peek() < 0) { fail(\"<any token>\", ")
                         .append(ruleKindConst(ctx))
                         .append("); ")
                         .append(ctx.failAction)
                         .append(" }\n");
            ctx.sb.append(indent(ctx.depth)).append("advance();\n");
            return Result.unitResult();
        }

        private Result<Unit> emitSequence(Expression.Sequence seq, EmitContext ctx) {
            for ( var element : seq.elements()) {
                var r = emitExpression(element, ctx);
                if ( !r.isSuccess()) {
                return r;}
            }
            return Result.unitResult();
        }

        private Result<Unit> emitChoice(Expression.Choice ch, EmitContext ctx) {
            // Phase 0.6.0-perf — boolean ordered-alternative backtracking. Each
            // alternative runs inside its own do { ... } while (false) loop;
            // leaf failures inside the alt body emit "break;" to exit the loop,
            // at which point we restore saved state and try the next alt. The
            // outer loop short-circuits once any alt has matched.
            //
            // Cut support: each Choice declares a cutHit_<label> flag whose
            // name is propagated into each alternative's emit context. When
            // an Expression.Cut is encountered inside an alternative, the
            // flag is set. After the alternative's do/while, if the alt
            // failed AND the cut was hit, we skip the remaining alternatives
            // and dispatch the parent's failAction (per PEG cut semantics:
            // commit to this alternative; failure here fails the whole Choice).
            var label = "alt_" + ctx.nextLabelId();
            var cutFlag = "cutHit_" + label;
            ctx.sb.append(indent(ctx.depth)).append("// choice: ")
                         .append(label)
                         .append("\n");
            ctx.sb.append(indent(ctx.depth)).append("{\n");
            var inner = ctx.indented();
            var alternatives = ch.alternatives();
            ctx.sb.append(indent(inner.depth)).append("int savedPos_")
                         .append(label)
                         .append(" = pos;\n");
            ctx.sb.append(indent(inner.depth)).append("int savedNodes_")
                         .append(label)
                         .append(" = cst.currentNodeCount();\n");
            ctx.sb.append(indent(inner.depth)).append("boolean matched_")
                         .append(label)
                         .append(" = false;\n");
            ctx.sb.append(indent(inner.depth)).append("boolean ")
                         .append(cutFlag)
                         .append(" = false;\n");
            for ( var i = 0; i < alternatives.size(); i++) {
                ctx.sb.append(indent(inner.depth)).append("if (!matched_")
                             .append(label)
                             .append(" && !")
                             .append(cutFlag)
                             .append(") {\n");
                var altCtx = inner.indented();
                ctx.sb.append(indent(altCtx.depth)).append("do {\n");
                var altBody = altCtx.indentedWithFailAction(EmitContext.BREAK_FAIL_ACTION).withCutFlag(cutFlag);
                var r = emitExpression(alternatives.get(i), altBody);
                if ( !r.isSuccess()) {
                return r;}
                ctx.sb.append(indent(altBody.depth)).append("matched_")
                             .append(label)
                             .append(" = true;\n");
                ctx.sb.append(indent(altCtx.depth)).append("} while (false);\n");
                ctx.sb.append(indent(altCtx.depth)).append("if (!matched_")
                             .append(label)
                             .append(") {\n");
                ctx.sb.append(indent(altCtx.depth + 1)).append("pos = savedPos_")
                             .append(label)
                             .append(";\n");
                ctx.sb.append(indent(altCtx.depth + 1)).append("cst.truncate(savedNodes_")
                             .append(label)
                             .append(");\n");
                ctx.sb.append(indent(altCtx.depth)).append("}\n");
                ctx.sb.append(indent(inner.depth)).append("}\n");
            }
            // All alternatives failed — record a furthest-failure entry then
            // dispatch the parent's failAction (e.g. "break;" for nested
            // contexts, or "return false;" for the rule body). fail() already
            // tracks furthest failure across nested calls. Also fires when
            // cut was hit but the committed alternative failed.
            ctx.sb.append(indent(inner.depth)).append("if (!matched_")
                         .append(label)
                         .append(") { fail(\"<choice>\", ")
                         .append(ruleKindConst(ctx))
                         .append("); ")
                         .append(ctx.failAction)
                         .append(" }\n");
            ctx.sb.append(indent(ctx.depth)).append("}\n");
            return Result.unitResult();
        }

        /**
         * Emit code for the Cut operator. Cut commits the parser to the current
         * Choice alternative: subsequent failures in this alternative cause the
         * enclosing Choice to fail, rather than backtracking to try the next
         * alternative. We implement this by setting the enclosing Choice's
         * cutFlag to true; emitChoice's per-alternative gate then short-circuits
         * the remaining alternatives.
         *
         * <p>Outside any Choice (no cutFlag in scope), Cut is a no-op — there's
         * no alternation to suppress. This matches the spec's "enclosing Choice"
         * scoping and is consistent with PEG convention.
         */
        private Result<Unit> emitCut(EmitContext ctx) {
            if ( ctx.cutFlag != null) {
            ctx.sb.append(indent(ctx.depth)).append(ctx.cutFlag)
                         .append(" = true;\n");} else
            {
            ctx.sb.append(indent(ctx.depth)).append("// cut: no enclosing Choice — no-op\n");}
            return Result.unitResult();
        }

        private Result<Unit> emitZeroOrMore(Expression inner, EmitContext ctx) {
            // Phase 0.6.0-perf — boolean iteration. Each attempt runs in its own
            // do { ... } while (false) loop; on inner failure we restore saved
            // state and break the outer "while (true)" loop. Successful iteration
            // is signalled by setting an "iterOk_*" flag before the do/while exit;
            // a zero-width successful match also breaks (else infinite loop).
            var label = "rep_" + ctx.nextLabelId();
            ctx.sb.append(indent(ctx.depth)).append("// zero-or-more: ")
                         .append(label)
                         .append("\n");
            ctx.sb.append(indent(ctx.depth)).append("while (true) {\n");
            var body = ctx.indented();
            ctx.sb.append(indent(body.depth)).append("int savedPos_")
                         .append(label)
                         .append(" = pos;\n");
            ctx.sb.append(indent(body.depth)).append("int savedNodes_")
                         .append(label)
                         .append(" = cst.currentNodeCount();\n");
            ctx.sb.append(indent(body.depth)).append("boolean iterOk_")
                         .append(label)
                         .append(" = false;\n");
            ctx.sb.append(indent(body.depth)).append("do {\n");
            var inner2 = body.indentedWithFailAction(EmitContext.BREAK_FAIL_ACTION);
            var r = emitExpression(inner, inner2);
            if ( !r.isSuccess()) {
            return r;}
            ctx.sb.append(indent(inner2.depth)).append("iterOk_")
                         .append(label)
                         .append(" = true;\n");
            ctx.sb.append(indent(body.depth)).append("} while (false);\n");
            ctx.sb.append(indent(body.depth)).append("if (!iterOk_")
                         .append(label)
                         .append(") {\n");
            ctx.sb.append(indent(inner2.depth)).append("pos = savedPos_")
                         .append(label)
                         .append(";\n");
            ctx.sb.append(indent(inner2.depth)).append("cst.truncate(savedNodes_")
                         .append(label)
                         .append(");\n");
            ctx.sb.append(indent(inner2.depth)).append("break;\n");
            ctx.sb.append(indent(body.depth)).append("}\n");
            ctx.sb.append(indent(body.depth)).append("if (pos == savedPos_")
                         .append(label)
                         .append(") break; // guard against infinite loops on zero-width matches\n");
            ctx.sb.append(indent(ctx.depth)).append("}\n");
            return Result.unitResult();
        }

        private Result<Unit> emitOneOrMore(Expression inner, EmitContext ctx) {
            // One mandatory iteration, then zero-or-more.
            var r = emitExpression(inner, ctx);
            if ( !r.isSuccess()) {
            return r;}
            return emitZeroOrMore(inner, ctx);
        }

        private Result<Unit> emitOptional(Expression inner, EmitContext ctx) {
            // Phase 0.6.0-perf — boolean optional. Failure inside the inner body
            // simply breaks out of the do/while and we restore saved state. Optional
            // always "succeeds" from the caller's perspective.
            var label = "opt_" + ctx.nextLabelId();
            ctx.sb.append(indent(ctx.depth)).append("// optional: ")
                         .append(label)
                         .append("\n");
            ctx.sb.append(indent(ctx.depth)).append("{\n");
            var body = ctx.indented();
            ctx.sb.append(indent(body.depth)).append("int savedPos_")
                         .append(label)
                         .append(" = pos;\n");
            ctx.sb.append(indent(body.depth)).append("int savedNodes_")
                         .append(label)
                         .append(" = cst.currentNodeCount();\n");
            ctx.sb.append(indent(body.depth)).append("boolean optOk_")
                         .append(label)
                         .append(" = false;\n");
            ctx.sb.append(indent(body.depth)).append("do {\n");
            var inner2 = body.indentedWithFailAction(EmitContext.BREAK_FAIL_ACTION);
            var r = emitExpression(inner, inner2);
            if ( !r.isSuccess()) {
            return r;}
            ctx.sb.append(indent(inner2.depth)).append("optOk_")
                         .append(label)
                         .append(" = true;\n");
            ctx.sb.append(indent(body.depth)).append("} while (false);\n");
            ctx.sb.append(indent(body.depth)).append("if (!optOk_")
                         .append(label)
                         .append(") {\n");
            ctx.sb.append(indent(inner2.depth)).append("pos = savedPos_")
                         .append(label)
                         .append(";\n");
            ctx.sb.append(indent(inner2.depth)).append("cst.truncate(savedNodes_")
                         .append(label)
                         .append(");\n");
            ctx.sb.append(indent(body.depth)).append("}\n");
            ctx.sb.append(indent(ctx.depth)).append("}\n");
            return Result.unitResult();
        }

        private Result<Unit> emitRepetition(Expression.Repetition rep, EmitContext ctx) {
            // min copies, then either ZeroOrMore (max < 0) or (max - min) optionals.
            for ( var i = 0; i < rep.min(); i++) {
                var r = emitExpression(rep.expression(), ctx);
                if ( !r.isSuccess()) {
                return r;}
            }
            var maxOpt = rep.max();
            if ( maxOpt.isEmpty()) {
            return emitZeroOrMore(rep.expression(), ctx);}
            var max = maxOpt.unwrap();
            for ( var i = rep.min(); i < max; i++) {
                var r = emitOptional(rep.expression(), ctx);
                if ( !r.isSuccess()) {
                return r;}
            }
            return Result.unitResult();
        }

        private Result<Unit> emitAnd(Expression inner, EmitContext ctx) {
            // Phase 0.6.0-perf — and-predicate. Inner runs in a do/while; on
            // failure it breaks out and we propagate the parent's failAction
            // (and-predicate fails if its inner fails). On success we restore
            // pos/CST (predicate is non-consuming) and continue.
            var label = "and_" + ctx.nextLabelId();
            ctx.sb.append(indent(ctx.depth)).append("// and-predicate: ")
                         .append(label)
                         .append("\n");
            ctx.sb.append(indent(ctx.depth)).append("{\n");
            var body = ctx.indented();
            ctx.sb.append(indent(body.depth)).append("int savedPos_")
                         .append(label)
                         .append(" = pos;\n");
            ctx.sb.append(indent(body.depth)).append("int savedNodes_")
                         .append(label)
                         .append(" = cst.currentNodeCount();\n");
            ctx.sb.append(indent(body.depth)).append("boolean andOk_")
                         .append(label)
                         .append(" = false;\n");
            ctx.sb.append(indent(body.depth)).append("do {\n");
            var inner2 = body.indentedWithFailAction(EmitContext.BREAK_FAIL_ACTION);
            var r = emitExpression(inner, inner2);
            if ( !r.isSuccess()) {
            return r;}
            ctx.sb.append(indent(inner2.depth)).append("andOk_")
                         .append(label)
                         .append(" = true;\n");
            ctx.sb.append(indent(body.depth)).append("} while (false);\n");
            // Always restore saved state: and-predicate is non-consuming.
            ctx.sb.append(indent(body.depth)).append("pos = savedPos_")
                         .append(label)
                         .append(";\n");
            ctx.sb.append(indent(body.depth)).append("cst.truncate(savedNodes_")
                         .append(label)
                         .append(");\n");
            ctx.sb.append(indent(body.depth)).append("if (!andOk_")
                         .append(label)
                         .append(") { fail(\"&<predicate>\", ")
                         .append(ruleKindConst(ctx))
                         .append("); ")
                         .append(ctx.failAction)
                         .append(" }\n");
            ctx.sb.append(indent(ctx.depth)).append("}\n");
            return Result.unitResult();
        }

        private Result<Unit> emitNot(Expression inner, EmitContext ctx) {
            // Phase 0.6.0-perf — not-predicate. Inner runs in a do/while; if it
            // matches we set notMatched=true and bail out via the parent's
            // failAction. If it fails (breaks the do/while) the predicate
            // succeeds. Always restore saved state: not-predicate is
            // non-consuming.
            var label = "not_" + ctx.nextLabelId();
            ctx.sb.append(indent(ctx.depth)).append("// not-predicate: ")
                         .append(label)
                         .append("\n");
            ctx.sb.append(indent(ctx.depth)).append("{\n");
            var body = ctx.indented();
            ctx.sb.append(indent(body.depth)).append("int savedPos_")
                         .append(label)
                         .append(" = pos;\n");
            ctx.sb.append(indent(body.depth)).append("int savedNodes_")
                         .append(label)
                         .append(" = cst.currentNodeCount();\n");
            ctx.sb.append(indent(body.depth)).append("boolean notMatched_")
                         .append(label)
                         .append(" = false;\n");
            ctx.sb.append(indent(body.depth)).append("do {\n");
            var inner2 = body.indentedWithFailAction(EmitContext.BREAK_FAIL_ACTION);
            var r = emitExpression(inner, inner2);
            if ( !r.isSuccess()) {
            return r;}
            ctx.sb.append(indent(inner2.depth)).append("notMatched_")
                         .append(label)
                         .append(" = true;\n");
            ctx.sb.append(indent(body.depth)).append("} while (false);\n");
            ctx.sb.append(indent(body.depth)).append("pos = savedPos_")
                         .append(label)
                         .append(";\n");
            ctx.sb.append(indent(body.depth)).append("cst.truncate(savedNodes_")
                         .append(label)
                         .append(");\n");
            ctx.sb.append(indent(body.depth)).append("if (notMatched_")
                         .append(label)
                         .append(") { fail(\"!<predicate>\", ")
                         .append(ruleKindConst(ctx))
                         .append("); ")
                         .append(ctx.failAction)
                         .append(" }\n");
            ctx.sb.append(indent(ctx.depth)).append("}\n");
            return Result.unitResult();
        }

        private static <T> Result<T> unsupported(String ruleName, String kind, String detail) {
            return new ParserGenerationError.UnsupportedExpression(ruleName, kind, detail).result();
        }

        /**
         * Return true when {@code expr} contains only char-level constructs
         * (CharClass, Any, possibly wrapped in Group/TokenBoundary/etc., or
         * combined via Sequence/Choice/repetition). Used to decide whether an
         * And/Not predicate is char-level — in which case it's elided since the
         * lexer already disambiguated.
         */
        private static boolean isCharLevelOnly(Expression expr) {
            return switch (expr) {case Expression.CharClass __ -> true;case Expression.Any __ -> true;case Expression.Sequence seq -> seq.elements().stream()
                                                                                                                                                  .allMatch(Renderer::isCharLevelOnly);case Expression.Choice ch -> ch.alternatives().stream()
                                                                                                                                                                                                                                   .allMatch(Renderer::isCharLevelOnly);case Expression.ZeroOrMore z -> isCharLevelOnly(z.expression());case Expression.OneOrMore o -> isCharLevelOnly(o.expression());case Expression.Optional o -> isCharLevelOnly(o.expression());case Expression.Repetition r -> isCharLevelOnly(r.expression());case Expression.And a -> isCharLevelOnly(a.expression());case Expression.Not n -> isCharLevelOnly(n.expression());case Expression.TokenBoundary tb -> isCharLevelOnly(tb.expression());case Expression.Ignore ig -> isCharLevelOnly(ig.expression());case Expression.Capture c -> isCharLevelOnly(c.expression());case Expression.CaptureScope cs -> isCharLevelOnly(cs.expression());case Expression.Group g -> isCharLevelOnly(g.expression());case Expression.Cut __ -> true;default -> false;};
        }

        /**
         * Phase B.3 no-op: emit a comment explaining the elided behaviour.
         * MIXED-rule char-level constructs (CharClass, BackReference, Dictionary)
         * inside parser rules typically guard tokens that the lexer has already
         * disambiguated; eliding them at parse time matches the token-level
         * semantics. Phase B.4+ may add per-rule char-level fallbacks.
         */
        private static Result<Unit> emitParseTimeNoop(EmitContext ctx, String detail) {
            ctx.sb.append(indent(ctx.depth)).append("// no-op: ")
                         .append(detail)
                         .append("\n");
            return Result.unitResult();
        }
    }

    /**
     * Mutable per-emit context: depth is fixed per scope; labelCounter is shared
     * across the rule body. The {@code failAction} string is the Java statement
     * that a leaf-level failure should emit to bail out of the enclosing
     * backtrackable scope (rule body, choice alt, ZeroOrMore body, Optional
     * body, And body, Not body). Typically {@code "break;"} when wrapped in a
     * {@code do { ... } while (false);} loop.
     */
    private static final class EmitContext {
        // Top-level fail action used at the rule body root: restore the rule's
        // saved state and return false from parseFoo.
        static final String RULE_BODY_FAIL_ACTION = "pos = savedPos; cst.truncate(savedNodes); return false;";

        // Common fail action: break out of the immediately enclosing
        // do { ... } while (false) loop.
        static final String BREAK_FAIL_ACTION = "break;";

        final String ruleName;
        final int depth;
        final StringBuilder sb;
        final String failAction;

        /**
         * Java identifier of the boolean flag in the enclosing Choice's emitted
         * scope which {@link Renderer#emitCut} sets to {@code true}. {@code null}
         * means no enclosing Choice — Cut becomes a no-op. Inherited across
         * {@code indented()} / {@code indentedWithFailAction()} so Cut nested
         * inside Sequence / Optional / Predicate inside an alternative still
         * targets the alternative's enclosing Choice. Overridden by
         * {@code withCutFlag()} when emitting an inner alternative so a nested
         * Choice's Cut only affects the nested Choice.
         */
        final String cutFlag;
        private final int[] labelCounter;

        EmitContext(String ruleName, int depth, StringBuilder sb) {
            this(ruleName, depth, sb, new int[]{0}, RULE_BODY_FAIL_ACTION, null);
        }

        EmitContext(String ruleName,
                    int depth,
                    StringBuilder sb,
                    int[] labelCounter,
                    String failAction,
                    String cutFlag) {
            this.ruleName = ruleName;
            this.depth = depth;
            this.sb = sb;
            this.labelCounter = labelCounter;
            this.failAction = failAction;
            this.cutFlag = cutFlag;
        }

        EmitContext indented() {
            return new EmitContext(ruleName, depth + 1, sb, labelCounter, failAction, cutFlag);
        }

        EmitContext withFailAction(String newFailAction) {
            return new EmitContext(ruleName, depth, sb, labelCounter, newFailAction, cutFlag);
        }

        EmitContext indentedWithFailAction(String newFailAction) {
            return new EmitContext(ruleName, depth + 1, sb, labelCounter, newFailAction, cutFlag);
        }

        EmitContext withCutFlag(String newCutFlag) {
            return new EmitContext(ruleName, depth, sb, labelCounter, failAction, newCutFlag);
        }

        int nextLabelId() {
            var id = labelCounter[0];
            labelCounter[0] = id + 1;
            return id;
        }
    }

    private static List<Rule> collectParserRules(Grammar grammar, RuleClassifier.Classification classification) {
        var out = new ArrayList<Rule>();
        for ( var rule : grammar.rules()) {
            var k = classification.kinds().get(rule.name());
            if ( k == RuleKind.PARSER || k == RuleKind.MIXED) {
            out.add(rule);}
        }
        return out;
    }

    /**
     * Phase E.1 — public allocation helper. Assigns sequential indices starting
     * at 0 to every PARSER/MIXED rule in source order; the result aligns with
     * the {@code RULE_*_KIND} constants the generator emits and with the rule
     * positions in the parser's emitted {@code RULE_TABLE}. {@link
     * VisitorGenerator} calls this so visitor dispatch shares one source of
     * truth with the parser.
     */
    public static Map<String, Integer> allocateParserRuleKinds(Grammar grammar,
                                                               RuleClassifier.Classification classification) {
        var rules = collectParserRules(grammar, classification);
        var map = new LinkedHashMap<String, Integer>();
        for ( var i = 0; i < rules.size(); i++) {
        map.put(rules.get(i).name(),
                i);}
        return map;
    }

    private static String indent(int depth) {
        return "    ".repeat(depth + 1);
    }

    /**
     * Sanitize a kind name into a Java identifier suffix. The DFA builder already
     * generates valid identifiers for inline literals (INLINE_*) and rule names
     * are already valid identifiers; this function therefore just substitutes any
     * stray non-identifier character with underscore as a safety net.
     */
    private static String sanitize(String name) {
        var sb = new StringBuilder(name.length());
        for ( var i = 0; i < name.length(); i++) {
            var c = name.charAt(i);
            if ( Character.isJavaIdentifierPart(c)) {
            sb.append(c);} else
            {
            sb.append('_');}
        }
        // KIND_NAMES may contain entries starting with a digit (none observed but
        // be safe) — prepend underscore.
        if ( sb.length() > 0 && !Character.isJavaIdentifierStart(sb.charAt(0))) {
        sb.insert(0, '_');}
        return sb.toString().toUpperCase(Locale.ROOT);
    }

    private static String escapeJavaString(String s) {
        var out = new StringBuilder(s.length() + 4);
        for ( var i = 0; i < s.length(); i++) {
            var c = s.charAt(i);
            switch ( c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if ( c < 0x20 || c == 0x7f) {
                    out.append(String.format("\\u%04x", (int) c));} else
                    {
                    out.append(c);}
                }
            }
        }
        return out.toString();
    }

    private static boolean isValidIdentifier(String s) {
        if ( s == null || s.isEmpty()) {
        return false;}
        if ( !Character.isJavaIdentifierStart(s.charAt(0))) {
        return false;}
        for ( var i = 1; i < s.length(); i++) {
        if ( !Character.isJavaIdentifierPart(s.charAt(i))) {
        return false;}}
        return true;
    }

    private static boolean isValidQualifiedPackage(String s) {
        if ( s == null) {
        return false;}
        if ( s.isEmpty()) {
        return true;}
        for ( var part : s.split("\\.", - 1)) {
        if ( !isValidIdentifier(part)) {
        return false;}}
        return true;
    }
}
