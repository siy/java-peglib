package org.pragmatica.peg.diagnostic;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import org.pragmatica.peg.PegParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/**
 * The mirror of {@link JavaCoverageProbe}: constructs that must be REJECTED.
 *
 * <p>A coverage probe alone cannot detect an over-permissive grammar — a grammar that accepted
 * every byte sequence would pass it perfectly. These cases were found by differencing the
 * grammar against javac's own parse phase over the OpenJDK langtools corpus
 * ({@code tools/langtools-corpus/}), where each showed up as a FALSE ACCEPT: javac reports a
 * syntax error, we did not.
 *
 * <p>Every case is paired with a legal near-miss in {@link #legalNearMisses()}. That pairing is
 * the point: the cheap way to make a rejection case pass is to over-tighten the rule until it
 * also rejects valid code, and the near-miss is what catches that. {@code 1_} must fail while
 * {@code 1__0L} must still parse.
 *
 * <p>Scope note: this probe deliberately does NOT encode javac checks that are not context-free
 * — numeric range ({@code compiler.err.int.number.too.large}), duplicate modifiers, or filename
 * agreement. Those live in javac's parser for convenience, not because they are grammar. See
 * {@code tools/langtools-corpus/README.md} for the enumerated waiver list.
 */
public class JavaRejectionProbe {

    private static LinkedHashMap<String, String> mustReject() {
        var cases = new LinkedHashMap<String, String>();

        // --- JLS 3.10.1: underscores may appear only BETWEEN digits ---
        cases.put("trailing-underscore", "class A { long x = 1_; }");
        cases.put("underscore-before-dot", "class A { double d = 1_.5; }");
        cases.put("underscore-after-dot", "class A { double d = 1._5; }");
        cases.put("hex-leading-underscore", "class A { int x = 0x_1; }");
        cases.put("underscore-before-suffix", "class A { long x = 1_L; }");
        cases.put("binary-trailing-underscore", "class A { int x = 0b1_; }");

        // --- JLS 3.10.6: the escape set is closed ---
        cases.put("bad-escape-e", "class A { String s = \"\\e\"; }");
        cases.put("bad-escape-q", "class A { char c = '\\q'; }");

        // --- JLS 3.10.4: a char literal holds exactly one element ---
        cases.put("empty-char-lit", "class A { char c = ''; }");

        // --- JLS 8.4.1: varargs and C-style array brackets cannot be combined ---
        cases.put("varargs-and-old-array", "class A { void m(int... x[]) { } }");
        cases.put("varargs-and-old-array-ref", "class A { void m(String... s[]) { } }");

        // --- JLS 3.9: 'var' and 'yield' are restricted TYPE names ---
        cases.put("var-as-field-type", "class A { var v; }");
        cases.put("var-as-param-type", "class A { void m(var x) { } }");
        cases.put("var-as-return-type", "class A { var m() { return null; } }");
        cases.put("var-as-class-name", "class var { }");
        cases.put("var-as-interface-name", "interface var { }");
        cases.put("yield-as-class-name", "class yield { }");
        cases.put("var-multiple-declarators", "class A { void m() { var x = 1, y = 2; } }");

        // --- JLS 9.1.4: an interface body is not a class body ---
        cases.put("interface-field-no-initializer", "interface I { int X; }");
        cases.put("interface-instance-initializer", "interface I { { } }");
        cases.put("interface-static-initializer", "interface I { static { } }");
        cases.put("interface-constructor", "interface I { I(Object... args) { } }");

        // --- JLS 14.20: a try needs resources, or a catch, or a finally ---
        cases.put("try-alone", "class A { void m() { try { } } }");

        // --- JLS 8.4.1 / 14.11.1 / 7.5 / 4.3 ---
        cases.put("varargs-not-last", "class A { void m(String... a, String b) { } }");
        cases.put("underscore-with-brackets", "class A { void m() { int[] _[] = null; } }");
        cases.put("guard-on-constant-label", "class A { void m(Object o, boolean b) { switch (o) { case 0 when b -> { } default -> { } } } }");
        cases.put("unqualified-import", "import Dummy;");
        cases.put("void-array-type", "class A { void[] a = null; }");

        // --- JLS 14.3 / 8.4.1 / 14.30.1 / 15.8.2 ---
        cases.put("static-local-class", "class A { void m() { static class Y { } } }");
        cases.put("receiver-param-not-first", "class A { void m(int any, A A.this) { } }");
        cases.put("instanceof-var-pattern", "class A { void m(Object o) { if (o instanceof var v) { } } }");
        cases.put("annotated-class-literal", "class A { Object o = @A Object.class; }");
        cases.put("twr-bare-creation-resource", "class A implements AutoCloseable { public void close() { } void m() { try (new A()) { } } }");

        // --- JEP 456: bare '_' is a variable name only, never a member or type name ---
        cases.put("underscore-as-field", "class A { int _; }");
        cases.put("underscore-as-method", "class A { void _() { } }");
        cases.put("underscore-as-param", "class A { void m(int _) { } }");

        // --- JLS 15.27.1: a lambda's parameter list must be uniform ---
        cases.put("lambda-mixed-var-inferred", "class A { Object o = (var x, y) -> x; }");
        cases.put("lambda-mixed-typed-inferred", "class A { Object o = (Integer a, b) -> a; }");
        cases.put("lambda-mixed-typed-var", "class A { Object o = (Integer a, var b) -> a; }");

        // --- JLS 15.8.2: a class literal is 'TypeName {[]} . class', not a postfix operator ---
        cases.put("this-dot-class", "class A { Object o = this.class; }");

        // --- JLS 14.8: not every expression is a statement ---
        cases.put("parenthesised-not-stmt", "class A { void m(int a) { (a); } }");
        cases.put("field-access-not-stmt", "class A { void m(A a) { a.b; } }");
        cases.put("bare-yield-not-stmt", "class A { void m() { yield; } }");
        cases.put("method-ref-not-stmt", "class A { void m() { A::new; } }");
        cases.put("literal-not-stmt", "class A { void m() { 1; } }");

        // --- JLS 15.10.1: an array's element type must be reifiable ---
        cases.put("array-with-type-arguments", "class A { Object o = new java.util.List<String>[10]; }");
        cases.put("array-with-diamond", "class A { Object o = new java.util.List<>[10]; }");

        // --- JLS 14.3 / JEP 512 ---
        cases.put("sealed-local-class", "class A { void m() { sealed class L { } } }");
        cases.put("compact-source-with-package", "package p;\nvoid main() { }\n");

        // JLS 7.3: a stray ';' after the imports is itself a TypeDeclaration, so a later
        // import is illegal. This used to pass because the recovery loop silently re-parsed
        // the remainder as a SECOND compilation unit.
        cases.put("import-after-extraneous-semicolon", "import java.util.Map;;\nimport java.util.Set;\nclass Foo { }\n");
        cases.put("two-concatenated-units", "class A { }\nimport java.util.Map;\nclass B { }\n");

        // --- JLS 8.10.4: a record's state is exactly its components ---
        cases.put("record-instance-field", "record R(int x) { int y; }");
        cases.put("record-instance-initializer", "record R(int x) { { } }");
        cases.put("nested-record-instance-field", "class A { record N(int x) { int y; } }");

        // NOTE: record instance fields / instance initializers are NOT gated here. They are
        // over-accepted today and cannot be fixed in the grammar alone — see the record-body
        // entry in tools/langtools-corpus/README.md.

        return cases;
    }

