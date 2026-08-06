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

        // --- JLS 9.1.4: an interface body is not a class body ---
        cases.put("interface-field-no-initializer", "interface I { int X; }");
        cases.put("interface-instance-initializer", "interface I { { } }");
        cases.put("interface-static-initializer", "interface I { static { } }");
        cases.put("interface-constructor", "interface I { I(Object... args) { } }");

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
        cases.put("record-as-identifier", "class A { int record; void record() { } }");

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
