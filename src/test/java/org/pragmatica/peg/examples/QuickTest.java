package org.pragmatica.peg.examples;

import org.pragmatica.peg.PegParser;
import static org.pragmatica.peg.examples.Java25GrammarExample.JAVA_GRAMMAR;

public class QuickTest {
    public static void main(String[] args) throws Exception {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();

        String code = """
            class Test {
                private String name;
                public Test(String name) { this.name = name; }
            }
            """;

        var result = parser.parseCst(code);
        System.out.println("Result: " + (result.isSuccess() ? "OK" : result));

        // Test assignment expression directly
        var r2 = parser.parseCst("this.name = name", "Expr");
        System.out.println("Assignment expr: " + (r2.isSuccess() ? "OK" : r2));

        // Test statement
        var r3 = parser.parseCst("this.name = name;", "Stmt");
        System.out.println("Assignment stmt: " + (r3.isSuccess() ? "OK" : r3));
    }
}