    /**
     * Legal constructs that sit one character away from a rejection case. Each guards against
     * the corresponding rule being tightened past the point of correctness.
     */
    private static LinkedHashMap<String, String> legalNearMisses() {
        var cases = new LinkedHashMap<String, String>();

        cases.put("runs-of-underscores", "class A { long x = 1__0L; }");
        cases.put("underscores-all-parts", "class A { double d = 1_0.5_0e1_0; }");
        cases.put("binary-and-hex-underscores", "class A { int y = 0b1010_1010; int z = 0xDEAD_BEEF; }");
        cases.put("hex-float", "class A { double d = 0x1.8p3; float f = 0x1p-2f; }");
        cases.put("trailing-dot-double", "class A { double d = 1.; double e = .5; }");
        cases.put("octal-and-space-escape", "class A { String s = \"\\0\\377\\s\"; }");
        cases.put("unicode-escape", "class A { String s = \"\\u0041\"; }");
        cases.put("quote-escapes", "class A { char c = '\\''; char d = '\\\\'; String s = \"\\\"\"; }");
        cases.put("text-block", "class A { String s = \"\"\"\n  hi\n  \"\"\"; }");
        cases.put("varargs-alone", "class A { void m(int... x) { } }");
        cases.put("array-param-alone", "class A { void m(int x[], String s[][]) { } }");
        cases.put("varargs-generic", "class A { void m(java.util.List<String>... x) { } }");
        cases.put("receiver-param", "class A { void m(A this, int x[]) { } }");

        // 'var' and 'yield' stay perfectly legal as ordinary identifiers — they are the two
        // most common contextual keywords in real code, so the restriction above must not
        // leak out of type-use position.
        cases.put("var-as-identifier", "class A { int var = 3; void m() { var = 4; } }");
        cases.put("var-local-inference", "class A { void m() { var x = 3; } }");
        cases.put("var-in-for-each", "class A { void m(int[] a) { for (var x : a) { } } }");
        cases.put("var-single-declarator", "class A { void m() { var x = 1; } }");
        cases.put("multi-declarators-typed", "class A { void m() { int x = 1, y = 2; } }");
        cases.put("for-init-var-and-typed", "class A { void m() { for (var i = 0; i < 3; i++) { } for (int i = 0, j = 1; i < 3; i++) { } } }");
        cases.put("yield-as-identifier", "class A { int yield = 1; int m() { return yield; } }");
        cases.put("yield-in-switch", "class A { int m(int x) { return switch (x) { case 1 -> { yield 2; } default -> 0; }; } }");

        // Interface and record shapes that must survive the per-container body rules. The
        // JEP 512 compact source file matters most: the !RecordKW guard that stops a broken
        // record being re-read as a method named R returning type 'record' must not disturb it.
        cases.put("interface-initialized-field", "interface I { int X = 1; }");
        cases.put("interface-multi-field", "interface I { int A = 1, B = 2; }");
        cases.put("interface-method-shapes", "interface I { void m(); default void d() { } static void s() { } private void p() { } }");
        cases.put("interface-generic-extends-nested", "interface I<T> extends java.util.List<T> { class Nested { } }");
        cases.put("class-keeps-initializers", "class A { int x; { } static { } A() { } }");

        // Records and JEP 512 compact source files, pinned so a future attempt at the
        // record-body restriction cannot quietly break them.
        cases.put("record-static-field", "record R(int x) { static final int Y = 1; }");
        cases.put("record-compact-and-method", "record R(int x) { R { } int m() { return x; } }");
        cases.put("record-canonical-ctor", "record R(int x) { R(int x, int y) { this(x); } }");
        cases.put("nested-record-static-field", "class A { record N(int x) { static int Y = 1; } }");
        cases.put("compact-source-file", "void main() { }");
        cases.put("imports-before-module", "import a.Ann;\n@Ann\nmodule mod { requires b; }\n");
        cases.put("plain-module", "module mod { }");
        cases.put("open-module-exports", "open module mod { exports a to b; }");
        cases.put("trailing-semicolon-after-class", "class Foo { };");

        // try in each of its legal shapes.
        cases.put("try-finally", "class A { void m() { try { } finally { } } }");
        cases.put("try-catch", "class A { void m() { try { } catch (Exception e) { } } }");
        cases.put("try-resources", "class A { void m() throws Exception { try (var r = open()) { } } AutoCloseable open() { return null; } }");

        // '_' in every position JEP 456 actually allows. The typed pattern binding
        // ('case String _') is the one the bare-'_' alternative misses.
        cases.put("underscore-local", "class A { void m() { var _ = 1; } }");
        cases.put("underscore-for-each", "class A { void m(int[] a) { for (var _ : a) { } } }");
        cases.put("underscore-catch-param", "class A { void m() { try { } catch (Exception _) { } } }");
        cases.put("underscore-lambda-param", "class A { void m() { java.util.List.of(1).forEach(_ -> { }); } }");
        cases.put("underscore-bare-pattern", "class A { void m(Object o) { switch (o) { case _ -> { } } } }");
        cases.put("underscore-typed-pattern", "class A { void m(Object o) { switch (o) { case String _ when true -> { } default -> { } } } }");
        cases.put("underscore-record-pattern", "class A { void m(Object o) { if (o instanceof P(String _, var y)) { } } }");
        cases.put("underscore-prefixed-identifiers", "class A { int _x = 1; int __ = 2; int _9 = 3; }");

        // Every uniform lambda parameter shape.
        cases.put("lambda-inferred-list", "class A { Object o = (a, b) -> a; }");
        cases.put("lambda-typed-list", "class A { Object o = (Integer a, Integer b) -> a; }");
        cases.put("lambda-var-list", "class A { Object o = (var a, var b) -> a; }");
        cases.put("lambda-empty-and-single", "class A { Object o = () -> 1; Object p = x -> x; }");
        cases.put("lambda-modifiers-annotations", "class A { Object o = (final var a, @Ann var b) -> a; }");
        cases.put("lambda-varargs-param", "class A { Object o = (int... xs) -> xs; }");
        cases.put("lambda-underscore-param", "class A { Object o = (_, y) -> y; }");

        // Every legal class-literal shape must survive removing '.class' from PostOp.
        cases.put("class-literal-simple", "class A { Object o = String.class; }");
        cases.put("class-literal-qualified", "class A { Object o = java.util.Map.class; }");
        cases.put("class-literal-primitive", "class A { Object o = int.class; void m() { Object v = void.class; } }");
        cases.put("class-literal-array", "class A { Object o = String[].class; Object p = int[][].class; }");
        cases.put("qualified-this", "class A { class B { Object o = A.this; } }");
        cases.put("method-ref-still-works", "class A { Object o = String::valueOf; }");
        cases.put("annotated-local-var", "class A { void m() { @Ann var v = \"\"; } }");

        // An unbounded wildcard IS reifiable, so this stays legal — the trap that broke
        // 12 files on the first attempt at the array rule.
        cases.put("array-of-wildcard", "class A { Object o = new Class<?>[0]; Object p = new Class<?>[]{ }; }");
        cases.put("array-creation-shapes", "class A { Object o = new int[3][4]; Object p = new String[]{\"a\"}; }");
        cases.put("local-class-plain", "class A { void m() { class L { } } }");
        cases.put("package-with-class", "package p;\nclass A { }\n");

        // Every JLS 14.8 statement-expression shape. The chain is spelled right-recursively
        // because PEG repetition is possessive, so these pin the shapes that broke while
        // getting that right: 'this.foo()' (PostOp swallowed the call), a mid-chain call,
        // qualified 'new', and an explicit constructor invocation with type arguments.
        cases.put("stmt-invocations", "class A { void m() { foo(); this.foo(); a.b.c(); } void foo() { } }");
        cases.put("stmt-chained-calls", "class A { void m() { m().n().o(); } A m() { return this; } }");
        cases.put("stmt-mid-chain-call", "class A { void m() { java.util.List.of(1).forEach(x -> { }); } }");
        cases.put("stmt-super-call", "class A { void m() { super.hashCode(); } }");
        cases.put("stmt-generic-call", "class A { void m() { this.<String>foo(); Test.<X,Y>bar(); } <T> void foo() { } }");
        cases.put("stmt-assignments", "class A { void m(int x, int[] r) { x = 1; x += 2; r[0] = 1; } }");
        cases.put("stmt-inc-dec", "class A { void m(int i, int[] r) { i++; ++i; i--; --i; r[0]++; } }");
        cases.put("stmt-instance-creation", "class A { void m() { new A(); new A().m(); } }");
        cases.put("stmt-qualified-new", "class A { void m(A a) { a.new B(); new A().new B(); } class B { } }");
        cases.put("stmt-explicit-ctor-invocation", "class A extends B { A() { <T,E>super(); } } class B { }");
        cases.put("stmt-this-super-ctor", "class A { A() { this(1); } A(int x) { } }");
        cases.put("record-as-identifier", "class A { int record; void record() { } }");
        cases.put("varargs-last-is-fine", "class A { void m(String a, String... b) { } }");
        cases.put("guard-on-pattern-label", "class A { void m(Object o) { switch (o) { case Integer i when i > 0 -> { } default -> { } } } }");
        cases.put("import-shapes", "import a.Dummy;\nimport a.*;\nimport static a.B.c;\nclass A { }\n");
        cases.put("void-return-and-class-literal", "class A { void m() { } Object o = void.class; }");
        cases.put("plain-local-class", "class A { void m() { class Y { } } }");
        cases.put("static-member-class", "class A { static class Y { } }");
        cases.put("receiver-param-first", "class A { void m(A this, int x) { } }");
        // A record COMPONENT pattern may still use 'var' even though a top-level one may not.
        cases.put("record-component-var-pattern", "class A { void m(Object o) { if (o instanceof P(Q(var x), var y)) { } } }");
        cases.put("class-literal-shapes", "class A { Object o = Object.class; Object p = java.util.Map.class; Object q = String[].class; }");

        return cases;
    }

