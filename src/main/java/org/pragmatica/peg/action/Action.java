package org.pragmatica.peg.action;
/**
 * Functional interface for semantic actions.
 */
@FunctionalInterface
public interface Action {
    /**
     * Execute the action with semantic values.
     *
     * @param sv the semantic values from parsing
     * @return the computed semantic value
     */
    Object apply(SemanticValues sv);
}
