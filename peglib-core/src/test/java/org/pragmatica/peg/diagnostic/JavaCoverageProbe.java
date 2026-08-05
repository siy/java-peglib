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
 * <p>Asserts zero gaps: these 40 constructs all parse today, so any future grammar change
 * that breaks one fails here rather than being discovered in the field. Add a case when a
 * gap is found; do not delete one to make the suite pass. Run:
 * {@code mvn -pl peglib-core test -Dtest=JavaCoverageProbe -Djbct.skip=true}
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
