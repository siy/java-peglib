package org.pragmatica.peg.diagnostic;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import org.pragmatica.peg.PegParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/**
 * Adversarial coverage probe: valid Java constructs that the selfhost corpus is
 * unlikely to contain. Deliberately picks awkward-but-legal forms rather than
 * idiomatic ones — the point is to find gaps, not to confirm the happy path.
 *
 * <p>Asserts zero gaps: every construct listed here parses today, so any future grammar change
 * that breaks one fails here rather than being discovered in the field. Add a case when a
 * gap is found; do not delete one to make the suite pass. Run:
 * {@code mvn -pl peglib-core test -Dtest=JavaCoverageProbe -Djbct.skip=true}
 *
 * <p>This probe covers only what must be ACCEPTED. For constructs that must be REJECTED, see
 * {@code JavaRejectionProbe} — a grammar that accepts everything would pass this file trivially.
 */
public class JavaCoverageProbe {

    @Test
    public void probe() throws Exception {
        var grammar = Files.readString(Path.of("src/test/resources/java25.peg"));
        var parser = PegParser.fromGrammar(grammar).unwrap();
        var cases = new LinkedHashMap<String, String>();

        // Generics / types
        cases.put("explicit-type-args-call", "class A { void m() { this.<String>f(); } }");
        cases.put("wildcard-super", "class A { void m(java.util.List<? super Integer> l) { } }");
        cases.put("intersection-cast", "class A { void m(Object o) { var x = (Runnable & java.io.Serializable) o; } }");
        cases.put("bounded-intersection-tp", "class A<T extends Comparable<? super T> & Cloneable> { }");
        cases.put("nested-generic-array", "class A { java.util.List<String>[] a; }");
        cases.put("generic-ctor-call", "class A { void m() { var x = new java.util.HashMap<String, java.util.List<Integer>>(); } }");
        cases.put("type-annotated-array", "class A { String @Ann [] a; }");

        // Declarations
        cases.put("varargs-generic", "class A { void m(java.util.List<String>... x) { } }");
        cases.put("static-nested-enum", "class A { static enum E { X, Y; } }");
        cases.put("enum-with-body", "class A { enum E { X { void f() { } }, Y; abstract void f(); } }");
        cases.put("sealed-interface", "sealed interface S permits I1, I2 { } final class I1 implements S { } final class I2 implements S { }");
        cases.put("generic-record-compact", "record R<T>(T a, T b) { R { } }");
        cases.put("interface-private-method", "interface I { private void helper() { } static void s() { } default void d() { } }");
        cases.put("annotation-array-value", "@interface A { String[] value(); } @A({\"x\", \"y\"}) class B { }");
        cases.put("receiver-parameter", "class A { void m(A this) { } }");
        cases.put("initializer-blocks", "class A { static { } { } }");

        // Statements / expressions
        cases.put("labeled-break-continue", "class A { void m() { outer: for (;;) { continue outer; } } }");
        cases.put("try-with-multi-resource", "class A { void m() throws Exception { try (var a = open(); var b = open()) { } } AutoCloseable open() { return null; } }");
        cases.put("try-with-existing-var", "class A { void m(AutoCloseable r) throws Exception { try (r) { } } }");
        cases.put("multi-catch", "class A { void m() { try { } catch (RuntimeException | Error e) { } } }");
        cases.put("switch-yield-block", "class A { int m(int x) { return switch (x) { case 1 -> { yield 2; } default -> 0; }; } }");
        cases.put("switch-old-style-fallthrough", "class A { void m(int x) { switch (x) { case 1: case 2: break; default: break; } } }");
        cases.put("anonymous-class", "class A { Runnable r = new Runnable() { public void run() { } }; }");
        cases.put("array-init-nested", "class A { int[][] a = { { 1, 2 }, { 3 } }; }");
        cases.put("array-creation-dims", "class A { int[][] a = new int[3][4]; int[] b = new int[]{1,2}; }");
        cases.put("method-ref-forms", "class A { void m() { Runnable a = System.out::println; java.util.function.Supplier<A> b = A::new; } }");
        cases.put("lambda-typed-params", "class A { void m() { java.util.function.BiFunction<Integer,Integer,Integer> f = (Integer a, Integer b) -> a + b; } }");
        cases.put("ternary-nested", "class A { int m(int x) { return x > 0 ? x > 1 ? 2 : 1 : 0; } }");
        cases.put("cast-then-lambda", "class A { Object o = (Runnable) () -> { }; }");
        cases.put("assert-with-message", "class A { void m(int x) { assert x > 0 : \"neg\"; } }");
        cases.put("synchronized-block", "class A { void m() { synchronized (this) { } } }");
        cases.put("do-while", "class A { void m() { do { } while (true); } }");
        cases.put("instanceof-generic", "class A { void m(Object o) { if (o instanceof java.util.List<?> l) { } } }");
        cases.put("record-pattern-nested", "class A { void m(Object o) { if (o instanceof P(Q(var x), var y)) { } } }");
        cases.put("guarded-pattern", "class A { void m(Object o) { switch (o) { case String s when s.isEmpty() -> { } default -> { } } } }");

        // Gaps found by the OpenJDK langtools corpus (2026-08-06). Each of these parsed
        // incorrectly before that run; they are gated here so the corpus does not have to be
        // re-fetched to catch a regression.
        cases.put("qualified-super", "class A { class B { void m() { A.super.hashCode(); } } }");
        cases.put("array-init-lone-comma", "class A { int[] i = {,}; int[] j = new int[] {,}; }");
        cases.put("annotated-wildcard", "class A { java.util.List<@Ann ?> l; }");
        cases.put("annotated-wildcard-bound", "class A { java.util.List<@Ann ? extends Number> l; }");

        // A compilation unit may be legally empty, and a file holding only a license header is
        // just as legal — CompilationUnit is nullable. The engine used to reject both: the
        // generated parseWithRecovery emitted "empty input" whenever the token stream was empty
        // or all-trivia, without ever attempting the start rule. 71 langtools files hit this.
        cases.put("empty-compilation-unit", "");
        cases.put("comment-only-block", "/*\n * Copyright header, no code.\n */\n");
        cases.put("comment-only-line", "// just a line comment\n");
        cases.put("comment-only-mixed", "/* block */\n// line\n/// doc line\n");

        // Gaps found by differencing against javac's own parse phase over the OpenJDK
        // langtools corpus (2026-08-06). See tools/langtools-corpus/README.md.
        cases.put("stray-semicolon-toplevel", "class Foo { };");
        cases.put("stray-semicolon-between", "class A { } ; ; class B { }");
        cases.put("annotation-decl-typeparams", "@interface A<T> { }");
        cases.put("annotation-decl-extends", "@interface A extends B { }");
        cases.put("annotation-elem-params-throws", "@interface A { int x(int y) throws Exception; }");
        cases.put("annotation-elem-default-lone-comma", "@interface A { int[] v() default {,}; }");
        cases.put("enum-lone-comma", "enum E { , }");
        cases.put("enum-empty", "enum E { }");
        cases.put("explicit-ctor-typeargs-super", "class A extends B { A() { <Object>super(); } }");
        cases.put("explicit-ctor-typeargs-this", "class A { A() { <Object>this(1); } A(int x) { } }");
        cases.put("annotated-varargs", "class A { void f(int @Ann ... x) { } }");
        cases.put("for-each-final-annotated", "class A { void m(int[] arr) { for (final @Ann int a : arr) { } } }");
        cases.put("pattern-final-binding", "class A { void m(Object o) { switch (o) { case Foo(final int x) -> { } default -> { } } } }");
        cases.put("case-null-with-item", "class A { void m(String s) { switch (s) { case null, \"a\": break; default: break; } } }");
        cases.put("annotation-before-typeparams", "class A { public @Ann <T> void m() { } }");
        cases.put("qualified-receiver-param", "class Outer { class Inner { Inner(Outer Outer.this) { } } }");
        cases.put("qualified-super-ctor-typeargs", "class T<X> { class V<Z> { <C> V(T<X> t) { t.<Object>super(\"\"); } } }");
        cases.put("qualified-super-ctor-plain", "class T<X> { class V<Z> { V(T<X> t) { t.super(\"\"); } } }");
        cases.put("generic-constructor", "class T<X> { <A> T() { } }");
        cases.put("interface-method-default-value", "interface I { String value() default \"\"; }");
        cases.put("annotated-qualified-selector", "class A { void m(String... args) { java.util.@A Arrays.stream(args); } }");
        cases.put("final-receiver-parameter", "class A { void m() { class I { I(final A A.this) { } } } }");
        cases.put("var-as-package-segment", "class A { void m() { pkg.nested.var.A a = null; } }");
        cases.put("annotation-mixed-args", "@Anno(name == \"fred\", address = \"there\") class A { }");
        cases.put("twr-parenthesised-resource", "class A implements AutoCloseable { public void close() { } void m(A v) { try ((v)) { } } }");
        cases.put("twr-field-chain-resource", "class A implements AutoCloseable { public void close() { } void m(A v) { try (v.f.g) { } } }");

        // JLS 3.8: an identifier is any run of Character.isJavaIdentifierPart, not just ASCII.
        // Non-English identifiers are ordinary Java and must parse.
        // A text block may contain an ESCAPED triple quote without closing. Block comments
        // must not gain the same behaviour: a backslash before */ does not escape it.
        cases.put("text-block-escaped-quotes", "class A { String s = \"\"\"\n  a \\\"\"\"  b\n  \"\"\"; }");
        cases.put("block-comment-backslash-still-ends", "class A { void m() { /* a \\*/ int x = 1; } }");
        cases.put("non-ascii-identifier", "class A { int caf\u00e9 = 1; }");
        cases.put("non-ascii-identifier-start", "class A { int \u00e9x = 1; int normal = 2; }");
        cases.put("non-ascii-in-string-and-comment", "class A { String s = \"caf\u00e9\"; /* \u00e9 */ }");

        // A switch guard ending in a bare IDENTIFIER used to be swallowed as a lambda: with
        // Lambda reachable from Primary, 'when i == j ->' parsed 'j -> {...}' and ate the
        // switch rule's own arrow. Guards ending in a literal ('> 2') masked this for a long
        // time, so both shapes are pinned here.
        cases.put("guard-ending-in-identifier", "class A { void m(Object o, int j) { switch (o) { case Integer i when i == j -> { } default -> { } } } }");
        cases.put("guard-ending-in-literal", "class A { void m(Object o) { switch (o) { case String s when s.length() > 2 -> { } default -> { } } } }");
        cases.put("guard-bare-identifier", "class A { void m(Object o, boolean b) { switch (o) { case Integer i when b -> { } default -> { } } } }");
        cases.put("lambda-in-annotation-value", "@Anno(value = x -> x) class A { } @interface Anno { Object value(); }");
        cases.put("lambda-in-ternary", "class A { Object o = true ? (Runnable) () -> { } : null; }");
        cases.put("lambda-as-assignment-rhs", "class A { Runnable r; void m() { r = () -> { }; } }");
        cases.put("record-varargs-component", "record R(String... options) { }");
        cases.put("local-record-varargs", "class A { void m() { record Setup(String... options) { } } }");
        cases.put("constructor-annotation", "class Outer { class Inner { public @Ann Inner() { } } }");
        cases.put("annotation-bare-multi-value", "@Target(ElementType.METHOD, ElementType.TYPE) @interface A { }");

        // An identifier beginning with '_' immediately after '.' used to lose a maximal-munch
        // race: NumLit's leading-dot alternative was < '.' [0-9_]+ ... >, and because '_' is in
        // [0-9_] it matched '._' as a single 2-char token, beating the 1-char '.' and desyncing
        // the token stream for the rest of the file. Requiring a real digit after the dot fixed
        // it. These look like lexer trivia but each one cascaded into a whole-file failure.
        cases.put("underscore-member-after-dot", "class A { int _field; void m(A t) { t._field = 3; } }");
        cases.put("underscore-package-segment", "package primitive._class;\nclass C { }\n");
        cases.put("underscore-digit-member", "class A { void _1() { } void m(A t) { t._1(); } }");
        cases.put("underscore-prefixed-names", "class A { int _x = 1; int __y = 2; int _9 = 3; }");

        // Literals / lexical
        cases.put("text-block", "class A { String s = \"\"\"\n  hi\n  \"\"\"; }");
        cases.put("underscore-literals", "class A { long x = 1_000_000L; int y = 0b1010_1010; }");
        cases.put("unicode-escape-in-string", "class A { String s = \"\\u0041\\n\\t\"; }");
        cases.put("char-escapes", "class A { char c = '\\\\'; char d = '\\''; }");
        cases.put("unnamed-variable", "class A { void m() { for (var _ : new int[]{1}) { } } }");

        System.out.println("=== adversarial java coverage probe ===");
        var failures = 0;

        for (var e : cases.entrySet()) {
            var r = parser.parse(e.getValue());
            var n = r.diagnostics().size();

            if (n != 0) {
                failures++ ;
                System.out.printf("  FAIL %-30s (%d diag) %s%n",
                                  e.getKey(), n, r.diagnostics().get(0).message());
            }
        }

        System.out.println("=== " + (cases.size() - failures) + "/" + cases.size()
                           + " accepted; " + failures + " gaps ===");

        assertThat(failures)
        .as("every construct listed here parsed cleanly when it was added; a non-zero count is a regression")
        .isZero();
    }
}
