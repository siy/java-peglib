package org.pragmatica.peg.playground;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.pragmatica.lang.Option;
import org.pragmatica.peg.error.RecoveryStrategy;
import org.pragmatica.peg.playground.PlaygroundEngine.ParseOutcome;
import org.pragmatica.peg.playground.PlaygroundEngine.ParseRequest;
import org.pragmatica.peg.playground.internal.JsonDecoder;
import org.pragmatica.peg.playground.internal.JsonEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Embedded HTTP server for the peglib playground. Binds to localhost on the
 * configured port, serves the static SPA under {@code /}, and accepts JSON
 * parse requests at {@code POST /parse}.
 *
 * <p>Command-line usage:
 * <pre>
 *   java -jar peglib-playground.jar            # default port 8080
 *   java -jar peglib-playground.jar --port 9090
 * </pre>
 */
public final class PlaygroundServer {
    private static final int DEFAULT_PORT = 8080;
    private static final int STATIC_READ_BUFFER = 4096;

    private final HttpServer httpServer;
    private final int port;

    private PlaygroundServer(HttpServer httpServer, int port) {
        this.httpServer = httpServer;
        this.port = port;
    }

    public static void main(String[] args) throws IOException {
        int port = parsePort(args);
        var server = start(port);
        System.out.println("peglib playground: http://localhost:" + server.port());
        System.out.println("press Ctrl-C to stop");
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }

    /**
     * Start the server on the given port and return the running instance.
     * Pass {@code 0} to let the OS choose an ephemeral port (used in tests).
     */
    public static PlaygroundServer start(int port) throws IOException {
        var address = new InetSocketAddress("localhost", port);
        var server = HttpServer.create(address, 0);
        server.createContext("/parse", PlaygroundServer::handleParse);
        server.createContext("/", PlaygroundServer::handleStatic);
        server.setExecutor(null);
        server.start();
        return new PlaygroundServer(server, server.getAddress().getPort());
    }

    public int port() {
        return port;
    }

    public void stop() {
        httpServer.stop(0);
    }

    // === /parse handler ===
    private static void handleParse(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "method not allowed"));
            return;
        }
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        ParseRequest request;
        try {
            request = parseRequestBody(body);
        } catch (IllegalArgumentException ex) {
            sendJson(exchange, 400, Map.of("error", "bad request", "detail", ex.getMessage()));
            return;
        }
        var result = PlaygroundEngine.run(request);
        if (result.isFailure()) {
            sendJson(exchange, 200, grammarErrorPayload(result.toString()));
            return;
        }
        sendJson(exchange, 200, buildResponse(result.unwrap()));
    }

    private static Map<String, Object> grammarErrorPayload(String message) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("ok", Boolean.FALSE);
        payload.put("grammarError", message);
        payload.put("tree", null);
        payload.put("diagnostics", List.of());
        payload.put("stats", Stats.empty());
        return payload;
    }

    private static Map<String, Object> buildResponse(ParseOutcome outcome) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("ok", outcome.hasNode() && !outcome.hasErrors());
        payload.put("tree", outcome.node().fold(() -> null, node -> node));
        payload.put("diagnostics", outcome.diagnostics());
        payload.put("stats", outcome.stats());
        return payload;
    }

    private static ParseRequest parseRequestBody(String body) {
        Map<String, Object> obj = JsonDecoder.decodeObject(body);
        String grammar = stringField(obj, "grammar", "");
        String input = stringField(obj, "input", "");
        Option<String> startRule = optionalString(obj, "startRule");
        boolean packrat = booleanField(obj, "packrat", true);
        boolean captureTrivia = booleanField(obj, "trivia", true);
        boolean astMode = "ast".equalsIgnoreCase(stringField(obj, "mode", "cst"));
        RecoveryStrategy recovery = parseRecovery(stringField(obj, "recovery", "BASIC"));
        if (grammar.isEmpty()) {
            throw new IllegalArgumentException("grammar field is required");
        }
        return new ParseRequest(grammar, input, startRule, packrat, recovery, captureTrivia, astMode);
    }

    private static RecoveryStrategy parseRecovery(String raw) {
        if (raw == null) {
            return RecoveryStrategy.BASIC;
        }
        try {
            return RecoveryStrategy.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return RecoveryStrategy.BASIC;
        }
    }

    private static String stringField(Map<String, Object> obj, String key, String fallback) {
        Object value = obj.get(key);
        return value instanceof String s ? s : fallback;
    }

    private static boolean booleanField(Map<String, Object> obj, String key, boolean fallback) {
        Object value = obj.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        return fallback;
    }

    private static Option<String> optionalString(Map<String, Object> obj, String key) {
        Object value = obj.get(key);
        if (value instanceof String s && !s.isBlank()) {
            return Option.some(s);
        }
        return Option.none();
    }

    // === static file handler ===
    private static void handleStatic(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "method not allowed"));
            return;
        }
        URI uri = exchange.getRequestURI();
        String path = uri.getPath();
        if (path == null || path.isEmpty() || path.equals("/")) {
            path = "/index.html";
        }
        String resourcePath = "/playground" + path;
        byte[] body = readResource(resourcePath);
        if (body == null) {
            sendPlain(exchange, 404, "not found: " + path);
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", contentType(path));
        exchange.sendResponseHeaders(200, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] readResource(String path) throws IOException {
        try (var in = PlaygroundServer.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            var buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[STATIC_READ_BUFFER];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            return buffer.toByteArray();
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    // === response helpers ===
    private static void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] body = JsonEncoder.encode(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void sendPlain(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static int parsePort(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException ex) {
                    System.err.println("invalid port, using default " + DEFAULT_PORT);
                    return DEFAULT_PORT;
                }
            }
        }
        return DEFAULT_PORT;
    }
}
