package org.pragmatica.peg.v6.generator;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.cst.ParseResult;
import org.pragmatica.peg.v6.token.TokenArray;

import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase B.3 — compile a {@link ParserGenerator.GeneratedParser} into a callable
 * {@link CompiledParser}. Mirrors {@link LexerCompiler}'s in-memory JDK Compiler
 * API pattern.
 */
public final class ParserCompiler {

    private ParserCompiler() {}

    public sealed interface ParserCompileError extends Cause {
        record NoCompilerAvailable() implements ParserCompileError {
            @Override
            public String message() {
                return "No Java compiler available — run with a JDK, not a JRE";
            }
        }

        record CompilationFailed(String diagnostics) implements ParserCompileError {
            @Override
            public String message() {
                return "Generated parser compilation failed:\n" + diagnostics;
            }
        }

        record LoadFailed(String className, Throwable cause) implements ParserCompileError {
            @Override
            public String message() {
                return "Failed to load generated parser '" + className + "': " + cause;
            }
        }
    }

    public record CompiledParser(Class<?> parserClass, Method parseMethod) {
        /**
         * Phase B.4 — generated {@code parse(TokenArray)} now returns
         * {@link ParseResult} unconditionally: a CST plus a (possibly empty)
         * diagnostics list. Recovery is panic-mode at the token sync set so
         * malformed inputs no longer raise an exception to the caller.
         */
        public ParseResult parse(TokenArray tokens) {
            try {
                return (ParseResult) parseMethod.invoke(null, tokens);
            } catch (InvocationTargetException e) {
                var cause = e.getCause();
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                if (cause instanceof Error err) {
                    throw err;
                }
                throw new RuntimeException(cause);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * Convenience wrapper for callers that want only the CST and assume the
         * input is well-formed. Equivalent to {@code parse(tokens).cst()}.
         */
        public CstArray parseCst(TokenArray tokens) {
            return parse(tokens).cst();
        }
    }

    public static Result<CompiledParser> compile(ParserGenerator.GeneratedParser source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new ParserCompileError.NoCompilerAvailable().result();
        }
        return runCompilation(compiler, source).flatMap(ParserCompiler::loadParserClass);
    }

    private static Result<CompiledClass> runCompilation(JavaCompiler compiler, ParserGenerator.GeneratedParser source) {
        var fqcn = source.fullyQualifiedName();
        try (var standard = compiler.getStandardFileManager(null, null, null)) {
            var fileManager = new InMemoryFileManager(standard);
            var fileObject = new StringJavaFileObject(fqcn, source.source());
            var diagnostics = new StringWriter();
            var task = compiler.getTask(diagnostics, fileManager, null,
                List.of("--release", "25"), null, List.of(fileObject));
            if (!task.call()) {
                return new ParserCompileError.CompilationFailed(diagnostics.toString()).result();
            }
            return Result.success(new CompiledClass(fqcn, fileManager));
        } catch (Exception e) {
            return new ParserCompileError.LoadFailed(fqcn, e).result();
        }
    }

    private static Result<CompiledParser> loadParserClass(CompiledClass compiled) {
        try {
            var classLoader = new InMemoryClassLoader(compiled.fileManager(),
                ParserCompiler.class.getClassLoader());
            var clazz = classLoader.loadClass(compiled.fullyQualifiedName());
            var method = clazz.getDeclaredMethod("parse", TokenArray.class);
            method.setAccessible(true);
            return Result.success(new CompiledParser(clazz, method));
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return new ParserCompileError.LoadFailed(compiled.fullyQualifiedName(), e).result();
        }
    }

    private record CompiledClass(String fullyQualifiedName, InMemoryFileManager fileManager) {}

    private static final class StringJavaFileObject extends SimpleJavaFileObject {
        private final String code;

        StringJavaFileObject(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    private static final class ByteArrayJavaFileObject extends SimpleJavaFileObject {
        private byte[] bytes;

        ByteArrayJavaFileObject(String className) {
            super(URI.create("bytes:///" + className.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
        }

        @Override
        public OutputStream openOutputStream() {
            return new ByteArrayOutputStream() {
                @Override
                public void close() {
                    bytes = toByteArray();
                }
            };
        }

        byte[] bytes() {
            return bytes;
        }
    }

    private static final class InMemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, ByteArrayJavaFileObject> classFiles = new HashMap<>();

        InMemoryFileManager(StandardJavaFileManager delegate) {
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

        Option<byte[]> classBytes(String className) {
            return Option.option(classFiles.get(className)).map(ByteArrayJavaFileObject::bytes);
        }
    }

    private static final class InMemoryClassLoader extends ClassLoader {
        private final InMemoryFileManager fileManager;

        InMemoryClassLoader(InMemoryFileManager fileManager, ClassLoader parent) {
            super(parent);
            this.fileManager = fileManager;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            var bytesOpt = fileManager.classBytes(name);
            if (bytesOpt.isEmpty()) {
                throw new ClassNotFoundException(name);
            }
            var bytes = bytesOpt.unwrap();
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
