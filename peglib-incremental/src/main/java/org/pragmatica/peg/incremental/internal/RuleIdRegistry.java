package org.pragmatica.peg.incremental.internal;

import org.pragmatica.peg.action.RuleId;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.INIT_NAME;
import static java.lang.constant.ConstantDescs.MTD_void;

/**
 * Generates and caches {@link RuleId} subclasses whose
 * {@link Class#getSimpleName()} matches a given grammar rule name. Required
 * by {@code peglib-core}'s
 * {@link org.pragmatica.peg.parser.Parser#parseRuleAt(Class, String, int)}
 * contract: the rule to invoke is resolved from the class's simple name
 * (see {@code PegEngine#resolveRuleName}).
 *
 * <p>Since arbitrary grammar rule names cannot be known at compile time, we
 * synthesize a bytecode-generated {@link Class} per rule name on demand
 * using {@link ClassFile} (JEP 457, stable in JDK 25) and load it through a
 * dedicated, per-registry {@link ClassLoader} so each generated class has a
 * clean, non-hidden name: its {@code getSimpleName()} returns exactly the
 * rule name.
 *
 * <p>Classes are cached per rule-name; repeated lookups are lock-free reads
 * against a {@link ConcurrentHashMap}.
 *
 * @since 0.3.1
 */
final class RuleIdRegistry {
    private static final String PACKAGE = "org.pragmatica.peg.incremental.generated";
    private static final ClassDesc CD_RULE_ID = ClassDesc.of(RuleId.class.getName());

    private final GeneratedClassLoader loader = new GeneratedClassLoader(RuleIdRegistry.class.getClassLoader());
    private final Map<String, Class<? extends RuleId>> cache = new ConcurrentHashMap<>();

    /**
     * Return a {@link RuleId} {@link Class} whose simple name equals
     * {@code ruleName}. Result is cached; repeated calls for the same name
     * return the same class.
     */
    Class<? extends RuleId> classFor(String ruleName) {
        return cache.computeIfAbsent(ruleName, this::generate);
    }

    @SuppressWarnings("unchecked")
    private Class<? extends RuleId> generate(String ruleName) {
        if (!isValidJavaIdentifier(ruleName)) {
            throw new IllegalArgumentException("rule name is not a valid Java identifier: " + ruleName);
        }
        var classDesc = ClassDesc.of(PACKAGE + "." + ruleName);

        byte[] bytes = ClassFile.of().build(classDesc, classBuilder -> classBuilder
            .withSuperclass(CD_Object)
            .withInterfaceSymbols(CD_RULE_ID)
            .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL)
            .withMethodBody(INIT_NAME, MTD_void, ClassFile.ACC_PUBLIC, codeBuilder -> codeBuilder
                .aload(0)
                .invokespecial(CD_Object, INIT_NAME, MTD_void)
                .return_()));

        return (Class<? extends RuleId>) loader.define(PACKAGE + "." + ruleName, bytes);
    }

    private static boolean isValidJavaIdentifier(String s) {
        if (s == null || s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static final class GeneratedClassLoader extends ClassLoader {
        GeneratedClassLoader(ClassLoader parent) {
            super("peglib-incremental-rule-ids", parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
