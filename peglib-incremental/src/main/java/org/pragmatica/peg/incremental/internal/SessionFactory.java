package org.pragmatica.peg.incremental.internal;

import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.action.RuleId;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.incremental.IncrementalParser;
import org.pragmatica.peg.incremental.Session;
import org.pragmatica.peg.parser.Parser;
import org.pragmatica.peg.parser.ParserConfig;
import org.pragmatica.peg.tree.CstNode;

import java.util.Set;

/**
 * Package-private factory holding the resources shared across a lineage of
 * {@link Session}s produced from the same {@link IncrementalParser}.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>validate the grammar and construct the backing {@link Parser}
 *       (interpreter path; the same parser is used for full parses and for
 *       {@code parseRuleAt} during incremental splice);</li>
 *   <li>pre-compute the set of rule names that trigger full reparse because
 *       they depend on back-references (SPEC §10 mitigation);</li>
 *   <li>own the {@link RuleIdRegistry} that maps rule names to synthesised
 *       {@code Class<? extends RuleId>} instances.</li>
 * </ul>
 *
 * @since 0.3.1
 */
public final class SessionFactory implements IncrementalParser {
    private final Grammar grammar;
    private final ParserConfig config;
    private final Parser parser;
    private final Set<String> fallbackRules;
    private final RuleIdRegistry registry;

    private SessionFactory(Grammar grammar, ParserConfig config, Parser parser, Set<String> fallbackRules) {
        this.grammar = grammar;
        this.config = config;
        this.parser = parser;
        this.fallbackRules = fallbackRules;
        this.registry = new RuleIdRegistry();
    }

    /**
     * Create a {@link SessionFactory} over {@code grammar} with the given
     * {@code config}. Grammar validation errors surface as
     * {@link IllegalArgumentException} — {@link IncrementalParser} is a
     * construction-time API, not a parse-result API, so validation failure
     * is a programmer error (the caller owns the grammar text).
     */
    public static IncrementalParser create(Grammar grammar, ParserConfig config) {
        var validated = grammar.validate().fold(
            cause -> { throw new IllegalArgumentException("invalid grammar: " + cause.message()); },
            g -> g);
        var parser = PegParser.fromGrammar(validated, config).fold(
            cause -> { throw new IllegalStateException("failed to build parser: " + cause.message()); },
            p -> p);
        var fallback = BackReferenceScan.unsafeRules(validated);
        return new SessionFactory(validated, config, parser, fallback);
    }

    @Override
    public Session initialize(String buffer, int cursorOffset) {
        if (buffer == null) {
            throw new IllegalArgumentException("buffer must not be null");
        }
        int clampedCursor = Math.max(0, Math.min(cursorOffset, buffer.length()));
        CstNode root = parseFull(buffer);
        return SessionImpl.initial(this, buffer, clampedCursor, root);
    }

    Grammar grammar() { return grammar; }
    ParserConfig config() { return config; }
    Parser parser() { return parser; }
    Set<String> fallbackRules() { return fallbackRules; }
    RuleIdRegistry registry() { return registry; }

    /**
     * Full-parse the buffer via the backing {@link Parser}. Surfaces errors
     * as {@link IllegalStateException} — v1 treats an unparseable full
     * buffer as a programmer-level failure; recovery-aware callers should
     * configure {@link ParserConfig} with {@link
     * org.pragmatica.peg.error.RecoveryStrategy#ADVANCED} and read
     * diagnostics from the resulting tree's {@link CstNode.Error} nodes.
     */
    CstNode parseFull(String buffer) {
        return parser.parseCst(buffer).fold(
            cause -> { throw new IllegalStateException("full parse failed: " + cause.message()); },
            node -> node);
    }
}