    @Test
    public void rejectsWhatJavacRejects() throws Exception {
        var grammar = Files.readString(Path.of("src/test/resources/java25.peg"));
        var parser = PegParser.fromGrammar(grammar).unwrap();

        System.out.println("=== java rejection probe ===");
        var wronglyAccepted = 0;

        for (var e : mustReject().entrySet()) {
            var r = parser.parse(e.getValue());

            if (r.diagnostics().isEmpty()) {
                wronglyAccepted++ ;
                System.out.printf("  ACCEPTED (should reject) %-28s %s%n", e.getKey(), e.getValue());
            }
        }

        var wronglyRejected = 0;

        for (var e : legalNearMisses().entrySet()) {
            var r = parser.parse(e.getValue());

            if (!r.diagnostics().isEmpty()) {
                wronglyRejected++ ;
                System.out.printf("  REJECTED (should accept) %-28s %s%n",
                                  e.getKey(), r.diagnostics().get(0).message());
            }
        }

        System.out.println("=== " + wronglyAccepted + " over-permissive, "
                           + wronglyRejected + " over-tightened ===");

        assertThat(wronglyAccepted)
        .as("javac's parser rejects these; a grammar that accepts them is over-permissive")
        .isZero();

        assertThat(wronglyRejected)
        .as("these are legal Java sitting one character from a rejection case — rejecting them "
            + "means a rule was tightened past correctness")
        .isZero();
    }
}
