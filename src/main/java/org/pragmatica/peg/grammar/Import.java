package org.pragmatica.peg.grammar;

import org.pragmatica.lang.Option;
import org.pragmatica.peg.tree.SourceSpan;

/**
 * 0.2.8 — Grammar-level {@code %import} directive.
 *
 * <p>Forms:
 * <ul>
 *   <li>{@code %import GrammarName.RuleName} — imports the rule, exposed under the
 *       composed-grammar name {@code GrammarName_RuleName}. No alias present.</li>
 *   <li>{@code %import GrammarName.RuleName as LocalName} — imports the rule,
 *       exposed under {@code LocalName} (no grammar-name prefix).</li>
 * </ul>
 *
 * <p>Transitive closure: when a composed grammar is resolved, the imported rule
 * plus every rule it references (recursively) is inlined into the composed
 * {@link Grammar}. Internal references keep their grammar-qualified names
 * ({@code GrammarName_InnerRule}); only the top-level imported name is affected
 * by {@code as} renames.
 *
 * <p>Collision policy: for explicit imports, a name clash with a root rule is an
 * error unless an explicit {@code as} rename is given. For transitively-pulled
 * rules, the root definition silently shadows by name.
 */
public record Import(
 SourceSpan span,
 String grammarName,
 String ruleName,
 Option<String> alias) {
    /**
     * Local name this import exposes into the composed grammar.
     * When {@code alias} is present, uses the alias verbatim; otherwise
     * returns {@code grammarName_ruleName} (underscore-joined).
     */
    public String localName() {
        return alias.fold(() -> grammarName + "_" + ruleName, a -> a);
    }

    /**
     * Prefixed name used for internal (transitive) rules from the imported grammar.
     * Always {@code grammarName_ruleName} regardless of any top-level alias.
     */
    public static String prefixedName(String grammarName, String ruleName) {
        return grammarName + "_" + ruleName;
    }
}
