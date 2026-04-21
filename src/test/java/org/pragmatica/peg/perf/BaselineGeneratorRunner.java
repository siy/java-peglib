package org.pragmatica.peg.perf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Manual one-shot: run with {@code -Dperf.regen=1} to regenerate
 * {@code src/test/resources/perf-corpus-baseline/} artifacts. Disabled by default so
 * {@link BaselineGenerator#main} remains the primary entry point and baselines are not
 * silently rewritten by the normal test run.
 */
@EnabledIfSystemProperty(named = "perf.regen", matches = "1")
class BaselineGeneratorRunner {

    @Test
    void regenerate() throws Exception {
        System.out.println(BaselineGenerator.run());
    }
}
