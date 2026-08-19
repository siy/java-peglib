package org.pragmatica.peg.analyzer;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.grammar.GrammarParser;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for every {@link Finding} tag produced by {@link Analyzer}, plus an
 * end-to-end smoke check against the project's canonical {@code java25.peg}.
 */
class AnalyzerTest {

    private static AnalyzerReport analyze(String grammarText) {
        // 0.4.0 — GrammarParser.parse(...) routes through Grammar.grammar(...)
        // so the returned grammar is already validated.
        var grammar = GrammarParser.parse(grammarText)
                                   .unwrap();
        return Analyzer.analyze(grammar);
    }

    @Nested
    class UnreachableRules {
        @Test
        void reportsRuleNotReachableFromStart() {
            var grammar = """
                    Start <- 'a' B
                    B <- 'b'
                    Orphan <- 'orphan'
                    """;
            var report = analyze(grammar);
            assertThat(report.findings()).anyMatch(f ->
            "grammar.unreachable-rule".equals(f.tag()) && "Orphan".equals(f.ruleName()));
            assertThat(report.findings()).noneMatch(f ->
            "grammar.unreachable-rule".equals(f.tag()) && "Start".equals(f.ruleName()));
            assertThat(report.findings()).noneMatch(f ->
            "grammar.unreachable-rule".equals(f.tag()) && "B".equals(f.ruleName()));
        }

        @Test
        void transitivelyReachableRulesNotFlagged() {
            var grammar = """
                    Start <- A
                    A <- B
                    B <- 'b'
                    """;
            var report = analyze(grammar);
            assertThat(report.findings()).noneMatch(f ->
            "grammar.unreachable-rule".equals(f.tag()));
        }
    }

    @Nested
    class AmbiguousChoices {
        @Test
        void flagsTwoLiteralsWithSameFirstChar() {
            var grammar = """
                    Start <- 'foo' / 'fib'
                    """;
            var report = analyze(grammar);
            assertThat(report.findings()).anyMatch(f ->
            "grammar.ambiguous-choice".equals(f.tag()) && "Start".equals(f.ruleName()));
        }

        @Test
        void doesNotFlagDistinctFirstChars() {
            var grammar = """
                    Start <- 'foo' / 'bar'
                    """;
            var report = analyze(grammar);
            assertThat(report.findings()).noneMatch(f ->
            "grammar.ambiguous-choice".equals(f.tag()));
        }

        @Test
        void doesNotFlagNonLiteralPrefixedAlternatives() {
            var grammar = """
                    Start <- Ident / 'x'
                    Ident <- [a-z]+
                    """;
            var report = analyze(grammar);
            assertThat(report.findings()).noneMatch(f ->
            "grammar.ambiguous-choice".equals(f.tag()));
        }
    }

    @Nested
    class NullableRules {
        @Test
        void infoOnPlainNullableRule() {
            var grammar = """
                    Start <- 'x' Opt 'y'
                    Opt <- 'a'?
                    """;
            var report = analyze(grammar);
            assertThat(report.findings()).anyMatch(f ->
            "grammar.nullable-rule".equals(f.tag())
            && "Opt".equals(f.ruleName())
            && f.severity() == Finding.Severity.INFO);
        }

        @Test
        void warningOnNullableLeftRecursiveRule() {
            // Direct left recursion: Expr references itself as the first non-predicate element
            // of an alternative. Expr is also nullable (via 'n'? alternative), so the finding
            // must be WARNING severity instead of INFO.
            var grammar = """
                    Expr <- Expr '+' Term / 'n'?
                    Term <- 'n'
                    """;
            var report = analyze(grammar);
            assertThat(report.findings()).anyMatch(f ->
            "grammar.nullable-rule".equals(f.tag())
            && "Expr".equals(f.ruleName())
            && f.severity() == Finding.Severity.WARNING);
        }

        @Test
        void nonNullableRuleNotFlagged() {
            var grammar = """
                    Start <- 'x'
                    """;
            var report = analyze(grammar);
            assertThat(report.findings()).noneMatch(f ->
            "grammar.nullable-rule".equals(f.tag()) && "Start".equals(f.ruleName()));
        }
    }

    @Nested
    class DuplicateLiterals {
        @Test
        void reportsExactDuplicateInChoice() {
            var grammar = """
                    Start <- 'foo' / 'foo'
                    """;
            var report = analyze(grammar);
            assertThat(report.findings()).anyMatch(f ->
            "grammar.duplicate-literal".equals(f.tag())
            && f.severity() == Finding.Severity.ERROR
            && f.message()
                .contains("foo"));
        }

        @Test
        void doesNotReportDistinctLiterals() {
            var grammar = """
                    Start <- 'foo' / 'bar'
                    """;
            var report = analyze(grammar);
            assertThat(report.findings()).noneMatch(f ->
            "grammar.duplicate-literal".equals(f.tag()));
        }
    }

    @Nested
    class WhitespaceCycle {
        @Test
        void detectsDirectCycle() {
            var grammar = """
                    Start <- 'x'
                    %whitespace <- WS
                    WS <- WS / [ \\t]
                    """;
            var report = analyze(grammar);
            assertThat(report.findings()).anyMatch(f ->
            "grammar.whitespace-cycle".equals(f.tag())
            && f.severity() == Finding.Severity.ERROR);
        }

        @Test
        void noFalsePositiveOnAcyclicWhitespace() {
            var grammar = """
                    Start <- 'x'
                    %whitespace <- [ \\t\\n]*
                    """;
            var report = analyze(grammar);
            assertThat(report.findings()).noneMatch(f ->
            "grammar.whitespace-cycle".equals(f.tag()));
        }
    }

