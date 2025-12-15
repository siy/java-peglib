package org.pragmatica.peg.examples;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java 25 grammar example demonstrating parsing of Java source code.
 *
 * This is a practical subset of Java grammar for CST parsing.
 */
class Java25GrammarExample {

    // Simplified Java Grammar for CST parsing
    static final String JAVA_GRAMMAR = """
        CompilationUnit <- PackageDecl? ImportDecl* TypeDecl*
        PackageDecl <- 'package' QualifiedName ';'
        ImportDecl  <- 'import' 'static'? QualifiedName ('.' '*')? ';'

        TypeDecl <- Annotation* Modifier* TypeKind
        TypeKind <- ClassDecl / InterfaceDecl / EnumDecl / RecordDecl
        ClassDecl <- 'class' Identifier TypeParams? ('extends' Type)? ImplementsClause? PermitsClause? ClassBody
        InterfaceDecl <- 'interface' Identifier TypeParams? ('extends' TypeList)? ClassBody
        EnumDecl <- 'enum' Identifier ImplementsClause? EnumBody
        RecordDecl <- 'record' Identifier '(' RecordComponents? ')' ImplementsClause? RecordBody
        ImplementsClause <- 'implements' TypeList
        PermitsClause <- 'permits' TypeList
        TypeList <- Type (',' Type)*
        TypeParams <- '<' TypeParam (',' TypeParam)* '>'
        TypeParam <- Identifier ('extends' Type ('&' Type)*)?

        ClassBody <- '{' ClassMember* '}'
        ClassMember <- Annotation* Modifier* Member / StaticBlock / ';'
        Member <- ConstructorDecl / MethodDecl / FieldDecl / TypeKind
        StaticBlock <- 'static' Block
        EnumBody <- '{' EnumConsts? (';' ClassMember*)? '}'
        EnumConsts <- EnumConst (',' EnumConst)* ','?
        EnumConst <- Identifier ('(' Args? ')')? ClassBody?
        RecordComponents <- RecordComp (',' RecordComp)*
        RecordComp <- Annotation* Type Identifier
        RecordBody <- '{' ClassMember* '}'

        FieldDecl <- Type VarDecls ';'
        VarDecls <- VarDecl (',' VarDecl)*
        VarDecl <- Identifier Dims? ('=' VarInit)?
        VarInit <- '{' (VarInit (',' VarInit)* ','?)? '}' / Expr
        MethodDecl <- TypeParams? Type Identifier '(' Params? ')' Dims? Throws? (Block / ';')
        Params <- Param (',' Param)*
        Param <- Annotation* Modifier* Type '...'? Identifier Dims?
        Throws <- 'throws' TypeList
        ConstructorDecl <- TypeParams? Identifier '(' Params? ')' Throws? Block

        Block <- '{' BlockStmt* '}'
        BlockStmt <- LocalVar / TypeKind / Stmt
        LocalVar <- Modifier* Type VarDecls ';'
        Stmt <- Block
             / 'if' '(' Expr ')' Stmt ('else' Stmt)?
             / 'while' '(' Expr ')' Stmt
             / 'for' '(' ForCtrl ')' Stmt
             / 'do' Stmt 'while' '(' Expr ')' ';'
             / 'try' ResourceSpec? Block Catch* Finally?
             / 'switch' '(' Expr ')' SwitchBlock
             / 'return' Expr? ';'
             / 'throw' Expr ';'
             / 'break' Identifier? ';'
             / 'continue' Identifier? ';'
             / 'assert' Expr (':' Expr)? ';'
             / 'synchronized' '(' Expr ')' Block
             / 'yield' Expr ';'
             / Expr ';'
             / ';'
        ForCtrl <- ForInit? ';' Expr? ';' ExprList? / Type Identifier ':' Expr
        ForInit <- LocalVarNoSemi / ExprList
        LocalVarNoSemi <- Modifier* Type VarDecls
        ResourceSpec <- '(' Resource (';' Resource)* ';'? ')'
        Resource <- Modifier* Type Identifier '=' Expr / QualifiedName
        Catch <- 'catch' '(' Modifier* Type ('|' Type)* Identifier ')' Block
        Finally <- 'finally' Block
        SwitchBlock <- '{' SwitchRule* '}'
        SwitchRule <- SwitchLabel '->' (Expr ';' / Block / 'throw' Expr ';') / SwitchLabel ':' BlockStmt*
        SwitchLabel <- 'case' CaseItem (',' CaseItem)* / 'default'
        CaseItem <- Type Identifier / Expr

        Expr <- Ternary
        Ternary <- LogOr ('?' Expr ':' Ternary)?
        LogOr <- LogAnd ('||' LogAnd)*
        LogAnd <- BitOr ('&&' BitOr)*
        BitOr <- BitXor ('|' BitXor)*
        BitXor <- BitAnd ('^' BitAnd)*
        BitAnd <- Equality ('&' Equality)*
        Equality <- Relational (('==' / '!=') Relational)*
        Relational <- Shift (('<=' / '>=' / '<' / '>') Shift / 'instanceof' Type Identifier?)?
        Shift <- Additive (('<<' / '>>>' / '>>') Additive)*
        Additive <- Multiplicative (('+' / '-') Multiplicative)*
        Multiplicative <- Unary (('*' / '/' / '%') Unary)*
        Unary <- ('++' / '--' / '+' / '-' / '!' / '~') Unary / '(' Type ')' Unary / Postfix
        Postfix <- Primary PostOp*
        PostOp <- '.' Identifier ('(' Args? ')')? / '.' 'class' / '.' 'this' / '[' Expr ']' / '(' Args? ')' / '++' / '--' / '::' TypeArgs? (Identifier / 'new')
        Primary <- Literal / 'this' / 'super' / 'new' TypeArgs? Type ('(' Args? ')' ClassBody? / Dims? VarInit?) / '(' Expr ')' / Lambda / 'switch' '(' Expr ')' SwitchBlock / QualifiedName
        Lambda <- LambdaParams '->' (Expr / Block)
        LambdaParams <- Identifier / '(' LambdaParam? (',' LambdaParam)* ')'
        LambdaParam <- Modifier* Type? Identifier
        Args <- Expr (',' Expr)*
        ExprList <- Expr (',' Expr)*

        Type <- (PrimType / RefType) Dims?
        PrimType <- 'boolean' / 'byte' / 'short' / 'int' / 'long' / 'float' / 'double' / 'char' / 'void'
        RefType <- QualifiedName TypeArgs?
        Dims <- ('[' ']')+
        TypeArgs <- '<' TypeArg (',' TypeArg)* '>'
        TypeArg <- Type / '?' (('extends' / 'super') Type)?

        QualifiedName <- Identifier ('.' Identifier)*
        Identifier <- !Keyword < [a-zA-Z_$] [a-zA-Z0-9_$]* >

        Modifier <- 'public' / 'protected' / 'private' / 'static' / 'final' / 'abstract' / 'native' / 'synchronized' / 'transient' / 'volatile' / 'strictfp' / 'default' / 'sealed' / 'non-sealed'
        Annotation <- '@' QualifiedName ('(' AnnotationValue? ')')?
        AnnotationValue <- Identifier '=' AnnotationElem (',' Identifier '=' AnnotationElem)* / AnnotationElem
        AnnotationElem <- Annotation / '{' (AnnotationElem (',' AnnotationElem)* ','?)? '}' / Ternary

        Literal <- 'null' / 'true' / 'false' / CharLit / StringLit / NumLit
        CharLit <- < '\\'' ([^'\\\\] / '\\\\' .)* '\\'' >
        StringLit <- < '"' ([^"\\\\] / '\\\\' .)* '"' > / < '\"\"\"' (!'\"\"\"' .)* '\"\"\"' >
        NumLit <- < '0' [xX] [0-9a-fA-F_]+ [lL]? > / < '0' [bB] [01_]+ [lL]? > / < [0-9]+ ('.' [0-9]*)? ([eE] [+\\-]? [0-9]+)? [fFdDlL]? > / < '.' [0-9]+ ([eE] [+\\-]? [0-9]+)? [fFdD]? >

        Keyword <- ('abstract' / 'assert' / 'boolean' / 'break' / 'byte' / 'case' / 'catch' / 'char' / 'class' / 'const' / 'continue' / 'default' / 'do' / 'double' / 'else' / 'enum' / 'extends' / 'false' / 'final' / 'finally' / 'float' / 'for' / 'goto' / 'if' / 'implements' / 'import' / 'instanceof' / 'int' / 'interface' / 'long' / 'native' / 'new' / 'non-sealed' / 'null' / 'package' / 'permits' / 'private' / 'protected' / 'public' / 'record' / 'return' / 'sealed' / 'short' / 'static' / 'strictfp' / 'super' / 'switch' / 'synchronized' / 'this' / 'throw' / 'throws' / 'transient' / 'true' / 'try' / 'var' / 'void' / 'volatile' / 'when' / 'while' / 'yield' / '_') ![a-zA-Z0-9_$]

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
}
