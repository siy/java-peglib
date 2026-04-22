package org.pragmatica.peg.incremental;

import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.incremental.internal.SessionFactory;
import org.pragmatica.peg.parser.ParserConfig;

/**
 * Factory for cursor-anchored incremental {@link Session}s over a given
 * {@link Grammar}.
 *
 * <p>Each {@code IncrementalParser} owns:
 * <ul>
 *   <li>a validated {@link Grammar};</li>
 *   <li>a configured {@link org.pragmatica.peg.parser.Parser} from
 *       {@link PegParser#fromGrammar(Grammar, ParserConfig)} — this is the
 *       parser used for both full parses and {@code parseRuleAt(...)} calls
 *       during incremental splice;</li>
 *   <li>the set of rule names that fall back to full reparse because they
 *       (directly or transitively) depend on a {@link
 *       org.pragmatica.peg.grammar.Expression.BackReference} (SPEC §6.3 /
 *       §10);</li>
 *   <li>a registry of synthesised {@link org.pragmatica.peg.action.RuleId}
 *       classes so {@code parseRuleAt} can be invoked with any rule name
 *       known to this grammar.</li>
 * </ul>
 *
 * <p>Sessions produced by {@link #initialize(String)} /
 * {@link #initialize(String, int)} and their descendants share these
 * resources. Thread-safe to share across threads: all state held here is
 * either immutable or uses concurrent collections.
 *
 * <p>v1 scope per {@code docs/incremental/SPEC.md} §9: CST-only, wholesale
 * packrat invalidation on every edit, no incremental action execution.
 *
 * @since 0.3.1
 */
public interface IncrementalParser {
    /**
     * Create an incremental parser over {@code grammar} with the default
     * runtime parser configuration ({@link ParserConfig#DEFAULT}).
     */
    static IncrementalParser create(Grammar grammar) {
        return create(grammar, ParserConfig.DEFAULT);
    }

    /**
     * Create an incremental parser over {@code grammar} with a specific
     * runtime parser configuration. The configuration's packrat /
     * recovery / trivia flags are honoured for both full parses and
     * incremental partial-parse calls.
     */
    static IncrementalParser create(Grammar grammar, ParserConfig config) {
        return SessionFactory.create(grammar, config);
    }

    /**
     * Produce the initial {@link Session} over {@code buffer} with the
     * cursor at offset 0.
     */
    default Session initialize(String buffer) {
        return initialize(buffer, 0);
    }

    /**
     * Produce the initial {@link Session} over {@code buffer} with the
     * cursor at {@code cursorOffset}. The cursor is clamped to
     * {@code [0, buffer.length()]}.
     */
    Session initialize(String buffer, int cursorOffset);
}
