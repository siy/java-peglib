package org.pragmatica.peg.examples;

import org.pragmatica.peg.PegParser;
import static org.pragmatica.peg.examples.Java25GrammarExample.JAVA_GRAMMAR;

public class QuickTest {
    public static void main(String[] args) throws Exception {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();

        String failingCode = """
            class Foo {
                enum Status { PENDING, ACTIVE }
                String test(Status s) {
                    return switch (s) {
                        case PENDING -> "p";
                        case ACTIVE -> "a";
                    };
                }
            }
            """;

        var result = parser.parseCst(failingCode);
        System.out.println("Result: " + (result.isSuccess() ? "OK" : result));
    }
}
