package org.pragmatica.peg.v6.incremental;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.v6.PegParser;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.6.1 — verify that {@link IncrementalParser} consumes the grammar's
 * {@code %checkpoint} directives when constructed via the 2-arg constructor.
 *
 * <p>The 2-arg constructor must prefer the grammar-supplied checkpoint set
 * when non-empty, and fall back to {@link IncrementalParser#DEFAULT_CHECKPOINT_RULES}
 * only when the grammar carries no {@code %checkpoint} declarations.
 */
final class CheckpointDirectiveIncrementalTest {

    private static final String GRAMMAR_WITH_CHECKPOINT = """
        %checkpoint Item
        %checkpoint Outer
        File <- Item (',' Item)*
        Item <- 'foo' / 'bar'
        Outer <- '[' File ']'
        %whitespace <- [ \\t]*
        """;

    private static final String GRAMMAR_WITHOUT_CHECKPOINT = """
        File <- Item (',' Item)*
        Item <- 'foo' / 'bar'
        %whitespace <- [ \\t]*
        """;

    @Test
    void twoArgCtor_usesGrammarCheckpoints_whenDeclared() {
        var parser = PegParser.fromGrammar(GRAMMAR_WITH_CHECKPOINT).unwrap();
        var incremental = new IncrementalParser(parser, "foo, bar");
        assertThat(incremental.checkpointRules())
            .as("grammar-declared checkpoints must override defaults")
            .isEqualTo(Set.of("Item", "Outer"));
    }

    @Test
    void twoArgCtor_fallsBackToDefaults_whenGrammarHasNoCheckpoints() {
        var parser = PegParser.fromGrammar(GRAMMAR_WITHOUT_CHECKPOINT).unwrap();
        var incremental = new IncrementalParser(parser, "foo, bar");
        assertThat(incremental.checkpointRules())
            .as("with no grammar checkpoints, defaults apply")
            .isEqualTo(IncrementalParser.DEFAULT_CHECKPOINT_RULES);
    }

    @Test
    void threeArgCtor_explicitSet_overridesEverything() {
        var parser = PegParser.fromGrammar(GRAMMAR_WITH_CHECKPOINT).unwrap();
        var explicit = Set.of("File");
        var incremental = new IncrementalParser(parser, "foo, bar", explicit);
        assertThat(incremental.checkpointRules())
            .as("explicit 3-arg ctor argument wins")
            .isEqualTo(explicit);
    }
}
