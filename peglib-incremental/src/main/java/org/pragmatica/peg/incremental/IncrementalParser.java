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
 * <p>0.5.0 (Lever D): {@link #initialize(String, int)} returns an
 * {@link InitialSession} bundling the Session and the initial {@link Cursor}.
 * Cursor state is no longer carried inside the Session record — see SPEC §5.
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
     * runtime parser configuration.
     */
    static IncrementalParser create(Grammar grammar, ParserConfig config) {
        return SessionFactory.sessionFactory(grammar, config);
    }

    /**
     * Create an incremental parser with explicit control over the SPEC §5.4
     * v2 trivia-only fast-path.
     *
     * @since 0.3.2
     */
    static IncrementalParser create(Grammar grammar, ParserConfig config, boolean triviaFastPathEnabled) {
        return SessionFactory.sessionFactory(grammar, config, triviaFastPathEnabled);
    }

    /**
     * Produce the initial {@link InitialSession} over {@code buffer} with the
     * cursor at offset 0.
     */
    default InitialSession initialize(String buffer) {
        return initialize(buffer, 0);
    }

    /**
     * Produce the initial {@link InitialSession} over {@code buffer} with the
     * cursor at {@code cursorOffset}. The cursor is clamped to
     * {@code [0, buffer.length()]}.
     *
     * <p>0.5.0 (Lever D): returns a paired {@code (Session, Cursor)} —
     * cursor state lives outside the Session record.
     */
    InitialSession initialize(String buffer, int cursorOffset);
}
