package org.pragmatica.peg.v6.generator;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.v6.PegParser;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Phase 0.6.0 — exercises the identifier-fallback set: when the grammar has an
 * {@code Identifier <- !Keyword <body>} skip-prefix rule, the generated parser
 * accepts inline-literal kinds whose text is identifier-shaped and NOT a hard
 * keyword, wherever {@code Identifier} is expected.
 *
 * <p>This recovers the 0.5.x behavior where contextual keywords like
 * {@code module}, {@code record}, {@code sealed}, {@code permits}, {@code open}
 * fall through to {@code Identifier} via PEG ordered choice when they appear
 * as method / parameter / field names.
 */
class IdentifierFallbackTest {

    /**
     * Synthetic minimal reproducer: a grammar where {@code use} is a hard
     * keyword (in the {@code Keyword} rule) but {@code module} is referenced
     * inline (so the lexer emits a dedicated INLINE_module kind). Without
     * the identifier fallback, {@code use module;} would fail to match
     * {@code use Identifier ;} because the lexer emits INLINE_module rather
     * than the Identifier kind.
     */
    @Test
    void contextualKeywordAcceptedAsIdentifier() {
        var grammar = """
            Stmt <- 'use' Identifier ';' / 'module' ';'
            Identifier <- !Keyword < [a-zA-Z_$] [a-zA-Z0-9_$]* >
            Keyword <- ('use' / 'import') ![a-zA-Z0-9_$]
            %whitespace <- [ \\t\\r\\n]*
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();
        // 'module' is identifier-shaped and not in Keyword → should match Identifier.
        var ok = parser.parse("use module;");
        assertTrue(ok.diagnostics().isEmpty(),
                   "expected clean parse of 'use module;', got diagnostics: " + ok.diagnostics());
    }

    @Test
    void hardKeywordRejectedAsIdentifier() {
        var grammar = """
            Stmt <- 'use' Identifier ';' / 'module' ';'
            Identifier <- !Keyword < [a-zA-Z_$] [a-zA-Z0-9_$]* >
            Keyword <- ('use' / 'import') ![a-zA-Z0-9_$]
            %whitespace <- [ \\t\\r\\n]*
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();
        // 'use' IS a hard keyword → must NOT match Identifier; the parser
        // should either fail or report diagnostics.
        var bad = parser.parse("use use;");
        assertFalse(bad.diagnostics().isEmpty(),
                    "expected diagnostics for 'use use;' (hard keyword in identifier position)");
    }

    @Test
    void plainIdentifierStillMatches() {
        var grammar = """
            Stmt <- 'use' Identifier ';' / 'module' ';'
            Identifier <- !Keyword < [a-zA-Z_$] [a-zA-Z0-9_$]* >
            Keyword <- ('use' / 'import') ![a-zA-Z0-9_$]
            %whitespace <- [ \\t\\r\\n]*
            """;
        var parser = PegParser.fromGrammar(grammar).unwrap();
        var ok = parser.parse("use foo;");
        assertTrue(ok.diagnostics().isEmpty(),
                   "expected clean parse of 'use foo;', got diagnostics: " + ok.diagnostics());
    }

    @Test
    void java25ContextualKeywordsParseAsIdentifiers() throws Exception {
        var grammar = Files.readString(Path.of("src/test/resources/java25.peg"));
        var parser = PegParser.fromGrammar(grammar).unwrap();
        // Each line uses a contextual keyword as a method name / parameter name —
        // these should all parse cleanly thanks to the identifier-fallback set.
        var cases = new String[]{
            "public class Foo { void m() { open(); } }",
            "public class Foo { void m() { module(); } }",
            "public class Foo { void m() { requires(); } }",
            "public class Foo { void m() { exports(); } }",
            "public class Foo { void m() { opens(); } }",
            "public class Foo { void m() { uses(); } }",
            "public class Foo { void m() { provides(); } }",
            "public class Foo { void m() { with(); } }",
            "public class Foo { void m() { to(); } }",
            "public class Foo { void m() { record(); } }",
            "public class Foo { void m() { yield(); } }",
            "public class Foo { void m() { sealed(); } }",
            "public class Foo { void m() { permits(); } }",
            "public class Foo { void m() { when(); } }",
            "public class Foo { void m() { var x = open(); } }",
            "public class Foo { int open = 1; }",
            "public class Foo { void m(int open) {} }",
        };
        for (var src : cases) {
            var r = parser.parse(src);
            assertTrue(r.diagnostics().isEmpty(),
                       "expected clean parse of '" + src + "', got diagnostics: " + r.diagnostics());
        }
    }

    @Test
    void java25HardKeywordsStillRejectedAsIdentifiers() throws Exception {
        var grammar = Files.readString(Path.of("src/test/resources/java25.peg"));
        var parser = PegParser.fromGrammar(grammar).unwrap();
        // 'class' is a hard Java keyword — must not be accepted as a method name.
        var src = "public class Foo { void class() {} }";
        var r = parser.parse(src);
        assertFalse(r.diagnostics().isEmpty(),
                    "expected diagnostics for hard keyword 'class' in identifier position");
    }

    /**
     * Targeted fixture mirroring the FactoryClassGenerator failure mode: real
     * Java code that exercises contextual keywords in identifier positions
     * (method names, qualified calls, parameter names). The grammar is the
     * unmodified java25.peg; this test certifies the fallback fix in
     * isolation from unrelated parse issues (e.g. markdown {@code ///}
     * comments) that the full FactoryClassGenerator fixture also hits.
     */
    @Test
    void realWorldClassWithContextualKeywordsParsesCleanly() throws Exception {
        var grammar = Files.readString(Path.of("src/test/resources/java25.peg"));
        var parser = PegParser.fromGrammar(grammar).unwrap();
        var src = """
            package a.b;
            import java.util.List;
            public class Foo {
                private final int open = 1;
                public void module(int record, String yield) {
                    sealed();
                    permits();
                    when();
                    var x = open;
                    file.openWriter();
                }
                public Foo to(int requires, int exports) { return this; }
            }
            """;
        var r = parser.parse(src);
        assertTrue(r.diagnostics().isEmpty(),
                   "expected clean parse of real-world contextual-keyword class; got " + r.diagnostics());
        // Diagnostic count must be exactly zero.
        assertEquals(0, r.diagnostics().size(),
                     "expected 0 diagnostics, got " + r.diagnostics().size());
    }
}
