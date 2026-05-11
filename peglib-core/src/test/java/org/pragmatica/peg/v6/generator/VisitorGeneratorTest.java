package org.pragmatica.peg.v6.generator;

import org.pragmatica.lang.Result;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.cst.ParseResult;
import org.pragmatica.peg.v6.lexer.DfaBuilder;
import org.pragmatica.peg.v6.lexer.LexerEngine;
import org.pragmatica.peg.v6.lexer.RuleClassifier;
import org.pragmatica.peg.v6.lexer.RuleKind;

import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VisitorGeneratorTest {
    private record Built(Grammar grammar,
                         RuleClassifier.Classification classification,
                         DfaBuilder.Built dfa,
                         LexerEngine engine) {}

    private static Built buildAll(String grammarText) {
        var grammar = GrammarParser.parse(grammarText)
                                   .unwrap();
        var classification = RuleClassifier.classify(grammar)
                                           .unwrap();
        var built = DfaBuilder.build(grammar, classification)
                              .unwrap();
        int wsKind = grammar.whitespace()
                            .isPresent()
                     ? DfaBuilder.KIND_WHITESPACE
                     : - 1;
        var engine = new LexerEngine(built.dfa(),
                                     built.kinds()
                                          .kindNameTable(),
                                     wsKind,
                                     built.kinds()
                                          .keywordResolutions());
        return new Built(grammar, classification, built, engine);
    }

    private static int countParserRules(RuleClassifier.Classification c) {
        int n = 0;
        for (var k : c.kinds()
                      .values()) {
            if (k == RuleKind.PARSER || k == RuleKind.MIXED) {
                n++ ;
            }
        }
        return n;
    }

    @Test
    void generate_emitsOneVisitMethodPerParserRule() {
        var built = buildAll("""
            Sum <- Number '+' Number
            Number <- [0-9]+
            """);
        var generated = VisitorGenerator.generate(built.grammar(),
                                                  built.classification(),
                                                  "test.gen.visitor.sum",
                                                  "SumVisitor")
                                        .unwrap();
        assertThat(generated.fullyQualifiedName())
        .isEqualTo("test.gen.visitor.sum.SumVisitor");
        assertThat(generated.source())
        .contains("public abstract class SumVisitor<T>");
        assertThat(generated.source())
        .contains("public T visitSum(CstArray cst, int nodeIdx)");
        // Number is LEXER — no visit method emitted.
        assertThat(generated.source())
        .doesNotContain("visitNumber");
        assertThat(generated.source())
        .contains("default -> defaultResult();");
    }

    @Test
    void compileAndDispatch_returnsAggregatedValue() throws Exception {
        // Sum has Term children; Term wraps Number with a parenthesised alternative
        // so Term stays PARSER (else it'd alias to Number/LEXER). Sum visit
        // dispatches through visitTerm; we override visitTerm to extract its leaf
        // token text and sum the integer values via aggregateResult.
        var built = buildAll("""
            Sum <- Term '+' Term
            Term <- '(' Number ')' / Number
            Number <- [0-9]+
            """);
        var generatedParser = ParserGenerator.generate(built.grammar(),
                                                       built.classification(),
                                                       built.dfa()
                                                            .kinds(),
                                                       "test.gen.visitor.dispatch",
                                                       "DispatchParser")
                                             .unwrap();
        var generatedVisitor = VisitorGenerator.generate(built.grammar(),
                                                         built.classification(),
                                                         "test.gen.visitor.dispatch",
                                                         "DispatchVisitor")
                                               .unwrap();
        var subFqcn = "test.gen.visitor.dispatch.DispatchVisitorSub";
        var subSource = """
            package test.gen.visitor.dispatch;
            import org.pragmatica.peg.v6.cst.CstArray;
            public final class DispatchVisitorSub extends DispatchVisitor<Integer> {
                @Override public Integer visitTerm(CstArray cst, int nodeIdx) {
                    return Integer.parseInt(cst.textAt(nodeIdx).toString());
                }
                @Override public Integer visitSum(CstArray cst, int nodeIdx) {
                    return visitChildren(cst, nodeIdx);
                }
                @Override protected Integer defaultResult() { return 0; }
                @Override protected Integer aggregateResult(Integer agg, Integer next) {
                    return agg + (next == null ? 0 : next);
                }
            }
            """;
        var compiled = compileAll(List.of(
        new SourceUnit(generatedParser.fullyQualifiedName(), generatedParser.source()),
        new SourceUnit(generatedVisitor.fullyQualifiedName(), generatedVisitor.source()),
        new SourceUnit(subFqcn, subSource)));
        var parserClass = compiled.load(generatedParser.fullyQualifiedName());
        var visitorClass = compiled.load(generatedVisitor.fullyQualifiedName());
        var subClass = compiled.load(subFqcn);
        var tokens = built.engine()
                          .lex("3+5");
        var parseResult = (ParseResult) parserClass.getDeclaredMethod("parse",
                                                                      org.pragmatica.peg.v6.token.TokenArray.class)
                                                               .invoke(null, tokens);
        var cst = parseResult.cst();
        assertThat(parseResult.diagnostics())
        .isEmpty();
        var instance = subClass.getDeclaredConstructor()
                               .newInstance();
        var visitMethod = visitorClass.getMethod("visit", CstArray.class, int.class);
        int sumNode = cst.firstChildAt(cst.rootIndex());
        var result = visitMethod.invoke(instance, cst, sumNode);
        assertThat(result)
        .isEqualTo(8);
    }

    @Test
    void defaultResultAndAggregator_areOverridable() throws Exception {
        // Pair must stay PARSER (wrapping with a literal keeps the classifier from
        // aliasing it). Word is also PARSER so visitPair walks two children
        // through visitChildren -> aggregateResult, exercising both overrides.
        var built = buildAll("""
            Pair <- Word '#' Word
            Word <- 'x' Tag
            Tag <- '!'
            """);
        var generatedParser = ParserGenerator.generate(built.grammar(),
                                                       built.classification(),
                                                       built.dfa()
                                                            .kinds(),
                                                       "test.gen.visitor.agg",
                                                       "AggParser")
                                             .unwrap();
        var generatedVisitor = VisitorGenerator.generate(built.grammar(),
                                                         built.classification(),
                                                         "test.gen.visitor.agg",
                                                         "AggVisitor")
                                               .unwrap();
        var subFqcn = "test.gen.visitor.agg.AggVisitorSub";
        var subSource = """
            package test.gen.visitor.agg;
            public final class AggVisitorSub extends AggVisitor<String> {
                @Override protected String defaultResult() { return "INIT"; }
                @Override protected String aggregateResult(String agg, String next) {
                    return agg + "|" + (next == null ? "null" : next);
                }
            }
            """;
        var compiled = compileAll(List.of(
        new SourceUnit(generatedParser.fullyQualifiedName(), generatedParser.source()),
        new SourceUnit(generatedVisitor.fullyQualifiedName(), generatedVisitor.source()),
        new SourceUnit(subFqcn, subSource)));
        var parserClass = compiled.load(generatedParser.fullyQualifiedName());
        var visitorClass = compiled.load(generatedVisitor.fullyQualifiedName());
        var subClass = compiled.load(subFqcn);
        var tokens = built.engine()
                          .lex("x!#x!");
        var parseResult = (ParseResult) parserClass.getDeclaredMethod("parse",
                                                                      org.pragmatica.peg.v6.token.TokenArray.class)
                                                               .invoke(null, tokens);
        var cst = parseResult.cst();
        assertThat(parseResult.diagnostics())
        .isEmpty();
        var instance = subClass.getDeclaredConstructor()
                               .newInstance();
        var visitMethod = visitorClass.getMethod("visit", CstArray.class, int.class);
        int pairNode = cst.firstChildAt(cst.rootIndex());
        var result = visitMethod.invoke(instance, cst, pairNode);
        // Visiting a Word returns defaultResult() = "INIT". Pair has two Word
        // parser children (the '#' literal is a lexer token without a CST node),
        // so the aggregator runs twice from the "INIT" seed.
        assertThat(result)
        .isEqualTo("INIT|INIT|INIT");
    }

    @Test
    void visitMethodCount_matchesParserRuleCount() {
        var built = buildAll("""
            A <- B C
            B <- 'b' D
            C <- 'c' D
            D <- 'd'
            """);
        var generated = VisitorGenerator.generate(built.grammar(),
                                                  built.classification(),
                                                  "test.gen.visitor.count",
                                                  "CountVisitor")
                                        .unwrap();
        var ruleKinds = ParserGenerator.allocateParserRuleKinds(built.grammar(), built.classification());
        var src = generated.source();
        int dispatchOccurrences = countOccurrences(src, "public T visit(CstArray cst, int nodeIdx)");
        assertThat(dispatchOccurrences)
        .isEqualTo(1);
        for (var ruleName : ruleKinds.keySet()) {
            assertThat(src)
            .contains("public T visit" + ruleName + "(CstArray cst, int nodeIdx)");
        }
        // Framework helpers present.
        assertThat(src)
        .contains("protected T visitChildren(");
        assertThat(src)
        .contains("protected T defaultResult()");
        assertThat(src)
        .contains("protected T aggregateResult(");
    }

    @Test
    void java25Grammar_visitorCompiles() throws IOException {
        var grammarText = Files.readString(
        Paths.get("src/test/resources/java25.peg"), StandardCharsets.UTF_8);
        var built = buildAll(grammarText);
        var generated = VisitorGenerator.generate(built.grammar(),
                                                  built.classification(),
                                                  "test.gen.visitor.java25",
                                                  "Java25Visitor")
                                        .unwrap();
        var ruleKinds = ParserGenerator.allocateParserRuleKinds(built.grammar(), built.classification());
        assertThat(ruleKinds)
        .hasSize(countParserRules(built.classification()));
        var compiled = compileAll(List.of(
        new SourceUnit(generated.fullyQualifiedName(), generated.source())));
        // Loading proves bytecode is well-formed and definable.
        compiled.load(generated.fullyQualifiedName());
        System.out.println("[VisitorGenerator:java25] source bytes = " + generated.source()
                                                                                  .length() + ", visit methods = " + ruleKinds.size());
    }

    @Test
    void invalidPackage_isRejected() {
        var built = buildAll("""
            Sum <- Number '+' Number
            Number <- [0-9]+
            """);
        Result<VisitorGenerator.GeneratedVisitor> r = VisitorGenerator.generate(
        built.grammar(), built.classification(), "1bad", "V");
        assertThat(r.isFailure())
        .isTrue();
    }

    @Test
    void invalidClassName_isRejected() {
        var built = buildAll("""
            Sum <- Number '+' Number
            Number <- [0-9]+
            """);
        Result<VisitorGenerator.GeneratedVisitor> r = VisitorGenerator.generate(
        built.grammar(), built.classification(), "p", "1bad");
        assertThat(r.isFailure())
        .isTrue();
    }

    @Test
    void emptyPackage_emitsNoPackageStatement() {
        var built = buildAll("""
            Sum <- Number '+' Number
            Number <- [0-9]+
            """);
        var generated = VisitorGenerator.generate(built.grammar(),
                                                  built.classification(),
                                                  "",
                                                  "RootVisitor")
                                        .unwrap();
        assertThat(generated.source())
        .doesNotContain("package ");
        assertThat(generated.fullyQualifiedName())
        .isEqualTo("RootVisitor");
    }

    @Test
    void parserAndVisitor_kindAllocationsAgree() {
        var built = buildAll("""
            A <- B C
            B <- 'b' D
            C <- 'c' D
            D <- 'd'
            """);
        var ruleKinds = ParserGenerator.allocateParserRuleKinds(built.grammar(), built.classification());
        var parserSrc = ParserGenerator.generate(built.grammar(),
                                                 built.classification(),
                                                 built.dfa()
                                                      .kinds(),
                                                 "p",
                                                 "P")
                                       .unwrap()
                                       .source();
        for (var e : ruleKinds.entrySet()) {
            assertThat(parserSrc)
            .contains("RULE_" + e.getKey() + "_KIND = " + e.getValue() + ";");
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != - 1) {
            count++ ;
            idx += needle.length();
        }
        return count;
    }

    /* ---------- in-memory compile + load helpers ---------- */
    private record SourceUnit(String fqcn, String source) {}

    private record CompiledBundle(Map<String, byte[] > bytecode, ClassLoader loader) {
        Class< ? > load(String fqcn) {
            try{
                return loader.loadClass(fqcn);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static CompiledBundle compileAll(List<SourceUnit> units) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No Java compiler available");
        }
        try (var standard = compiler.getStandardFileManager(null, null, null)) {
            var fileManager = new InMemoryFileManager(standard);
            var sources = new ArrayList<JavaFileObject>(units.size());
            for (var u : units) {
                sources.add(new StringJavaFileObject(u.fqcn(), u.source()));
            }
            var diagnostics = new StringWriter();
            var task = compiler.getTask(diagnostics, fileManager, null, List.of("--release", "25"), null, sources);
            if (!task.call()) {
                throw new RuntimeException("compile failed:\n" + diagnostics);
            }
            var bytecode = new HashMap<String, byte[] >();
            for (var u : units) {
                var bytes = fileManager.classBytes(u.fqcn());
                if (bytes == null) {
                    throw new RuntimeException("no bytes for " + u.fqcn());
                }
                bytecode.put(u.fqcn(), bytes);
            }
            // Inner classes etc. — flush all emitted files.
            for (var entry : fileManager.allClassBytes()
                                        .entrySet()) {
                bytecode.putIfAbsent(entry.getKey(), entry.getValue());
            }
            var loader = new BytesClassLoader(bytecode, VisitorGeneratorTest.class.getClassLoader());
            return new CompiledBundle(bytecode, loader);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final class StringJavaFileObject extends SimpleJavaFileObject {
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

    private static final class ByteArrayJavaFileObject extends SimpleJavaFileObject {
        private byte[] bytes;

        ByteArrayJavaFileObject(String className) {
            super(URI.create("bytes:///" + className.replace('.', '/') + Kind.CLASS.extension),
                  Kind.CLASS);
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

        byte[] classBytes(String className) {
            var f = classFiles.get(className);
            return f == null
                   ? null
                   : f.bytes();
        }

        Map<String, byte[] > allClassBytes() {
            var out = new HashMap<String, byte[] >();
            for (var e : classFiles.entrySet()) {
                var b = e.getValue()
                         .bytes();
                if (b != null) {
                    out.put(e.getKey(), b);
                }
            }
            return out;
        }
    }

    private static final class BytesClassLoader extends ClassLoader {
        private final Map<String, byte[] > bytecode;

        BytesClassLoader(Map<String, byte[] > bytecode, ClassLoader parent) {
            super(parent);
            this.bytecode = bytecode;
        }

        @Override
        protected Class< ? > findClass(String name) throws ClassNotFoundException {
            var bytes = bytecode.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
