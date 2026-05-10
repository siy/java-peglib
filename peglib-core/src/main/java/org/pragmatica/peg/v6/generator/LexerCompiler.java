package org.pragmatica.peg.v6.generator;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
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
 * Phase A.4 — compile a {@link LexerGenerator.Generated} source into a callable
 * {@link CompiledLexer}. In-memory JDK Compiler API pattern; mirrors
 * {@link org.pragmatica.peg.action.ActionCompiler ActionCompiler}.
 */
public final class LexerCompiler {
    private LexerCompiler() {}

    public sealed interface LexerCompileError extends Cause permits LexerCompileError.NoCompilerAvailable,
    LexerCompileError.CompilationFailed,
    LexerCompileError.LoadFailed,
    LexerCompileError.InvocationFailed {
        record NoCompilerAvailable() implements LexerCompileError {
            @Override public String message() {
                return "No Java compiler available — run with a JDK, not a JRE";
            }
        }

        record CompilationFailed(String diagnostics) implements LexerCompileError {
            @Override public String message() {
                return "Generated lexer compilation failed:\n" + diagnostics;
            }
        }

        record LoadFailed(String className, Throwable cause) implements LexerCompileError {
            @Override public String message() {
                return "Failed to load generated lexer '" + className + "': " + cause;
            }
        }

        record InvocationFailed(String className, Throwable cause) implements LexerCompileError {
            @Override public String message() {
                return "Generated lexer '" + className + "' invocation failed: " + cause;
            }
        }
    }

    public record CompiledLexer(Class<?> lexerClass, Method lexMethod) {
        public TokenArray lex(String input) {
            return Result.lift(t -> (Cause) new LexerCompileError.InvocationFailed(lexerClass.getName(), unwrapCause(t)),
                               () -> (TokenArray) lexMethod.invoke(null, input))
            .unwrap();
        }

        private static Throwable unwrapCause(Throwable t) {
            return t instanceof InvocationTargetException ite && ite.getCause() != null
                   ? ite.getCause()
                   : t;
        }
    }

    public static Result<CompiledLexer> compile(LexerGenerator.Generated source) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if ( compiler == null) {
        return new LexerCompileError.NoCompilerAvailable().result();}
        return runCompilation(compiler, source).flatMap(LexerCompiler::loadLexerClass);
    }

    private static Result<CompiledClass> runCompilation(JavaCompiler compiler, LexerGenerator.Generated source) {
        var fqcn = source.fullyQualifiedName();
        try (var standard = compiler.getStandardFileManager(null, null, null)) {
            var fileManager = new InMemoryFileManager(standard);
            var fileObject = new StringJavaFileObject(fqcn, source.source());
            var diagnostics = new StringWriter();
            var task = compiler.getTask(diagnostics,
                                        fileManager,
                                        null,
                                        List.of("--release", "25"),
                                        null,
                                        List.of(fileObject));
            if ( !task.call()) {
            return new LexerCompileError.CompilationFailed(diagnostics.toString()).result();}
            return Result.success(new CompiledClass(fqcn, fileManager));
        }



        catch (Exception e) {
            return new LexerCompileError.LoadFailed(fqcn, e).result();
        }
    }

    private static Result<CompiledLexer> loadLexerClass(CompiledClass compiled) {
        try {
            var classLoader = new InMemoryClassLoader(compiled.fileManager(), LexerCompiler.class.getClassLoader());
            var clazz = classLoader.loadClass(compiled.fullyQualifiedName());
            var method = clazz.getDeclaredMethod("lex", String.class);
            method.setAccessible(true);
            return Result.success(new CompiledLexer(clazz, method));
        }



        catch (ClassNotFoundException | NoSuchMethodException e) {
            return new LexerCompileError.LoadFailed(compiled.fullyQualifiedName(), e).result();
        }
    }

    private record CompiledClass(String fullyQualifiedName, InMemoryFileManager fileManager){}

    private static final class StringJavaFileObject extends SimpleJavaFileObject {
        private final String code;

        StringJavaFileObject(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension),
                  Kind.SOURCE);
            this.code = code;
        }

        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    private static final class ByteArrayJavaFileObject extends SimpleJavaFileObject {
        private byte[] bytes;

        ByteArrayJavaFileObject(String className) {
            super(URI.create("bytes:///" + className.replace('.', '/') + Kind.CLASS.extension),
                  Kind.CLASS);
        }

        @Override public OutputStream openOutputStream() {
            return new ByteArrayOutputStream() {@Override@SuppressWarnings("JBCT-RET-01") public void close() {
                bytes = toByteArray();
            }};
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

        @Override public JavaFileObject getJavaFileForOutput(Location location,
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

        // ClassLoader.findClass JDK API mandates a ClassNotFoundException throws
        // clause; suppress JBCT-EX-01 because the contract is dictated by the JDK.
        @Override
        @SuppressWarnings("JBCT-EX-01")
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            var bytesOpt = fileManager.classBytes(name);
            if ( bytesOpt.isEmpty()) {
            throw new ClassNotFoundException(name);}
            var bytes = bytesOpt.unwrap();
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
