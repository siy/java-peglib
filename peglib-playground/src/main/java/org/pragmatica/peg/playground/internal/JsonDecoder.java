package org.pragmatica.peg.playground.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON decoder for the playground's {@code POST /parse} request
 * bodies. Supports strings, numbers, booleans, null, arrays, and objects.
 * Intentionally permissive about whitespace. Not intended for arbitrary
 * third-party JSON.
 *
 * <p>Returns {@code Object} trees: {@code Map<String,Object>} for JSON
 * objects, {@code List<Object>} for arrays, {@code String}, {@code Long}
 * or {@code Double} for numbers, {@code Boolean}, or {@code null}.
 */
public final class JsonDecoder {
    private final String input;
    private int pos;

    private JsonDecoder(String input) {
        this.input = input;
    }

    public static Object decode(String text) {
        if (text == null || text.isBlank()) {
            return new LinkedHashMap<>();
        }
        var decoder = new JsonDecoder(text);
        decoder.skipWs();
        return decoder.parseValue();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> decodeObject(String text) {
        var decoded = decode(text);
        if (decoded instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new LinkedHashMap<>();
    }

    private Object parseValue() {
        skipWs();
        if (pos >= input.length()) {
            throw new IllegalArgumentException("unexpected end of JSON input");
        }
        char c = input.charAt(pos);
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObject() {
        expect('{');
        var result = new LinkedHashMap<String, Object>();
        skipWs();
        if (peek() == '}') {
            pos++;
            return result;
        }
        while (true) {
            skipWs();
            String key = parseString();
            skipWs();
            expect(':');
            Object value = parseValue();
            result.put(key, value);
            skipWs();
            char nc = peek();
            if (nc == ',') {
                pos++;
                continue;
            }
            if (nc == '}') {
                pos++;
                return result;
            }
            throw new IllegalArgumentException("expected ',' or '}' at offset " + pos);
        }
    }

    private List<Object> parseArray() {
        expect('[');
        var result = new ArrayList<>();
        skipWs();
        if (peek() == ']') {
            pos++;
            return result;
        }
        while (true) {
            Object value = parseValue();
            result.add(value);
            skipWs();
            char nc = peek();
            if (nc == ',') {
                pos++;
                continue;
            }
            if (nc == ']') {
                pos++;
                return result;
            }
            throw new IllegalArgumentException("expected ',' or ']' at offset " + pos);
        }
    }

    private String parseString() {
        expect('"');
        var sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos >= input.length()) {
                    throw new IllegalArgumentException("unterminated escape in string");
                }
                char esc = input.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > input.length()) {
                            throw new IllegalArgumentException("bad unicode escape");
                        }
                        String hex = input.substring(pos, pos + 4);
                        pos += 4;
                        sb.append((char) Integer.parseInt(hex, 16));
                    }
                    default -> throw new IllegalArgumentException("bad escape: \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("unterminated string");
    }

    private Boolean parseBoolean() {
        if (input.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (input.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("expected boolean at offset " + pos);
    }

    private Object parseNull() {
        if (input.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new IllegalArgumentException("expected null at offset " + pos);
    }

    private Object parseNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        boolean isFloat = false;
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (Character.isDigit(c)) {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                isFloat = true;
                pos++;
            } else {
                break;
            }
        }
        String num = input.substring(start, pos);
        if (num.isEmpty() || num.equals("-")) {
            throw new IllegalArgumentException("bad number at offset " + start);
        }
        if (isFloat) {
            return Double.valueOf(num);
        }
        return Long.valueOf(num);
    }

    private void expect(char c) {
        if (pos >= input.length() || input.charAt(pos) != c) {
            throw new IllegalArgumentException("expected '" + c + "' at offset " + pos);
        }
        pos++;
    }

    private char peek() {
        if (pos >= input.length()) {
            return '\0';
        }
        return input.charAt(pos);
    }

    private void skipWs() {
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                pos++;
            } else {
                break;
            }
        }
    }
}
