package org.pragmatica.peg.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Immutability and composability checks for the 0.2.6 {@link Actions} type.
 */
class ActionsImmutabilityTest {

    record Number() implements RuleId {}
    record Sum() implements RuleId {}

    @Test
    void empty_returnsSingletonInstance() {
        assertSame(Actions.empty(), Actions.empty());
        assertTrue(Actions.empty().isEmpty());
        assertEquals(0, Actions.empty().size());
    }

    @Test
    void with_returnsNewInstance_originalUnchanged() {
        var base = Actions.empty();
        var extended = base.with(Number.class, sv -> 42);

        assertNotSame(base, extended);
        assertTrue(base.isEmpty());
        assertEquals(0, base.size());
        assertEquals(1, extended.size());
        assertNull(base.get("Number"));
    }

    @Test
    void with_chainedCalls_returnFreshInstances() {
        var step1 = Actions.empty().with(Number.class, sv -> 1);
        var step2 = step1.with(Sum.class, sv -> 2);

        assertNotSame(step1, step2);
        assertEquals(1, step1.size());
        assertEquals(2, step2.size());
        assertNull(step1.get("Sum"));
        assertEquals(Integer.valueOf(2), step2.get("Sum").apply(null));
    }

    @Test
    void with_sameClass_overridesEntry() {
        var first = Actions.empty().with(Number.class, sv -> 1);
        var second = first.with(Number.class, sv -> 99);

        assertEquals(1, second.size());
        assertEquals(Integer.valueOf(99), second.get("Number").apply(null));
        assertEquals(Integer.valueOf(1), first.get("Number").apply(null));
    }

    @Test
    void get_byClass_equivalentToGetByName() {
        var actions = Actions.empty().with(Number.class, sv -> 7);
        assertEquals(actions.get("Number").apply(null), actions.get(Number.class).apply(null));
    }
}
