package org.pragmatica.peg.generator;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.tree.SourceLocation;
import org.pragmatica.peg.tree.SourceSpan;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Classification and grouping for the §7.1 character-dispatch optimization.
 * The analyzer is purely structural — these tests construct Expressions directly
 * rather than parsing grammar text.
 */
class ChoiceDispatchAnalyzerTest {

    private static final SourceSpan SPAN = new SourceSpan(
        new SourceLocation(1, 1, 0),
        new SourceLocation(1, 1, 0));

    private static Expression.Literal lit(String text, boolean ci) {
        return new Expression.Literal(SPAN, text, ci);
    }

    private static Expression.Literal lit(String text) {
        return lit(text, false);
    }

    private static Expression.Choice choice(Expression... alts) {
        return new Expression.Choice(SPAN, List.of(alts));
    }

    @Test
    void classify_allLiteralAlts_returnsEntriesInOrder() {
        var ch = choice(lit("if"), lit("else"), lit("while"));

        var result = ChoiceDispatchAnalyzer.classify(ch);

        assertThat(result).isPresent();
        var entries = result.orElseThrow();
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).firstChar().c()).isEqualTo('i');
        assertThat(entries.get(1).firstChar().c()).isEqualTo('e');
        assertThat(entries.get(2).firstChar().c()).isEqualTo('w');
        assertThat(entries.get(0).originalIndex()).isEqualTo(0);
        assertThat(entries.get(2).originalIndex()).isEqualTo(2);
    }

    @Test
    void classify_choiceWithReferenceAlt_returnsEmpty() {
        var ch = choice(lit("if"), new Expression.Reference(SPAN, "Identifier"));

        var result = ChoiceDispatchAnalyzer.classify(ch);

        assertThat(result).isEmpty();
    }

    @Test
    void classify_choiceWithCharClassAlt_returnsEmpty() {
        var ch = choice(lit("if"), new Expression.CharClass(SPAN, "a-z", false, false));

        var result = ChoiceDispatchAnalyzer.classify(ch);

        assertThat(result).isEmpty();
    }

    @Test
    void classify_emptyChoice_returnsEmpty() {
        var ch = new Expression.Choice(SPAN, List.of());

        var result = ChoiceDispatchAnalyzer.classify(ch);

        assertThat(result).isEmpty();
    }

    @Test
    void classify_sequencePrefixedByLiteral_usesLiteralsFirstChar() {
        var seq = new Expression.Sequence(SPAN, List.of(lit("do"), lit("while")));
        var ch = choice(seq, lit("for"));

        var result = ChoiceDispatchAnalyzer.classify(ch);

        assertThat(result).isPresent();
        var entries = result.orElseThrow();
        assertThat(entries.get(0).firstChar().c()).isEqualTo('d');
        assertThat(entries.get(1).firstChar().c()).isEqualTo('f');
    }

    @Test
    void classify_sequenceWithLeadingPredicate_skipsPredicateToLiteral() {
        // &'@' 'public' — predicate doesn't consume; first literal drives dispatch.
        var seq = new Expression.Sequence(SPAN, List.of(
            new Expression.And(SPAN, lit("@")),
            lit("public")));
        var ch = choice(seq, lit("private"));

        var result = ChoiceDispatchAnalyzer.classify(ch);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().get(0).firstChar().c()).isEqualTo('p');
    }

    @Test
    void classify_caseInsensitiveLiteral_marksEntryAsCaseInsensitive() {
        var ch = choice(lit("do", true), lit("for", false));

        var entries = ChoiceDispatchAnalyzer.classify(ch).orElseThrow();

        assertThat(entries.get(0).firstChar().caseInsensitive()).isTrue();
        assertThat(entries.get(1).firstChar().caseInsensitive()).isFalse();
    }

    @Test
    void classify_sequenceStartingWithNonLiteral_returnsEmpty() {
        var seq = new Expression.Sequence(SPAN, List.of(
            new Expression.Reference(SPAN, "Modifier"),
            lit("class")));
        var ch = choice(seq, lit("for"));

        assertThat(ChoiceDispatchAnalyzer.classify(ch)).isEmpty();
    }

    @Test
    void classify_unwrapsGroupTokenBoundaryIgnoreCapture() {
        var ch = choice(
            new Expression.Group(SPAN, lit("one")),
            new Expression.TokenBoundary(SPAN, lit("two")),
            new Expression.Ignore(SPAN, lit("three")),
            new Expression.Capture(SPAN, "c", lit("four")));

        var entries = ChoiceDispatchAnalyzer.classify(ch).orElseThrow();

        assertThat(entries).hasSize(4);
        assertThat(entries.get(0).firstChar().c()).isEqualTo('o');
        assertThat(entries.get(1).firstChar().c()).isEqualTo('t');
        assertThat(entries.get(2).firstChar().c()).isEqualTo('t');
        assertThat(entries.get(3).firstChar().c()).isEqualTo('f');
    }

    @Test
    void groupByChar_caseInsensitiveLiteral_contributesBothCases() {
        var entries = ChoiceDispatchAnalyzer.classify(choice(lit("do", true))).orElseThrow();

        var grouped = ChoiceDispatchAnalyzer.groupByChar(entries);

        assertThat(grouped).containsOnlyKeys('d', 'D');
        assertThat(grouped.get('d')).usingRecursiveComparison().isEqualTo(grouped.get('D'));
    }

    @Test
    void groupByChar_caseSensitiveLiteral_contributesOnlyExactChar() {
        var entries = ChoiceDispatchAnalyzer.classify(choice(lit("Do", false))).orElseThrow();

        var grouped = ChoiceDispatchAnalyzer.groupByChar(entries);

        assertThat(grouped).containsOnlyKeys('D');
    }

    @Test
    void groupByChar_sharedFirstChar_collectsIntoOneBucketInOrder() {
        var entries = ChoiceDispatchAnalyzer.classify(
            choice(lit("do"), lit("double"), lit("for"))).orElseThrow();

        var grouped = ChoiceDispatchAnalyzer.groupByChar(entries);

        assertThat(grouped.get('d')).hasSize(2);
        assertThat(grouped.get('d').get(0).originalIndex()).isEqualTo(0);
        assertThat(grouped.get('d').get(1).originalIndex()).isEqualTo(1);
        assertThat(grouped.get('f')).hasSize(1);
    }

    @Test
    void buckets_caseInsensitiveLiteral_coalescesUpperAndLowerIntoSingleBucket() {
        var entries = ChoiceDispatchAnalyzer.classify(choice(lit("do", true), lit("for"))).orElseThrow();

        var buckets = ChoiceDispatchAnalyzer.buckets(ChoiceDispatchAnalyzer.groupByChar(entries));

        // 'do' (case-insensitive) → one bucket with chars {d, D}; 'for' → one bucket with {f}.
        assertThat(buckets).hasSize(2);
        var firstBucket = buckets.get(0);
        assertThat(firstBucket.chars()).containsExactlyInAnyOrder('d', 'D');
        assertThat(firstBucket.alts()).hasSize(1);
        assertThat(buckets.get(1).chars()).containsExactly('f');
    }
}
