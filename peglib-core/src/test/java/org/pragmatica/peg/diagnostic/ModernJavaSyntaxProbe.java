package org.pragmatica.peg.diagnostic;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import org.pragmatica.peg.PegParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/**
 * Post-Java-25 syntax gate: JEP 401 value classes, JEP 512 compact source files, and
 * JEP 530/532 primitive patterns, plus the 'value'-as-ordinary-identifier cases that the
 * contextual keyword must not break. All 19 pass today and are asserted, so a regression
 * fails here instead of silently shipping.
 *
 * <p>Run: {@code mvn -pl peglib-core test -Dtest=ModernJavaSyntaxProbe -Djbct.skip=true}
 */
public class ModernJavaSyntaxProbe {

    @Test
    public void probe() throws Exception {
        var grammar = Files.readString(Path.of("src/test/resources/java25.peg"));
        var parser = PegParser.fromGrammar(grammar).unwrap();

        var cases = new LinkedHashMap<String, String>();

        // JEP 530/532 — primitive types in patterns, instanceof, switch (preview)
        cases.put("instanceof-primitive", "class A { void m(Object o) { if (o instanceof int i) { } } }");
        cases.put("switch-case-primitive", "class A { void m(Object o) { switch (o) { case int i -> { } } } }");
        cases.put("record-pattern-primitive", "class A { void m(Object o) { if (o instanceof P(int x, int y)) { } } }");

        // JEP 401 — value classes (Release 28, preview)
        cases.put("value-class", "value class Point { }");
        cases.put("value-record", "value record Point(int x, int y) { }");
        cases.put("abstract-value-class", "abstract value class Number { }");
        cases.put("sealed-abstract-value", "sealed abstract value class UserID permits EmailID { }");
        cases.put("value-class-extends", "value class EmailID extends UserID { }");
        cases.put("public-value-class", "public value class Money { private long cents; }");

        // 'value' must remain usable as an ordinary identifier
        cases.put("value-as-local-var", "class A { void m() { var value = 3; } }");
        cases.put("value-as-field", "class A { int value; }");
        cases.put("value-as-method", "class A { int value() { return 1; } }");
        cases.put("value-as-assignment", "class A { void m() { value = 3; } }");
        cases.put("value-as-call", "class A { void m() { value.foo(); } }");
        cases.put("value-as-param", "class A { void m(int value) { } }");

        // JEP 512 — compact source files / instance main (final in Java 25)
        cases.put("compact-source-file", "void main() { System.out.println(\"hi\"); }");

        // Other known gaps
        cases.put("qualified-new", "class A { void m(Outer o) { o.new Inner(); } }");
        cases.put("annotated-type-param", "class A<@NonNull T> { }");
        cases.put("hex-float-literal", "class A { double d = 0x1.8p3; }");

        System.out.println("=== modern-java syntax probe ===");
        var failures = 0;

        for (var e : cases.entrySet()) {
            var result = parser.parse(e.getValue());
            var n = result.diagnostics().size();
            var ok = n == 0;

            if (!ok) {
                failures++ ;
            }

            System.out.printf("  %-26s %-4s (%d diagnostics)%s%n",
                              e.getKey(),
                              ok ? "OK" : "FAIL",
                              n,
                              ok ? "" : "  first: " + result.diagnostics().get(0).message());
        }

        System.out.println("=== " + (cases.size() - failures) + "/" + cases.size() + " accepted ===");

        assertThat(failures)
        .as("all 19 forms parsed cleanly when added; non-zero is a regression")
        .isZero();
    }
}
