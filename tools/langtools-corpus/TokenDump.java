import org.pragmatica.peg.PegParser;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dumps the token stream for one source file. The parser-level diagnostic only says which
 * token it choked on; when a lexer rule changes, the question is almost always what KIND the
 * lexer assigned, which is invisible from the parse result.
 *
 * Usage: java TokenDump &lt;grammar.peg&gt; &lt;file.java&gt; [limit]
 */
public final class TokenDump {
    public static void main(String[] args) throws Exception {
        var parser = PegParser.fromGrammar(Files.readString(Path.of(args[0]))).unwrap();
        var src = Files.readString(Path.of(args[1]));
        var limit = args.length > 2 ? Integer.parseInt(args[2]) : 40;

        var tokens = parser.lexer().lex(src);

        System.out.println("tokens=" + tokens.count());
        for (var i = 0; i < tokens.count() && i < limit; i++) {
            var text = src.substring(tokens.startAt(i), tokens.endAt(i)).replace("\n", "\\n");
            System.out.printf("  %3d  kind=%-4d %-22s '%s'%n",
                              i, tokens.kindAt(i), tokens.kindName(i), text);
        }
    }
}