    @Nested
    class BackReferences {
        @Test
        void flagsRuleWithBackReference() {
            var grammar = """
                    Start <- $name< [a-z]+ > '=' $name
                    """;
            var report = analyze(grammar);
            assertThat(report.findings()).anyMatch(f ->
            "grammar.has-backreference".equals(f.tag())
            && "Start".equals(f.ruleName())
            && f.severity() == Finding.Severity.INFO);
        }

        @Test
        void doesNotFlagPlainRule() {
            var grammar = """
                    Start <- 'x'
                    """;
            var report = analyze(grammar);
            assertThat(report.findings()).noneMatch(f ->
            "grammar.has-backreference".equals(f.tag()));
        }
    }

    /**
     * 0.7.1 — both {@code %memo} failure modes are accepted by the front-end and
     * dropped at codegen, so the only symptom without a finding is that the
     * expected speedup silently fails to appear.
     */
    /**
     * 0.7.2 — directives the front-end accepts and the generator never reads. Silent
     * acceptance means the only symptom is that the declared behaviour never appears.
     */
    @Nested
    class InertDirectives {
        @Test
        void flagsWordDirective() {
            var grammar = """
                    %word <- [a-zA-Z0-9_]
                    Start <- 'a'
                    """;
            var report = analyze(grammar);
            assertThat(report.findings()).anyMatch(f ->
            "grammar.inert-directive".equals(f.tag())
            && f.message().contains("%word")
            && f.severity() == Finding.Severity.WARNING);
        }

        @Test
        void flagsRuleLevelTrailers() {
            var grammar = """
                    Start <- 'a' %expected "thing" %recover "}" %tag "my.tag"
                    """;
            var report = analyze(grammar);
            assertThat(report.findings()).anyMatch(f ->
            "grammar.inert-directive".equals(f.tag()) && f.message().contains("%expected"));
            assertThat(report.findings()).anyMatch(f ->
            "grammar.inert-directive".equals(f.tag()) && f.message().contains("%tag"));
            // %recover is the one trailer whose message carries extra guidance; without a
            // case for it that branch is never executed by any test.
            assertThat(report.findings()).anyMatch(f ->
            "grammar.inert-directive".equals(f.tag())
            && f.message().contains("%recover")
            && f.message().contains("grammar-level"));
        }

        @Test
        void doesNotFlagGrammarWithoutInertDirectives() {
            var grammar = """
                    Start <- 'x'
                    %whitespace <- [ ]*
                    """;
            var report = analyze(grammar);
            assertThat(report.findings()).noneMatch(f ->
            "grammar.inert-directive".equals(f.tag()));
        }
    }

    @Nested
    class MemoDirectives {
        @Test
        void flagsMemoOnUndefinedRule() {
            var grammar = """
                    Start <- Call+
                    Call <- Ident '(' Ident? ')'
                    Ident <- [a-z]+
                    %memo NoSuchRule
                    """;
            var report = analyze(grammar);
            assertThat(report.findings()).anyMatch(f ->
            "grammar.memo-unknown-rule".equals(f.tag())
            && "NoSuchRule".equals(f.ruleName())
            && f.severity() == Finding.Severity.WARNING);
        }

        @Test
        void flagsMemoOnLexerRule() {
            // Ident references only character classes, so RuleClassifier types it
            // LEXER and the generator never memoises it.
            var grammar = """
                    Start <- Call+
                    Call <- Ident '(' Ident? ')'
                    Ident <- [a-z]+
                    %memo Ident
                    """;
            var report = analyze(grammar);
            assertThat(report.findings()).anyMatch(f ->
            "grammar.memo-non-parser-rule".equals(f.tag())
            && "Ident".equals(f.ruleName())
            && f.severity() == Finding.Severity.WARNING);
        }

        @Test
        void doesNotFlagMemoOnParserRule() {
            var grammar = """
                    Start <- Call+
                    Call <- Ident '(' Args? ')'
                    Args <- Ident (',' Ident)*
                    Ident <- [a-z]+
                    %memo Args
                    """;
            var report = analyze(grammar);
            assertThat(report.findings()).noneMatch(f ->
            f.tag().startsWith("grammar.memo-"));
        }

        @Test
        void doesNotFlagGrammarWithoutMemo() {
            var grammar = """
                    Start <- 'x'
                    """;
            var report = analyze(grammar);
            assertThat(report.findings()).noneMatch(f ->
            f.tag().startsWith("grammar.memo-"));
        }
    }

    @Nested
    class ReportFormatting {
        @Test
        void hasErrorsReflectsErrorFindings() {
            var grammar = """
                    Start <- 'foo' / 'foo'
                    """;
            var report = analyze(grammar);
            assertThat(report.hasErrors()).isTrue();
        }

        @Test
        void rustStyleFormatterProducesExpectedStructure() {
            var grammar = """
                    Start <- 'foo' / 'foo'
                    """;
            var report = analyze(grammar);
            var out = report.formatRustStyle("test.peg");
            assertThat(out).contains("error[grammar.duplicate-literal]:");
            assertThat(out).contains("--> test.peg: Start");
            assertThat(out).contains("analyzer: 1 error");
        }
    }

    @Nested
    class Java25Smoke {
        @Test
        void java25PegHasZeroErrors() throws Exception {
            var text = Files.readString(Path.of("src/test/resources/java25.peg"));
            var report = analyze(text);
            assertThat(report.count(Finding.Severity.ERROR)).isZero();
        }
    }
}
