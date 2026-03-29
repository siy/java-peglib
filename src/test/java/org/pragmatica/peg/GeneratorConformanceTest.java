package org.pragmatica.peg;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.generator.ErrorReporting;

import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive conformance tests comparing interpreted and generated CST parsers.
 * For each grammar pattern, both parsers must agree on success/failure.
 */
class GeneratorConformanceTest {

    private static final AtomicInteger CLASS_COUNTER = new AtomicInteger(0);

    // --- Bug 2 grammar: multi-alternative with optional suffix ---

    static final String BUG2_GRAMMAR = """
        Input <- Expr
        Expr <- FuncCall / ColRef / Literal
        FuncCall <- FuncName '(' ArgList ')' OverClause? / FuncName '(' '*' ')' OverClause? / FuncName '(' ')' OverClause?
        FuncName <- < [a-zA-Z_]+ >
        ArgList <- Expr (',' Expr)*
        ColRef <- < [a-zA-Z_]+ >
        Literal <- < [0-9]+ >
        OverClause <- OverKW '(' WindowSpec ')'
        WindowSpec <- OrderClause?
        OrderClause <- OrderKW ByKW Expr SortDir?
        SortDir <- AscKW / DescKW
        OverKW <- < 'OVER'i ![a-zA-Z0-9_] >
        OrderKW <- < 'ORDER'i ![a-zA-Z0-9_] >
        ByKW <- < 'BY'i ![a-zA-Z0-9_] >
        AscKW <- < 'ASC'i ![a-zA-Z0-9_] >
        DescKW <- < 'DESC'i ![a-zA-Z0-9_] >
        %whitespace <- [ \\t\\r\\n]*
        """;

    @Test
    void bug2_funcCallWithArg() throws Exception {
        assertConformance(BUG2_GRAMMAR, "count(1)", true);
    }

    @Test
    void bug2_funcCallEmpty() throws Exception {
        assertConformance(BUG2_GRAMMAR, "rank()", true);
    }

    @Test
    void bug2_funcCallStar() throws Exception {
        assertConformance(BUG2_GRAMMAR, "count(*)", true);
    }

    @Test
    void bug2_funcCallWithOverClauseDesc() throws Exception {
        assertConformance(BUG2_GRAMMAR, "rank() OVER (ORDER BY x DESC)", true);
    }

    @Test
    void bug2_funcCallArgWithOverClause() throws Exception {
        assertConformance(BUG2_GRAMMAR, "count(1) OVER (ORDER BY x)", true);
    }

    @Test
    void bug2_funcCallStarWithOverClause() throws Exception {
        assertConformance(BUG2_GRAMMAR, "count(*) OVER (ORDER BY x)", true);
    }

    // --- Predicates in sequences ---

    static final String PREDICATE_GRAMMAR = """
        Root <- Item+
        Item <- !Keyword Ident
        Keyword <- 'if' / 'then' / 'else'
        Ident <- < [a-z]+ >
        %whitespace <- [ ]+
        """;

    @Test
    void predicates_validIdentifiers() throws Exception {
        assertConformance(PREDICATE_GRAMMAR, "foo bar", true);
    }

    @Test
    void predicates_keywordBlocked() throws Exception {
        assertConformance(PREDICATE_GRAMMAR, "foo if", false);
    }

    // --- Optional wrapping reference ---

    static final String OPTIONAL_REF_GRAMMAR = """
        Root <- A B? C
        A <- 'a'
        B <- 'b'
        C <- 'c'
        %whitespace <- [ ]*
        """;

    @Test
    void optionalRef_allPresent() throws Exception {
        assertConformance(OPTIONAL_REF_GRAMMAR, "a b c", true);
    }

    @Test
    void optionalRef_optionalAbsent() throws Exception {
        assertConformance(OPTIONAL_REF_GRAMMAR, "a c", true);
    }

    @Test
    void optionalRef_missingRequired() throws Exception {
        assertConformance(OPTIONAL_REF_GRAMMAR, "a b", false);
    }

    // --- Nested choices with optionals ---

    static final String NESTED_CHOICE_GRAMMAR = """
        Root <- (A / B) C?
        A <- 'x' 'y'
        B <- 'x' 'z'
        C <- 'w'
        %whitespace <- [ ]*
        """;

    @Test
    void nestedChoice_firstAlternative() throws Exception {
        assertConformance(NESTED_CHOICE_GRAMMAR, "x y", true);
    }

    @Test
    void nestedChoice_secondAlternative() throws Exception {
        assertConformance(NESTED_CHOICE_GRAMMAR, "x z", true);
    }

    @Test
    void nestedChoice_firstWithOptional() throws Exception {
        assertConformance(NESTED_CHOICE_GRAMMAR, "x y w", true);
    }

    @Test
    void nestedChoice_secondWithOptional() throws Exception {
        assertConformance(NESTED_CHOICE_GRAMMAR, "x z w", true);
    }

    // --- ZeroOrMore with whitespace ---

    static final String ZERO_OR_MORE_GRAMMAR = """
        Root <- Item*
        Item <- < [a-z]+ >
        %whitespace <- [ ]+
        """;

    @Test
    void zeroOrMore_multipleItems() throws Exception {
        assertConformance(ZERO_OR_MORE_GRAMMAR, "foo bar baz", true);
    }

