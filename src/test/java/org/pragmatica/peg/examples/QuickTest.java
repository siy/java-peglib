package org.pragmatica.peg.examples;

import org.pragmatica.peg.PegParser;
import static org.pragmatica.peg.examples.Java25GrammarExample.JAVA_GRAMMAR;
import java.nio.file.*;

public class QuickTest {
    public static void main(String[] args) throws Exception {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        for (var arg : args) {
            var content = Files.readString(Path.of(arg));
            var result = parser.parseCst(content);
            System.out.println(arg + " - " + (result.isSuccess() ? "OK" : result));
        }
    }
}
