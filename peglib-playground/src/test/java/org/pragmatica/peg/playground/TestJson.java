package org.pragmatica.peg.playground;

import org.pragmatica.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * Test-only JSON reader.
 *
 * <p>Replaces the hand-written {@code JsonDecoder} that production code used
 * before 0.7.0. Tests want a plain value to assert against rather than a
 * {@code Result}, so this unwraps — a malformed payload in a test is a test
 * failure, and {@code unwrap()} surfaces it with the mapper's own message.
 */
public final class TestJson {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    private TestJson() {}

    /** Decode a JSON object into a string-keyed map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String json) {
        return (Map<String, Object>) MAPPER.readString(json, Map.class)
                                           .unwrap();
    }

    /** Decode an arbitrary JSON value (object or array). */
    public static Object any(String json) {
        return json.stripLeading()
                   .startsWith("[")
               ? MAPPER.readString(json, List.class)
                       .unwrap()
               : object(json);
    }

    /**
     * Numeric field as a {@code long}.
     *
     * <p>JSON has one number type; the boxed Java type a decoder picks is an
     * implementation detail. The pre-0.7.0 hand-written decoder boxed every
     * integer as {@code Long}, whereas Jackson narrows to {@code Integer} when
     * the value fits. Assert on the value, not the box.
     */
    public static long num(Map<String, Object> map, String key) {
        return ((Number) map.get(key)).longValue();
    }
}
