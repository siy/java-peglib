package org.pragmatica.peg.grammar;

import org.pragmatica.lang.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 0.2.8 — Strategy for loading grammar text by name, used by
 * {@link GrammarResolver} to satisfy {@code %import} directives.
 *
 * <p>Implementations map a grammar name (e.g. {@code "Java25"}) to its source
 * text. The conventional mapping is {@code <name>.peg} on some search path;
 * each built-in implementation applies that convention to a specific backing
 * store. Returns {@link Option#none()} when the name is not resolvable — the
 * resolver converts this into a clear "missing grammar file" diagnostic that
 * includes the name it looked up and the source chain it tried.
 *
 * <p>Chaining is supported via {@link ChainedGrammarSource}; most callers will
 * use one of:
 * <ul>
 *   <li>{@link InMemoryGrammarSource} — a {@code Map<String,String>} built in
 *       code; useful for tests.</li>
 *   <li>{@link ClasspathGrammarSource} — reads {@code <name>.peg} from a
 *       classloader resource root.</li>
 *   <li>{@link FilesystemGrammarSource} — reads {@code <name>.peg} from a
 *       configured directory. (Deferred from 0.2.8 scope cut — kept behind the
 *       interface for now; users can implement their own or call
 *       {@link #filesystem(Path)} once enabled.)</li>
 * </ul>
 */
public interface GrammarSource {
    Option<String> load(String grammarName);

    /**
     * Empty source — returns {@link Option#none()} for any lookup. The default
     * source when callers do not pass one explicitly; present so that a root
     * grammar with {@code %import} directives fails with a clear "no grammar
     * source configured" message instead of a NullPointerException.
     */
    static GrammarSource empty() {
        return _ -> Option.none();
    }

    /**
     * In-memory source backed by the provided name→text map.
     */
    static GrammarSource inMemory(Map<String, String> grammars) {
        return new InMemoryGrammarSource(Map.copyOf(grammars));
    }

    /**
     * Classpath source — looks up {@code <name>.peg} via the supplied
     * classloader. Uses {@link ClassLoader#getResourceAsStream(String)}.
     */
    static GrammarSource classpath(ClassLoader loader) {
        return new ClasspathGrammarSource(loader);
    }

    /**
     * Classpath source using the current thread's context classloader.
     */
    static GrammarSource classpath() {
        var loader = Thread.currentThread()
                           .getContextClassLoader();
        return classpath(loader == null
                         ? GrammarSource.class.getClassLoader()
                         : loader);
    }

    /**
     * Filesystem source rooted at {@code directory}. Reads {@code <name>.peg}
     * resolved against the directory; returns {@link Option#none()} when the
     * file is absent or unreadable.
     */
    static GrammarSource filesystem(Path directory) {
        return new FilesystemGrammarSource(directory);
    }

    /**
     * Chain multiple sources; first hit wins.
     */
    static GrammarSource chained(GrammarSource... sources) {
        return new ChainedGrammarSource(List.of(sources));
    }

    /**
     * In-memory implementation. Package-private — use {@link #inMemory(Map)}.
     */
    final class InMemoryGrammarSource implements GrammarSource {
        private final Map<String, String> grammars;

        InMemoryGrammarSource(Map<String, String> grammars) {
            this.grammars = grammars;
        }

        @Override
        public Option<String> load(String grammarName) {
            var text = grammars.get(grammarName);
            return text == null
                   ? Option.none()
                   : Option.some(text);
        }
    }

    /**
     * Classpath implementation. Package-private — use {@link #classpath(ClassLoader)}.
     */
    final class ClasspathGrammarSource implements GrammarSource {
        private final ClassLoader loader;

        ClasspathGrammarSource(ClassLoader loader) {
            this.loader = loader;
        }

        @Override
        public Option<String> load(String grammarName) {
            var resourceName = grammarName + ".peg";
            try (var in = loader.getResourceAsStream(resourceName)) {
                if (in == null) {
                    return Option.none();
                }
                return Option.some(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                return Option.none();
            }
        }
    }

    /**
     * Filesystem implementation. Package-private — use {@link #filesystem(Path)}.
     */
    final class FilesystemGrammarSource implements GrammarSource {
        private final Path directory;

        FilesystemGrammarSource(Path directory) {
            this.directory = directory;
        }

        @Override
        public Option<String> load(String grammarName) {
            var file = directory.resolve(grammarName + ".peg");
            if (!Files.isRegularFile(file)) {
                return Option.none();
            }
            try{
                return Option.some(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                return Option.none();
            }
        }
    }

    /**
     * Chained implementation — tries each backing source in order; first hit wins.
     */
    final class ChainedGrammarSource implements GrammarSource {
        private final List<GrammarSource> sources;

        ChainedGrammarSource(List<GrammarSource> sources) {
            this.sources = List.copyOf(sources);
        }

        @Override
        public Option<String> load(String grammarName) {
            for (var source : sources) {
                var result = source.load(grammarName);
                if (result.isPresent()) {
                    return result;
                }
            }
            return Option.none();
        }
    }
}
