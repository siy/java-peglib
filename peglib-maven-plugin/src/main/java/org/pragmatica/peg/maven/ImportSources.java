package org.pragmatica.peg.maven;

import java.io.File;

import org.pragmatica.peg.grammar.GrammarSource;


/**
 * Shared {@code %import} resolution policy for the three mojos.
 *
 * <p>Extracted in 0.7.2 because the identical logic was written out three times, and
 * {@link CheckMojo} runs two of those copies in a single {@code execute()} — its own, and
 * {@link LintMojo}'s via the embedded lint pass. Byte-identical copies that are used
 * together in one invocation will eventually diverge, and when they do the two halves of
 * {@code peglib:check} would resolve the same {@code %import} against different
 * directories.
 *
 * <p>Kept in the plugin module deliberately: it deals in {@link File}, which is Maven's
 * currency, and {@code peglib-core}'s grammar API should not grow a {@code java.io.File}
 * dependency for the convenience of one consumer.
 *
 * @since 0.7.2
 */
final class ImportSources {
    private ImportSources() {}

    /**
     * Filesystem source rooted at {@code importDirectory}, or at {@code grammarFile}'s own
     * directory when that parameter is unset — so {@code %import Shared.Rule} finds
     * {@code Shared.peg} beside the root grammar with no configuration.
     *
     * <p>Returns {@link GrammarSource#empty()} when neither yields a directory, which makes
     * any {@code %import} fail with "grammar not found" rather than reading from an
     * unexpected location.
     */
    static GrammarSource forGrammar(File grammarFile, File importDirectory) {
        if (importDirectory != null) {
            return GrammarSource.filesystem(importDirectory.toPath());
        }

        var parent = grammarFile.toPath().toAbsolutePath().getParent();

        return parent == null
               ? GrammarSource.empty()
               : GrammarSource.filesystem(parent);
    }
}
