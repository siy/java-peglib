package org.pragmatica.peg.action;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.error.ParseError;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.Rule;
import org.pragmatica.peg.tree.SourceLocation;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.StringWriter;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Compiles inline Java actions from grammar rules.
 * Uses the JDK Compiler API for runtime compilation.
 *
 * <p><strong>Security Note:</strong> This class compiles and executes arbitrary Java code
 * provided in grammar action blocks. Only use with trusted grammar sources. Never compile
 * actions from untrusted user input as this enables arbitrary code execution.
 *
 * <p>For sandboxed execution of untrusted grammars, use source generation mode
 * ({@link org.pragmatica.peg.generator.ParserGenerator}) and review generated code
 * before compilation.
 */
public final class ActionCompiler {
    private static final String PACKAGE = "org.pragmatica.peg.action.generated";

    private final ClassLoader parentLoader;
    private final AtomicInteger counter = new AtomicInteger(0);

    private ActionCompiler(ClassLoader parentLoader) {
        this.parentLoader = parentLoader;
    }

    public static ActionCompiler create() {
        return new ActionCompiler(ActionCompiler.class.getClassLoader());
    }

    public static ActionCompiler create(ClassLoader parentLoader) {
        return new ActionCompiler(parentLoader);
    }

    /**
     * Compile all actions in a grammar.
     *
     * @return Map from rule name to compiled action
     */
    public Result<Map<String, Action>> compileGrammar(Grammar grammar) {
        var actions = new HashMap<String, Action>();
        for (var rule : grammar.rules()) {
            if (rule.hasAction()) {
                var result = compileAction(rule);
                if (result instanceof Result.Failure< ? > f) {
                    return Result.failure(f.cause());
                }
                actions.put(rule.name(), result.unwrap());
            }
        }
        return Result.success(actions);
    }

    /**
     * Compile a single rule's action.
     */
    public Result<Action> compileAction(Rule rule) {
        if (rule.action()
                .isEmpty()) {
            return Result.failure(new ParseError.SemanticError(
            rule.span()
                .start(),
            "Rule '" + rule.name() + "' has no action"));
        }
        var actionCode = rule.action()
                             .unwrap();
        return compileActionCode(rule.name(),
                                 actionCode,
                                 rule.span()
                                     .start());
    }

    /**
     * Compile action code string.
     */
    public Result<Action> compileActionCode(String ruleName, String actionCode, SourceLocation location) {
        var className = "Action_" + sanitize(ruleName) + "_" + counter.incrementAndGet();
        var fullClassName = PACKAGE + "." + className;
        // Transform action code: $0 -> sv.token(), $1 -> sv.get(0), etc.
        var transformedCode = transformActionCode(actionCode);
        var sourceCode = generateActionClass(className, transformedCode);
        return compileAndLoad(fullClassName, sourceCode, location);
    }

    private static final Pattern POSITIONAL_VAR = Pattern.compile("\\$(\\d+)");

    private String transformActionCode(String code) {
        // Replace $0 with sv.token()
        var result = code.replace("$0", "sv.token()");
        // Replace $N (N > 0) with sv.get(N-1) using regex for unlimited support
        result = POSITIONAL_VAR.matcher(result)
                               .replaceAll(match -> {
                                               int n = Integer.parseInt(match.group(1));
                                               return n == 0
                                                      ? "sv.token()"
                                                      : "sv.get(" + (n - 1) + ")";
                                           });
        return result;
    }

    private String generateActionClass(String className, String actionCode) {
        return """
            package %s;

            import org.pragmatica.peg.action.Action;
            import org.pragmatica.peg.action.SemanticValues;
            import java.util.*;

            public final class %s implements Action {
                @Override
                public Object apply(SemanticValues sv) {
                    %s
                }
            }
            """.formatted(PACKAGE, className, wrapActionCode(actionCode));
    }

    private String wrapActionCode(String code) {
        var trimmed = code.trim();
        // If code already has return, use as-is
        if (trimmed.startsWith("return ")) {
            return trimmed;
        }
        // If code is a simple expression, wrap with return
        if (!trimmed.contains(";") || trimmed.endsWith(";") && !trimmed.contains("\n")) {
            var expr = trimmed.endsWith(";")
                       ? trimmed.substring(0, trimmed.length() - 1)
                       : trimmed;
            return "return " + expr + ";";
        }
        // Otherwise, assume it's a block that handles its own return
        return trimmed;
    }

    private Result<Action> compileAndLoad(String className, String sourceCode, SourceLocation location) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return Result.failure(new ParseError.SemanticError(
            location, "No Java compiler available. Run with JDK, not JRE."));
        }
        try (var standardFileManager = compiler.getStandardFileManager(null, null, null)) {
            var fileManager = new InMemoryFileManager(standardFileManager);
            var sourceFile = new StringJavaFileObject(className, sourceCode);
            var diagnostics = new StringWriter();
            var task = compiler.getTask(
            diagnostics, fileManager, null, List.of("--release", "25"), null, List.of(sourceFile));
            if (!task.call()) {
                return Result.failure(new ParseError.SemanticError(
                location, "Action compilation failed: " + diagnostics));
            }
            var classLoader = new InMemoryClassLoader(fileManager, parentLoader);
            var actionClass = classLoader.loadClass(className);
            var action = (Action) actionClass.getDeclaredConstructor()
                                            .newInstance();
            return Result.success(action);
        } catch (Exception e) {
            return Result.failure(new ParseError.ActionError(
            location, sourceCode, e));
        }
    }

    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    // === In-memory compilation support ===
    private static class StringJavaFileObject extends SimpleJavaFileObject {
        private final String code;

        StringJavaFileObject(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension),
                  Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    private static class ByteArrayJavaFileObject extends SimpleJavaFileObject {
        private byte[] bytes;

        ByteArrayJavaFileObject(String className) {
            super(URI.create("bytes:///" + className.replace('.', '/') + Kind.CLASS.extension),
                  Kind.CLASS);
        }

        @Override
        public java.io.OutputStream openOutputStream() {
            return new java.io.ByteArrayOutputStream() {
                @Override
                public void close() {
                    bytes = toByteArray();
                }
            };
        }

        byte[] getBytes() {
            return bytes;
        }
    }

    private static class InMemoryFileManager extends javax.tools.ForwardingJavaFileManager<javax.tools.StandardJavaFileManager> {
        private final Map<String, ByteArrayJavaFileObject> classFiles = new HashMap<>();

        InMemoryFileManager(javax.tools.StandardJavaFileManager delegate) {
            super(delegate);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location,
                                                   String className,
                                                   JavaFileObject.Kind kind,
                                                   javax.tools.FileObject sibling) {
            var fileObject = new ByteArrayJavaFileObject(className);
            classFiles.put(className, fileObject);
            return fileObject;
        }

        Option<byte[] > getClassBytes(String className) {
            return Option.option(classFiles.get(className))
                         .map(ByteArrayJavaFileObject::getBytes);
        }
    }

    private static class InMemoryClassLoader extends ClassLoader {
        private final InMemoryFileManager fileManager;

        InMemoryClassLoader(InMemoryFileManager fileManager, ClassLoader parent) {
            super(parent);
            this.fileManager = fileManager;
        }

        @Override
        protected Class< ? > findClass(String name) throws ClassNotFoundException {
            var bytesOpt = fileManager.getClassBytes(name);
            if (bytesOpt.isEmpty()) {
                throw new ClassNotFoundException(name);
            }
            var bytes = bytesOpt.unwrap();
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
