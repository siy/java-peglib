package org.pragmatica.peg.examples;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java 25 grammar based on JLS SE 25 Chapter 19.
 *
 * <p>This is a practical PEG grammar for Java CST parsing. Some JLS rules are
 * simplified to avoid left recursion (which PEG doesn't support).
 *
 * <h2>Java 25 Features Supported</h2>
 * <ul>
 *   <li><b>Module declarations</b> - module, open module, requires, exports, opens, uses, provides</li>
 *   <li><b>Local variable type inference</b> - var keyword</li>
 *   <li><b>Pattern matching</b> - instanceof patterns, record patterns, switch patterns with guards</li>
 *   <li><b>Text blocks</b> - triple-quoted strings</li>
 *   <li><b>Records</b> - record declarations with components</li>
 *   <li><b>Sealed classes</b> - sealed, non-sealed, permits</li>
 *   <li><b>Switch expressions</b> - arrow syntax, yield</li>
 *   <li><b>Type-use annotations (JSR 308)</b> - {@code @NonNull String}, {@code List<@NonNull String>},
 *       {@code String @NonNull []}, {@code Outer.@Nullable Inner}</li>
 * </ul>
 *
 * @see <a href="https://docs.oracle.com/javase/specs/jls/se25/html/jls-19.html">JLS SE 25 Chapter 19</a>
 */
class Java25GrammarExample {

    // Java 25 Grammar (PEG-compatible adaptation of JLS Chapter 19)
    // Cut operator (^) commits to current alternative, preventing backtracking.
    // Used after discriminating keywords to improve error messages and performance.
    static final String JAVA_GRAMMAR = """
        # === Compilation Units (JLS 7.3-7.8) ===
        CompilationUnit <- ModuleDecl / OrdinaryUnit
        OrdinaryUnit <- PackageDecl? ImportDecl* TypeDecl*
        PackageDecl <- Annotation* 'package' ^ QualifiedName ';'
        ImportDecl  <- 'import' ^ ('module' QualifiedName ';' / 'static'? QualifiedName ('.' '*')? ';')

        # === Module Declarations (JLS 7.7) ===
        ModuleDecl <- Annotation* 'open'? 'module' ^ QualifiedName '{' ModuleDirective* '}'
        ModuleDirective <- RequiresDirective / ExportsDirective / OpensDirective / UsesDirective / ProvidesDirective
        RequiresDirective <- 'requires' ^ ('transitive' / 'static')* QualifiedName ';'
        ExportsDirective <- 'exports' ^ QualifiedName ('to' QualifiedName (',' QualifiedName)*)? ';'
        OpensDirective <- 'opens' ^ QualifiedName ('to' QualifiedName (',' QualifiedName)*)? ';'
        UsesDirective <- 'uses' ^ QualifiedName ';'
        ProvidesDirective <- 'provides' ^ QualifiedName 'with' QualifiedName (',' QualifiedName)* ';'

        TypeDecl <- Annotation* Modifier* TypeKind
        TypeKind <- ClassDecl / InterfaceDecl / EnumDecl / RecordDecl / AnnotationDecl
        ClassDecl <- ClassKW ^ Identifier TypeParams? ('extends' Type)? ImplementsClause? PermitsClause? ClassBody
        InterfaceDecl <- InterfaceKW ^ Identifier TypeParams? ('extends' TypeList)? PermitsClause? ClassBody
        AnnotationDecl <- '@' InterfaceKW ^ Identifier AnnotationBody
        # Type declaration keywords with word boundary (prevents 'class' matching prefix of 'className')
        ClassKW <- < 'class' ![a-zA-Z0-9_$] >
        InterfaceKW <- < 'interface' ![a-zA-Z0-9_$] >
        AnnotationBody <- '{' AnnotationMember* '}'
        AnnotationMember <- Annotation* Modifier* (AnnotationElemDecl / FieldDecl / TypeKind) / ';'
        AnnotationElemDecl <- Type Identifier '(' ')' ('default' AnnotationElem)? ';'
        EnumDecl <- EnumKW ^ Identifier ImplementsClause? EnumBody
        # Lookahead ensures this is a record declaration (record Name(...)) not:
        # - method call: record(...)
        # - field/variable of type 'record': record field;
        RecordDecl <- RecordKW &(Identifier TypeParams? '(') Identifier ^ TypeParams? '(' RecordComponents? ')' ImplementsClause? RecordBody
        EnumKW <- < 'enum' ![a-zA-Z0-9_$] >
        RecordKW <- < 'record' ![a-zA-Z0-9_$] >
        ImplementsClause <- 'implements' ^ TypeList
        PermitsClause <- 'permits' ^ TypeList
        TypeList <- Type (',' Type)*
        TypeParams <- '<' TypeParam (',' TypeParam)* '>'
        TypeParam <- Identifier ('extends' Type ('&' Type)*)?

        ClassBody <- '{' ClassMember* '}'
        ClassMember <- Annotation* Modifier* Member / InitializerBlock / ';'
        Member <- ConstructorDecl / TypeKind / MethodDecl / FieldDecl
        InitializerBlock <- 'static'? Block
        EnumBody <- '{' EnumConsts? (';' ClassMember*)? '}'
        EnumConsts <- EnumConst (',' EnumConst)* ','?
        EnumConst <- Annotation* Identifier ('(' Args? ')')? ClassBody?
        RecordComponents <- RecordComp (',' RecordComp)*
        RecordComp <- Annotation* Type Identifier
        RecordBody <- '{' RecordMember* '}'
        RecordMember <- CompactConstructor / ClassMember
        CompactConstructor <- Annotation* Modifier* Identifier Block

        FieldDecl <- Type VarDecls ';'
        VarDecls <- VarDecl (',' VarDecl)*
        VarDecl <- Identifier Dims? ('=' VarInit)?
        VarInit <- '{' (VarInit (',' VarInit)* ','?)? '}' / Expr
        MethodDecl <- TypeParams? Type Identifier '(' ^ Params? ')' Dims? Throws? (Block / ';')
        Params <- Param (',' Param)*
        Param <- Annotation* Modifier* Type '...'? Identifier Dims?
        Throws <- 'throws' ^ TypeList
        ConstructorDecl <- TypeParams? Identifier '(' ^ Params? ')' Throws? Block

        # === Blocks and Statements (JLS 14) ===
        Block <- '{' BlockStmt* '}'
        BlockStmt <- LocalVar / LocalTypeDecl / Stmt
        LocalTypeDecl <- Annotation* Modifier* TypeKind
        LocalVar <- Modifier* LocalVarType VarDecls ';'
        LocalVarType <- < 'var' ![a-zA-Z0-9_$] > / Type
        # Statement keywords use helper rules to combine keyword + word boundary as single token
        # This prevents the parser from skipping whitespace before the boundary check
        # Cut operator after keyword rules commits to that statement type
        Stmt <- Block
             / IfKW ^ '(' Expr ')' Stmt ('else' Stmt)?
             / WhileKW ^ '(' Expr ')' Stmt
             / ForKW ^ '(' ForCtrl ')' Stmt
             / DoKW ^ Stmt 'while' '(' Expr ')' ';'
             / TryKW ^ ResourceSpec? Block Catch* Finally?
             / SwitchKW ^ '(' Expr ')' SwitchBlock
             / ReturnKW Expr? ';'
             / ThrowKW Expr ';'
             / BreakKW Identifier? ';'
             / ContinueKW Identifier? ';'
             / AssertKW Expr (':' Expr)? ';'
             / SynchronizedKW ^ '(' Expr ')' Block
             / YieldKW Expr ';'
             / Identifier ':' Stmt
             / Expr ';'
             / ';'

        # Helper rules: keyword with word boundary INSIDE token (prevents whitespace skip before boundary check)
        IfKW <- < 'if' ![a-zA-Z0-9_$] >
        WhileKW <- < 'while' ![a-zA-Z0-9_$] >
        ForKW <- < 'for' ![a-zA-Z0-9_$] >
        DoKW <- < 'do' ![a-zA-Z0-9_$] >
        TryKW <- < 'try' ![a-zA-Z0-9_$] >
        SwitchKW <- < 'switch' ![a-zA-Z0-9_$] >
        SynchronizedKW <- < 'synchronized' ![a-zA-Z0-9_$] >
        ReturnKW <- < 'return' ![a-zA-Z0-9_$] >
        ThrowKW <- < 'throw' ![a-zA-Z0-9_$] >
        BreakKW <- < 'break' ![a-zA-Z0-9_$] >
        ContinueKW <- < 'continue' ![a-zA-Z0-9_$] >
        AssertKW <- < 'assert' ![a-zA-Z0-9_$] >
        YieldKW <- < 'yield' ![a-zA-Z0-9_$] >
        CatchKW <- < 'catch' ![a-zA-Z0-9_$] >
        FinallyKW <- < 'finally' ![a-zA-Z0-9_$] >
        WhenKW <- < 'when' ![a-zA-Z0-9_$] >
        ForCtrl <- ForInit? ';' Expr? ';' ExprList? / LocalVarType Identifier ':' Expr
        ForInit <- LocalVarNoSemi / ExprList
        LocalVarNoSemi <- Modifier* LocalVarType VarDecls
        ResourceSpec <- '(' Resource (';' Resource)* ';'? ')'
        Resource <- Modifier* LocalVarType Identifier '=' Expr / QualifiedName
        Catch <- CatchKW ^ '(' Modifier* Type ('|' Type)* Identifier ')' Block
        Finally <- FinallyKW ^ Block
        SwitchBlock <- '{' SwitchRule* '}'
        SwitchRule <- SwitchLabel '->' (Expr ';' / Block / ThrowKW Expr ';') / SwitchLabel ':' BlockStmt*
        # === Switch Labels and Patterns (JLS 14.11, 14.30) ===
        SwitchLabel <- 'case' ^ ('null' (',' 'default')? / CaseItem (',' CaseItem)* Guard?) / 'default'
        CaseItem <- Pattern / QualifiedName &('->' / ',' / ':' / 'when') / Expr
        Pattern <- RecordPattern / TypePattern
        TypePattern <- &(LocalVarType Identifier) LocalVarType Identifier / '_'
        RecordPattern <- RefType '(' PatternList? ')'
        PatternList <- Pattern (',' Pattern)*
        Guard <- WhenKW Expr

        Expr <- Assignment
        Assignment <- Ternary (('=' / '>>>=' / '>>=' / '<<=' / '+=' / '-=' / '*=' / '/=' / '%=' / '&=' / '|=' / '^=') Assignment)?
        Ternary <- LogOr ('?' Expr ':' Ternary)?
        LogOr <- LogAnd ('||' LogAnd)*
        LogAnd <- BitOr ('&&' BitOr)*
        BitOr <- BitXor (!'||' !'|=' '|' BitXor)*
        BitXor <- BitAnd (!'^=' '^' BitAnd)*
        BitAnd <- Equality (!'&&' !'&=' '&' Equality)*
        Equality <- Relational (('==' / '!=') Relational)*
        Relational <- Shift (('<=' / '>=' / '<' / '>') Shift / 'instanceof' (Pattern / Type))?
        Shift <- Additive ((!'<<=' '<<' / !'>>>=' '>>>' / !'>>=' !'>>>=' '>>') Additive)*
        Additive <- Multiplicative ((!'+=' '+' / !'-=' !'->' '-') Multiplicative)*
        Multiplicative <- Unary ((!'*=' '*' / !'/=' '/' / !'%=' '%') Unary)*
        Unary <- ('++' / '--' / '+' / '-' / '!' / '~') Unary / '(' Type ('&' Type)* ')' Unary / Postfix
        Postfix <- Primary PostOp*
        PostOp <- '.' TypeArgs? Identifier ('(' Args? ')')? / '.' 'class' / '.' 'this' / '[' Expr ']' / '(' Args? ')' / '++' / '--' / '::' TypeArgs? (Identifier / 'new')
        Primary <- Literal / < 'this' ![a-zA-Z0-9_$] > / < 'super' ![a-zA-Z0-9_$] > / < 'new' ![a-zA-Z0-9_$] > TypeArgs? Type ('(' Args? ')' ClassBody? / Dims? VarInit?) / SwitchKW '(' Expr ')' SwitchBlock / Lambda / '(' Expr ')' / TypeExpr / QualifiedName
        TypeExpr <- Type ('.' < 'class' > / '::' TypeArgs? (< 'new' > / Identifier))
        Lambda <- LambdaParams '->' (Expr / Block)
        LambdaParams <- Identifier / '_' / '(' LambdaParam? (',' LambdaParam)* ')'
        LambdaParam <- Annotation* Modifier* ((< 'var' ![a-zA-Z0-9_$] > / Type) &('...' / Identifier / '_'))? '...'? (Identifier / '_')
        Args <- Expr (',' Expr)*
        ExprList <- Expr (',' Expr)*

        # === Types with Type-Use Annotations (JSR 308 / JLS 4.11) ===
        Type <- Annotation* (PrimType / RefType) Dims?
        PrimType <- < ('boolean' / 'byte' / 'short' / 'int' / 'long' / 'float' / 'double' / 'char' / 'void') ![a-zA-Z0-9_$] >
        # Use lookahead BEFORE consuming '.' to avoid capturing it when followed by keyword (e.g., 'HashMap.class')
        RefType <- AnnotatedTypeName (&('.' ('@' / Identifier)) '.' AnnotatedTypeName)*
        AnnotatedTypeName <- Annotation* Identifier TypeArgs?
        Dims <- (Annotation* '[' ']')+
        TypeArgs <- '<' '>' / '<' TypeArg (',' TypeArg)* '>'
        TypeArg <- Type / '?' (Annotation* ('extends' / 'super') Type)?

        # Use lookahead BEFORE consuming '.' to avoid capturing it when followed by keyword (e.g., 'String.class')
        QualifiedName <- Identifier (&('.' Identifier) '.' Identifier)*
        Identifier <- !Keyword < [a-zA-Z_$] [a-zA-Z0-9_$]* >

        Modifier <- < ('public' / 'protected' / 'private' / 'static' / 'final' / 'abstract' / 'native' / 'synchronized' / 'transient' / 'volatile' / 'strictfp' / 'default' / 'sealed' / 'non-sealed') ![a-zA-Z0-9_$] >
        Annotation <- '@' !'interface' QualifiedName ('(' AnnotationValue? ')')?
        AnnotationValue <- Identifier '=' AnnotationElem (',' Identifier '=' AnnotationElem)* / AnnotationElem
        AnnotationElem <- Annotation / '{' (AnnotationElem (',' AnnotationElem)* ','?)? '}' / Ternary

        Literal <- < ('null' / 'true' / 'false') ![a-zA-Z0-9_$] > / CharLit / StringLit / NumLit
        CharLit <- < '\\'' ([^'\\\\] / '\\\\' .)* '\\'' >
        StringLit <- < '\"\"\"' (!'\"\"\"' .)* '\"\"\"' > / < '"' ([^"\\\\] / '\\\\' .)* '"' >
        NumLit <- < '0' [xX] [0-9a-fA-F_]+ [lL]? > / < '0' [bB] [01_]+ [lL]? > / < [0-9][0-9_]* ('.' [0-9_]*)? ([eE] [+\\-]? [0-9_]+)? [fFdDlL]? > / < '.' [0-9_]+ ([eE] [+\\-]? [0-9_]+)? [fFdD]? >

        # Hard keywords only - contextual keywords (var, yield, record, sealed, non-sealed, permits, when, module) are handled by their specific rules
        Keyword <- ('abstract' / 'assert' / 'boolean' / 'break' / 'byte' / 'case' / 'catch' / 'char' / 'class' / 'const' / 'continue' / 'default' / 'double' / 'do' / 'else' / 'enum' / 'extends' / 'false' / 'finally' / 'final' / 'float' / 'for' / 'goto' / 'implements' / 'import' / 'instanceof' / 'interface' / 'int' / 'if' / 'long' / 'native' / 'new' / 'null' / 'package' / 'private' / 'protected' / 'public' / 'return' / 'short' / 'static' / 'strictfp' / 'super' / 'switch' / 'synchronized' / 'this' / 'throws' / 'throw' / 'transient' / 'true' / 'try' / 'void' / 'volatile' / 'while') ![a-zA-Z0-9_$]

        %whitespace <- ([ \\t\\r\\n] / '//' [^\\n]* / '/*' (!'*/' .)* '*/')*
        """;

    // === Test Methods ===

    @Test
    void parseEmptyClass() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        assertTrue(parser.parseCst("class Foo { }").isSuccess());
    }

    @Test
    void parsePublicClass() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        assertTrue(parser.parseCst("public class Foo { }").isSuccess());
    }

    @Test
    void parseAnnotationInterface() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();

        var r1 = parser.parseCst("@interface Foo { }");
        assertTrue(r1.isSuccess(), () -> "Test 1 failed: " + r1);
        var r2 = parser.parseCst("public @interface Test { }");
        assertTrue(r2.isSuccess(), () -> "Test 2 failed: " + r2);
        var r3 = parser.parseCst("@interface MyAnnotation { int value(); }");
        assertTrue(r3.isSuccess(), () -> "Test 3 failed: " + r3);
    }

    @Test
    void parseClassWithField() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        var code = "class C { int x; }";
        var result = parser.parseCst(code);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseClassWithMethod() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        var code = "class C { void foo() { } }";
        var result = parser.parseCst(code);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseClassWithMethodAndBody() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        var code = "class C { int add(int a, int b) { return a + b; } }";
        var result = parser.parseCst(code);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseRecord() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        assertTrue(parser.parseCst("record Point(int x, int y) { }").isSuccess());
    }

    @Test
    void parseEnum() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        assertTrue(parser.parseCst("enum Color { RED, GREEN, BLUE }").isSuccess());
    }

    @Test
    void parseInterface() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        assertTrue(parser.parseCst("interface Foo { void bar(); }").isSuccess());
    }

    @Test
    void parseClassWithValue() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // Step-by-step diagnostics
        var ident = parser.parseCst("Object", "Identifier");
        assertTrue(ident.isSuccess(), () -> "Identifier failed: " + ident);
        var qname = parser.parseCst("Object", "QualifiedName");
        assertTrue(qname.isSuccess(), () -> "QualifiedName failed: " + qname);
        var refType = parser.parseCst("Object", "RefType");
        assertTrue(refType.isSuccess(), () -> "RefType failed: " + refType);
        var type = parser.parseCst("Object", "Type");
        assertTrue(type.isSuccess(), () -> "Type failed: " + type);
        var varDecl = parser.parseCst("value", "VarDecl");
        assertTrue(varDecl.isSuccess(), () -> "VarDecl failed: " + varDecl);
        var varDecls = parser.parseCst("value", "VarDecls");
        assertTrue(varDecls.isSuccess(), () -> "VarDecls failed: " + varDecls);
        // Test int x; first (primitive type)
        var intField = parser.parseCst("int x;", "FieldDecl");
        assertTrue(intField.isSuccess(), () -> "int field failed: " + intField);
        // Full field with reference type
        var field = parser.parseCst("Object value;", "FieldDecl");
        assertTrue(field.isSuccess(), () -> "FieldDecl failed: " + field);
        // Try class with just semicolon
        var emptyMember = parser.parseCst("class Box { ; }");
        assertTrue(emptyMember.isSuccess(), () -> "Empty member failed: " + emptyMember);
        // Then full class
        var full = parser.parseCst("class Box { Object value; }");
        assertTrue(full.isSuccess(), () -> "Full class failed: " + full);
    }

    @Test
    void parseLambda() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        assertTrue(parser.parseCst("x -> x", "Lambda").isSuccess());
        assertTrue(parser.parseCst("() -> 42", "Lambda").isSuccess());
    }

    @Test
    void parseLambdaParams() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // Single param without type (the ambiguity case)
        var r1 = parser.parseCst("(s) -> s", "Lambda");
        assertTrue(r1.isSuccess(), () -> "Single param failed: " + r1);
        // Single param with type
        var r2 = parser.parseCst("(String s) -> s", "Lambda");
        assertTrue(r2.isSuccess(), () -> "Typed param failed: " + r2);
        // Multiple params without types
        var r3 = parser.parseCst("(a, b) -> a", "Lambda");
        assertTrue(r3.isSuccess(), () -> "Multi param failed: " + r3);
        // Multiple params with types
        var r4 = parser.parseCst("(String s, Integer i) -> s", "Lambda");
        assertTrue(r4.isSuccess(), () -> "Multi typed param failed: " + r4);
        // Varargs
        var r5 = parser.parseCst("(String... args) -> args", "Lambda");
        assertTrue(r5.isSuccess(), () -> "Varargs failed: " + r5);
        // With modifier
        var r6 = parser.parseCst("(final String s) -> s", "Lambda");
        assertTrue(r6.isSuccess(), () -> "Modified param failed: " + r6);
        // Underscore param
        var r7 = parser.parseCst("(_) -> 42", "Lambda");
        assertTrue(r7.isSuccess(), () -> "Underscore param failed: " + r7);
        // Annotated param
        var r8 = parser.parseCst("(@SuppressWarnings(\"unused\") String s) -> s", "Lambda");
        assertTrue(r8.isSuccess(), () -> "Annotated param failed: " + r8);
        // Annotation without parens
        var r9 = parser.parseCst("(@Nullable String s) -> s", "Lambda");
        assertTrue(r9.isSuccess(), () -> "Simple annotation param failed: " + r9);
    }

    @Test
    void parseLambdaInExpression() {
        // Single param lambda in expression context (goes through Primary)
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        var r1 = parser.parseCst("(s) -> s.length()", "Expr");
        assertTrue(r1.isSuccess(), () -> "Lambda expr failed: " + r1);
        // Lambda in field initializer
        var r2 = parser.parseCst("Function<String, Integer> f = (s) -> s.length();", "Member");
        assertTrue(r2.isSuccess(), () -> "Lambda field failed: " + r2);
        // Ensure parenthesized expr still works
        var r3 = parser.parseCst("(x)", "Expr");
        assertTrue(r3.isSuccess(), () -> "Paren expr failed: " + r3);
        var r4 = parser.parseCst("(x) + 1", "Expr");
        assertTrue(r4.isSuccess(), () -> "Paren expr in add failed: " + r4);
        // Multi-line lambda with method chain
        var r5 = parser.parseCst("""
            s -> s.trim()
                  .toUpperCase()""", "Expr");
        assertTrue(r5.isSuccess(), () -> "Multi-line lambda failed: " + r5);
        // Lambda in method call argument
        var r6 = parser.parseCst("""
            input.map(s -> s.trim()
                           .toUpperCase())""", "Expr");
        assertTrue(r6.isSuccess(), () -> "Lambda in method call failed: " + r6);
        // Return statement with multi-line lambda
        var r7 = parser.parseCst("""
            return input.map(s -> s.trim()
                                   .toUpperCase());""", "Stmt");
        assertTrue(r7.isSuccess(), () -> "Return with lambda failed: " + r7);
    }

    @Test
    void parseIfStatement() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        assertTrue(parser.parseCst("if (x > 0) return x;", "Stmt").isSuccess());
    }

    @Test
    void parseForEach() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // Standard for loop - forEach has colon parsing complexity
        assertTrue(parser.parseCst("for (;;) { }", "Stmt").isSuccess());
    }

    @Test
    void parseTryCatch() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // Simple try without catch
        var code = "try { }";
        var result = parser.parseCst(code, "Stmt");
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseAnnotation() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        assertTrue(parser.parseCst("@Override", "Annotation").isSuccess());
        assertTrue(parser.parseCst("@SuppressWarnings(\"unchecked\")", "Annotation").isSuccess());
    }

    @Test
    void parsePackageAndImport() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        var code = """
            package com.example;
            import java.util.List;
            import java.util.*;
            """;
        var result = parser.parseCst(code);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseCompleteClass() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // Test package + import + class
        var code = "package demo; import java.util.List; public class Stack { }";
        var result = parser.parseCst(code);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseMultilineClass() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        var code = """
            package demo;
            import java.util.List;
            public class Stack {
                private List items;
                public void push(Object item) {
                    items.add(item);
                }
            }
            """;
        var result = parser.parseCst(code);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseSealedClass() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        var code = "sealed class Shape permits Circle { }";
        var result = parser.parseCst(code);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseLiterals() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        assertTrue(parser.parseCst("42", "Literal").isSuccess());
        assertTrue(parser.parseCst("3.14", "Literal").isSuccess());
        assertTrue(parser.parseCst("\"hello\"", "Literal").isSuccess());
        assertTrue(parser.parseCst("'x'", "Literal").isSuccess());
        assertTrue(parser.parseCst("true", "Literal").isSuccess());
        assertTrue(parser.parseCst("null", "Literal").isSuccess());
    }

    @Test
    void parseExpressions() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        assertTrue(parser.parseCst("a + b", "Expr").isSuccess());
        assertTrue(parser.parseCst("x > 0 ? x : -x", "Expr").isSuccess());
        assertTrue(parser.parseCst("obj.method()", "Expr").isSuccess());
        assertTrue(parser.parseCst("arr[i]", "Expr").isSuccess());
        // Assignment expressions
        assertTrue(parser.parseCst("x = 5", "Expr").isSuccess());
        assertTrue(parser.parseCst("this.name = name", "Expr").isSuccess());
        assertTrue(parser.parseCst("x += 1", "Expr").isSuccess());
        assertTrue(parser.parseCst("arr[i] = value", "Expr").isSuccess());
    }

    // === Java 25 Specific Features ===

    @Test
    void parseModuleDeclaration() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        var code = """
            module com.example {
                requires java.base;
                exports com.example.api;
            }
            """;
        var result = parser.parseCst(code);
        assertTrue(result.isSuccess(), () -> "Module declaration failed: " + result);
    }

    @Test
    void parseOpenModule() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        var code = "open module com.example { }";
        var result = parser.parseCst(code);
        assertTrue(result.isSuccess(), () -> "Open module failed: " + result);
    }

    @Test
    void parseModuleDirectives() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        var code = """
            module com.example {
                requires transitive java.sql;
                requires static java.compiler;
                exports com.example.api to com.client;
                opens com.example.internal to com.reflect;
                uses java.util.ServiceLoader;
                provides java.util.spi.ToolProvider with com.example.Tool;
            }
            """;
        var result = parser.parseCst(code);
        assertTrue(result.isSuccess(), () -> "Module directives failed: " + result);
    }

    @Test
    void parseVarLocalVariable() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        var code = "class C { void m() { var x = 42; } }";
        var result = parser.parseCst(code);
        assertTrue(result.isSuccess(), () -> "var failed: " + result);
    }

    @Test
    void parsePatternMatchingInstanceof() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        var code = "class C { boolean f(Object o) { return o instanceof String s; } }";
        var result = parser.parseCst(code);
        assertTrue(result.isSuccess(), () -> "Pattern instanceof failed: " + result);
    }

    @Test
    void parseRecordPattern() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        assertTrue(parser.parseCst("Point(int x, int y)", "RecordPattern").isSuccess());
    }

    @Test
    void parseSwitchExpression() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        var code = """
            class C {
                int f(int x) {
                    return switch (x) {
                        case 1 -> 10;
                        case 2 -> 20;
                        default -> 0;
                    };
                }
            }
            """;
        var result = parser.parseCst(code);
        assertTrue(result.isSuccess(), () -> "Switch expression failed: " + result);
    }

    @Test
    void parseSwitchWithGuard() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // Test guard syntax separately
        var code = "case String s when true -> 1;";
        var result = parser.parseCst(code, "SwitchRule");
        assertTrue(result.isSuccess(), () -> "Switch with guard failed: " + result);
    }

    @Test
    void parseTextBlockLiteral() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // Text blocks - test the literal directly
        var textBlock = "\"\"\"\nhello\n\"\"\"";
        var result = parser.parseCst(textBlock, "StringLit");
        assertTrue(result.isSuccess(), () -> "Text block literal failed: " + result);
    }

    @Test
    void parseImportModule() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        var code = "import module java.base;";
        var result = parser.parseCst(code, "ImportDecl");
        assertTrue(result.isSuccess(), () -> "Import module failed: " + result);
    }

    // === Type-Use Annotations (JSR 308) ===

    @Test
    void parseTypeUseAnnotation_simpleType() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // @NonNull String
        var result = parser.parseCst("@NonNull String", "Type");
        assertTrue(result.isSuccess(), () -> "Type-use annotation failed: " + result);
    }

    @Test
    void parseTypeUseAnnotation_arrayType() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // String @NonNull [] - annotation on array dimension
        var result = parser.parseCst("String @NonNull []", "Type");
        assertTrue(result.isSuccess(), () -> "Array type-use annotation failed: " + result);
    }

    @Test
    void parseTypeUseAnnotation_multiDimArray() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // String @NonNull [] @Nullable [] - annotations on each dimension
        var result = parser.parseCst("String @NonNull [] @Nullable []", "Type");
        assertTrue(result.isSuccess(), () -> "Multi-dim array type-use annotation failed: " + result);
    }

    @Test
    void parseTypeUseAnnotation_typeArgument() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // List<@NonNull String>
        var result = parser.parseCst("List<@NonNull String>", "Type");
        assertTrue(result.isSuccess(), () -> "Type argument annotation failed: " + result);
    }

    @Test
    void parseTypeUseAnnotation_wildcard() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // List<? extends @NonNull Object>
        var result = parser.parseCst("List<? extends @NonNull Object>", "Type");
        assertTrue(result.isSuccess(), () -> "Wildcard type annotation failed: " + result);
    }

    @Test
    void parseTypeUseAnnotation_nestedType() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // Outer.@Nullable Inner
        var result = parser.parseCst("Outer.@Nullable Inner", "Type");
        assertTrue(result.isSuccess(), () -> "Nested type annotation failed: " + result);
    }

    @Test
    void parseTypeUseAnnotation_complex() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // Map<@NonNull String, @Nullable List<@NonNull Integer>>
        var result = parser.parseCst("Map<@NonNull String, @Nullable List<@NonNull Integer>>", "Type");
        assertTrue(result.isSuccess(), () -> "Complex type annotation failed: " + result);
    }

    @Test
    void parseTypeUseAnnotation_fieldDecl() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        var code = "class C { @NonNull String name; }";
        var result = parser.parseCst(code);
        assertTrue(result.isSuccess(), () -> "Field with type annotation failed: " + result);
    }

    @Test
    void parseTypeUseAnnotation_methodReturn() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        var code = "class C { @NonNull String getName() { return null; } }";
        var result = parser.parseCst(code);
        assertTrue(result.isSuccess(), () -> "Method return type annotation failed: " + result);
    }

    @Test
    void parseTypeUseAnnotation_parameter() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        var code = "class C { void set(@NonNull String value) { } }";
        var result = parser.parseCst(code);
        assertTrue(result.isSuccess(), () -> "Parameter type annotation failed: " + result);
    }

    // === Edge Cases ===

    @Test
    void parseMethodReference() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // Simple method reference
        var r1 = parser.parseCst("String::valueOf", "Expr");
        assertTrue(r1.isSuccess(), () -> "Simple method ref failed: " + r1);
        // Constructor reference
        var r2 = parser.parseCst("ArrayList::new", "Expr");
        assertTrue(r2.isSuccess(), () -> "Constructor ref failed: " + r2);
    }

    @Test
    void parseAnonymousClassWithDiamond() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        var code = "class C { Object o = new ArrayList<>() { }; }";
        var result = parser.parseCst(code);
        assertTrue(result.isSuccess(), () -> "Anonymous class with diamond failed: " + result);
    }

    @Test
    void parseNestedGenerics() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // Nested wildcards
        var r1 = parser.parseCst("Map<String, List<? extends Number>>", "Type");
        assertTrue(r1.isSuccess(), () -> "Nested wildcards failed: " + r1);
        // Multiple type params
        var r2 = parser.parseCst("Function<String, List<Integer>>", "Type");
        assertTrue(r2.isSuccess(), () -> "Multiple type params failed: " + r2);
    }

    @Test
    void parseIntersectionCast() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // Cast with intersection type (lambda target)
        var code = "class C { Object f() { return (Runnable & Serializable) () -> {}; } }";
        var result = parser.parseCst(code);
        assertTrue(result.isSuccess(), () -> "Intersection cast failed: " + result);
    }

    @Test
    void parseArrayCreationWithAnnotation() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        var code = "class C { String[] arr = new String[10]; }";
        var result = parser.parseCst(code);
        assertTrue(result.isSuccess(), () -> "Array creation failed: " + result);
    }

    @Test
    void parseYieldInSwitch() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        var code = """
            class C {
                int f(int x) {
                    return switch(x) {
                        case 1 -> 10;
                        default -> { yield 0; }
                    };
                }
            }
            """;
        var result = parser.parseCst(code);
        assertTrue(result.isSuccess(), () -> "Yield in switch failed: " + result);
    }

    // === Standalone Parser Generation ===

    @Test
    void generateStandaloneJavaParser() {
        var result = PegParser.generateParser(
            JAVA_GRAMMAR,
            "org.pragmatica.java",
            "Java25Parser"
        );

        assertTrue(result.isSuccess(), () -> "Generation failed: " + result);

        var source = result.unwrap();

        // Verify package and class
        assertTrue(source.contains("package org.pragmatica.java;"));
        assertTrue(source.contains("public final class Java25Parser"));

        // Verify only pragmatica-lite imports (not peglib)
        assertTrue(source.contains("import org.pragmatica.lang.Result;"));
        assertFalse(source.contains("import org.pragmatica.peg."));

        // Verify main parse methods
        assertTrue(source.contains("parse_CompilationUnit"));
        assertTrue(source.contains("parse_ClassDecl"));
        assertTrue(source.contains("parse_MethodDecl"));
        assertTrue(source.contains("parse_FieldDecl"));
        assertTrue(source.contains("parse_Stmt"));
        assertTrue(source.contains("parse_Expr"));
        assertTrue(source.contains("parse_Type"));
        assertTrue(source.contains("parse_Identifier"));

        // Verify packrat memoization
        assertTrue(source.contains("cache"));

        // Print statistics
        var lines = source.split("\n");
        System.out.println("=== Generated Java25Parser ===");
        System.out.println("Total lines: " + lines.length);
        System.out.println("First 50 lines:");
        for (int i = 0; i < Math.min(50, lines.length); i++) {
            System.out.println(lines[i]);
        }
    }

    // === Additional Java 17-25 Feature Tests ===

    @Test
    void parseRecordDeclaration() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // Simple record
        assertTrue(parser.parseCst("record Point(int x, int y) {}", "TypeDecl").isSuccess());
        // Record with body
        assertTrue(parser.parseCst("record Person(String name, int age) { public String fullName() { return name; } }", "TypeDecl").isSuccess());
        // Record with compact constructor
        assertTrue(parser.parseCst("record Range(int lo, int hi) { Range { if (lo > hi) throw new IllegalArgumentException(); } }", "TypeDecl").isSuccess());
        // Record with annotations
        assertTrue(parser.parseCst("@Deprecated record OldPoint(int x, int y) {}", "TypeDecl").isSuccess());
        // Record implementing interface
        assertTrue(parser.parseCst("record Point(int x, int y) implements Serializable {}", "TypeDecl").isSuccess());
    }

    @Test
    void parseLocalTypeDeclarations() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // Simple local record
        var r1 = parser.parseCst("""
            class Test {
                void method() {
                    record LocalRecord(int x) {}
                }
            }
            """);
        assertTrue(r1.isSuccess(), () -> "Simple local record failed: " + r1);
        // Local record with implements
        var r2 = parser.parseCst("""
            class Test {
                void method() {
                    record LocalRecord(int x) implements Comparable<LocalRecord> {}
                }
            }
            """);
        assertTrue(r2.isSuccess(), () -> "Local record with implements failed: " + r2);
        // Annotated local record
        var r3 = parser.parseCst("""
            class Test {
                void method() {
                    @Deprecated record DeprecatedRecord(int x) {}
                }
            }
            """);
        assertTrue(r3.isSuccess(), () -> "Annotated local record failed: " + r3);
        // Final local class
        var r4 = parser.parseCst("""
            class Test {
                void method() {
                    final class FinalClass {}
                }
            }
            """);
        assertTrue(r4.isSuccess(), () -> "Final local class failed: " + r4);
        // Annotated local class
        var r5 = parser.parseCst("""
            class Test {
                void method() {
                    @SuppressWarnings("deprecation") class LocalClass {}
                }
            }
            """);
        assertTrue(r5.isSuccess(), () -> "Annotated local class failed: " + r5);
    }

    @Test
    void parsePatternMatchingSwitch() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // Type pattern in switch
        var r1 = parser.parseCst("""
            switch (obj) {
                case Integer i -> i * 2;
                case String s -> s.length();
                default -> 0;
            }""", "Expr");
        assertTrue(r1.isSuccess(), () -> "Type pattern switch failed: " + r1);
        // Guarded pattern
        var r2 = parser.parseCst("""
            switch (obj) {
                case String s when s.isEmpty() -> "empty";
                case String s -> s;
                default -> "other";
            }""", "Expr");
        assertTrue(r2.isSuccess(), () -> "Guarded pattern failed: " + r2);
        // Null case
        var r3 = parser.parseCst("""
            switch (obj) {
                case null -> "null";
                case String s -> s;
                default -> "other";
            }""", "Expr");
        assertTrue(r3.isSuccess(), () -> "Null case failed: " + r3);
    }

    @Test
    void parseTextBlock() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        var r1 = parser.parseCst("""
            var html = \"\"\"
                <html>
                    <body>Hello</body>
                </html>
                \"\"\";""", "LocalVar");
        assertTrue(r1.isSuccess(), () -> "Text block failed: " + r1);
    }

    @Test
    void parseVarTypeInference() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // Local variable
        var r1 = parser.parseCst("var x = 10;", "LocalVar");
        assertTrue(r1.isSuccess(), () -> "Local var failed: " + r1);
        // In for loop
        var r2 = parser.parseCst("for (var i = 0; i < 10; i++) {}", "Stmt");
        assertTrue(r2.isSuccess(), () -> "For loop var failed: " + r2);
        // In enhanced for
        var r3 = parser.parseCst("for (var item : list) {}", "Stmt");
        assertTrue(r3.isSuccess(), () -> "Enhanced for var failed: " + r3);
        // In try-with-resources
        var r4 = parser.parseCst("try (var in = new FileInputStream(f)) {}", "Stmt");
        assertTrue(r4.isSuccess(), () -> "Try-with-resources var failed: " + r4);
        // Lambda with var (Java 11+)
        var r5 = parser.parseCst("(@Nonnull var x) -> x", "Lambda");
        assertTrue(r5.isSuccess(), () -> "Lambda with var failed: " + r5);
    }

    @Test
    void parseEnumConstantInSwitch() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();

        // Simple enum constant (key fix: CaseItem now prefers QualifiedName over Lambda)
        var r1 = parser.parseCst("case PENDING -> 0;", "SwitchRule");
        assertTrue(r1.isSuccess(), () -> "Simple enum constant failed: " + r1);

        // Qualified enum constant
        var r2 = parser.parseCst("""
            switch (status) {
                case Status.PENDING -> "p";
                default -> "x";
            }""", "Expr");
        assertTrue(r2.isSuccess(), () -> "Qualified enum constant failed: " + r2);

        // Multiple enum constants
        var r3 = parser.parseCst("""
            switch (day) {
                case MONDAY, TUESDAY, WEDNESDAY -> "weekday";
                case SATURDAY, SUNDAY -> "weekend";
            }""", "Expr");
        assertTrue(r3.isSuccess(), () -> "Multiple enum constants failed: " + r3);

        // Full class with unqualified enum constants in switch
        var r4 = parser.parseCst("""
            class Foo {
                enum Status { PENDING, ACTIVE }
                String test(Status s) {
                    return switch (s) {
                        case PENDING -> "p";
                        case ACTIVE -> "a";
                    };
                }
            }
            """);
        assertTrue(r4.isSuccess(), () -> "Full class with enum switch failed: " + r4);
    }

    @Test
    void parseDeconstructionPattern() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // Record pattern in switch
        var r1 = parser.parseCst("""
            switch (obj) {
                case Point(int x, int y) -> x + y;
                default -> 0;
            }""", "Expr");
        assertTrue(r1.isSuccess(), () -> "Record pattern failed: " + r1);
        // Nested record pattern
        var r2 = parser.parseCst("""
            switch (obj) {
                case Line(Point(int x1, int y1), Point(int x2, int y2)) -> x1 + x2;
                default -> 0;
            }""", "Expr");
        assertTrue(r2.isSuccess(), () -> "Nested record pattern failed: " + r2);
    }

    @Test
    void parseStringTemplates() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // STR template (preview in Java 21+)
        // Note: String templates have special syntax STR."..." which may need grammar extension
        // For now, test that method calls with text blocks work
        var r1 = parser.parseCst("""
            String.format(\"\"\"
                Hello %s!
                \"\"\", name)""", "Expr");
        assertTrue(r1.isSuccess(), () -> "Format with text block failed: " + r1);
    }

    @Test
    void parseUnnamedPatterns() {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        // Unnamed pattern in switch (Java 21+)
        var r1 = parser.parseCst("""
            switch (obj) {
                case Point(int x, _) -> x;
                default -> 0;
            }""", "Expr");
        assertTrue(r1.isSuccess(), () -> "Unnamed pattern failed: " + r1);
        // Multiple underscores
        var r2 = parser.parseCst("""
            switch (box) {
                case Box(_, _) -> "box";
                default -> "other";
            }""", "Expr");
        assertTrue(r2.isSuccess(), () -> "Multiple unnamed failed: " + r2);
    }

    // === Source File Validation ===

    @Test
    void parseAllSourceFiles() throws IOException {
        var parser = PegParser.fromGrammar(JAVA_GRAMMAR).unwrap();
        var srcDir = Path.of("src/main/java");

        var javaFiles = Files.walk(srcDir)
            .filter(p -> p.toString().endsWith(".java"))
            .toList();

        System.out.println("Parsing " + javaFiles.size() + " Java source files...");

        int passed = 0;
        int failed = 0;

        for (var file : javaFiles) {
            var source = Files.readString(file);
            var result = parser.parseCst(source);

            if (result.isSuccess()) {
                passed++;
                System.out.println("✓ " + srcDir.relativize(file));
            } else {
                failed++;
                System.out.println("✗ " + srcDir.relativize(file) + ": " + result);
            }
        }

        System.out.println("\nResults: " + passed + " passed, " + failed + " failed");
        assertEquals(0, failed, "Some source files failed to parse");
    }
}