    @Test
    void zeroOrMore_singleItem() throws Exception {
        assertConformance(ZERO_OR_MORE_GRAMMAR, "foo", true);
    }

    @Test
    void zeroOrMore_emptyInput() throws Exception {
        assertConformance(ZERO_OR_MORE_GRAMMAR, "", true);
    }

    // --- Token boundaries with predicates ---

    static final String TOKEN_PREDICATE_GRAMMAR = """
        Root <- Kw
        Kw <- < 'for'i ![a-zA-Z0-9_] >
        %whitespace <- [ ]*
        """;

    @Test
    void tokenPredicate_exactMatch() throws Exception {
        assertConformance(TOKEN_PREDICATE_GRAMMAR, "for", true);
    }

    @Test
    void tokenPredicate_caseInsensitive() throws Exception {
        assertConformance(TOKEN_PREDICATE_GRAMMAR, "FOR", true);
    }

    @Test
    void tokenPredicate_prefixFails() throws Exception {
        assertConformance(TOKEN_PREDICATE_GRAMMAR, "foreach", false);
    }

    // --- Cut in alternatives ---

    static final String CUT_GRAMMAR = """
        Root <- Stmt
        Stmt <- 'let' ^ Ident '=' Expr / Expr
        Ident <- < [a-z]+ >
        Expr <- < [0-9]+ >
        %whitespace <- [ ]*
        """;

    @Test
    void cut_letStatement() throws Exception {
        assertConformance(CUT_GRAMMAR, "let x = 5", true);
    }

    @Test
    void cut_expressionFallback() throws Exception {
        assertConformance(CUT_GRAMMAR, "42", true);
    }

    // --- Repetition ---

    static final String REPETITION_GRAMMAR = """
        Root <- < [a-z] >{2,4}
        %whitespace <- [ ]*
        """;

    @Test
    void repetition_exactlyThree() throws Exception {
        assertConformance(REPETITION_GRAMMAR, "a b c", true);
    }

    @Test
    void repetition_exactlyTwo() throws Exception {
        assertConformance(REPETITION_GRAMMAR, "a b", true);
    }

    @Test
    void repetition_exactlyFour() throws Exception {
        assertConformance(REPETITION_GRAMMAR, "a b c d", true);
    }

    @Test
    void repetition_tooFew() throws Exception {
        assertConformance(REPETITION_GRAMMAR, "a", false);
    }

    @Test
    void repetition_tooMany() throws Exception {
        assertConformance(REPETITION_GRAMMAR, "a b c d e", false);
    }

    // --- Conformance helper ---

    private void assertConformance(String grammar, String input, boolean expectedSuccess) throws Exception {
        var interpretedParser = PegParser.fromGrammar(grammar).unwrap();
        var interpretedResult = interpretedParser.parseCst(input);

        var classIndex = CLASS_COUNTER.incrementAndGet();
        var className = "Conformance" + classIndex;
        var packageName = "test.conformance.p" + classIndex;
        var fqcn = packageName + "." + className;

        var sourceResult = PegParser.generateCstParser(grammar, packageName, className, ErrorReporting.BASIC);
        assertThat(sourceResult.isSuccess())
            .as("Source generation must succeed")
            .isTrue();

        var parser = compileAndInstantiate(sourceResult.unwrap(), fqcn);
        var parseMethod = parser.getClass().getMethod("parse", String.class);

        @SuppressWarnings("unchecked")
        var generatedResult = (Result<Object>) parseMethod.invoke(parser, input);

        assertThat(interpretedResult.isSuccess())
            .as("Interpreted parser result for input '%s'", input)
            .isEqualTo(expectedSuccess);

        assertThat(generatedResult.isSuccess())
            .as("Generated parser result for input '%s'", input)
            .isEqualTo(expectedSuccess);

        assertThat(generatedResult.isSuccess())
            .as("Generated parser must match interpreted parser for input '%s'", input)
            .isEqualTo(interpretedResult.isSuccess());
    }

    private Object compileAndInstantiate(String source, String className) throws Exception {
        var tempDir = Files.createTempDirectory("peglib-conformance");
        var packagePath = className.substring(0, className.lastIndexOf('.')).replace('.', '/');
        var simpleClassName = className.substring(className.lastIndexOf('.') + 1);

        var packageDir = tempDir.resolve(packagePath);
        Files.createDirectories(packageDir);

        var sourceFile = packageDir.resolve(simpleClassName + ".java");
        Files.writeString(sourceFile, source);

        var compiler = ToolProvider.getSystemJavaCompiler();
        var errStream = new java.io.ByteArrayOutputStream();
        var result = compiler.run(null, null, errStream,
            "-d", tempDir.toString(),
            "-cp", System.getProperty("java.class.path"),
            sourceFile.toString()
        );

        if (result != 0) {
            System.err.println("=== Compilation errors ===");
            System.err.println(errStream);
            throw new RuntimeException("Compilation failed for " + className + ": " + errStream);
        }

        var classLoader = new URLClassLoader(new URL[]{tempDir.toUri().toURL()});
        var parserClass = classLoader.loadClass(className);
        return parserClass.getDeclaredConstructor().newInstance();
    }
}
