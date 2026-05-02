package org.pragmatica.peg.playground;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

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
    /** Max size of an inbound request body (1 MiB). Larger bodies are rejected with HTTP 413. */
    private static final int MAX_REQUEST_BODY_BYTES = 1024 * 1024;
    /** Allow-list for static-asset paths after slash normalization. */
    private static final Pattern STATIC_PATH_ALLOWLIST = Pattern.compile("^/[A-Za-z0-9._/-]*$");

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
            // Read one more byte than the cap — if the stream still has data,
            // the request exceeds the limit and is rejected without buffering
            // the whole payload.
            byte[] bytes = in.readNBytes(MAX_REQUEST_BODY_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BODY_BYTES) {
                sendJson(exchange, 413,
                         Map.of("error", "payload too large",
                                "detail", "request body exceeds " + MAX_REQUEST_BODY_BYTES + " bytes"));
                return;
            }
            body = new String(bytes, StandardCharsets.UTF_8);
        }
        // 0.4.0 — Result.lift wraps the JSON-parse adapter; the validate step
        // stays as a pure Result so the bad-request branch surfaces both
        // decode and missing-field failures uniformly.
        var requestResult = parseRequestBody(body);
        if (requestResult.isFailure()) {
            sendJson(exchange, 400, badRequestPayload(requestResult));
            return;
        }
        var result = PlaygroundEngine.run(requestResult.unwrap());
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

    /**
     * 0.4.0 — JBCT adapter boundary: {@link Result#lift} captures any
     * {@link IllegalArgumentException} raised by {@link JsonDecoder#decodeObject}
     * and the validation step propagates the missing-grammar failure through
     * the same monadic channel.
     */
    static Result<ParseRequest> parseRequestBody(String body) {
        return Result.lift(BadRequest::new, () -> JsonDecoder.decodeObject(body))
                     .flatMap(PlaygroundServer::buildRequest);
    }

    private static Result<ParseRequest> buildRequest(Map<String, Object> obj) {
        String grammar = stringField(obj, "grammar", "");
        if (grammar.isEmpty()) {
            return new BadRequest("grammar field is required").result();
        }
        return Result.success(new ParseRequest(
                grammar,
                stringField(obj, "input", ""),
                optionalString(obj, "startRule"),
                booleanField(obj, "packrat", true),
                parseRecovery(stringField(obj, "recovery", "BASIC")),
                booleanField(obj, "trivia", true),
                "ast".equalsIgnoreCase(stringField(obj, "mode", "cst"))));
    }

    private static Map<String, Object> badRequestPayload(Result<ParseRequest> failed) {
        var detail = failed.fold(Cause::message, _ -> "");
        return Map.of("error", "bad request", "detail", detail);
    }

    /** Adapter-boundary cause for parse-request decoding/validation failures. */
    record BadRequest(String message) implements Cause {
        BadRequest(Throwable t) {
            this(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
    }

    private static RecoveryStrategy parseRecovery(String raw) {
        if (raw == null) {
            return RecoveryStrategy.BASIC;
        }
        try {
            return RecoveryStrategy.valueOf(raw.toUpperCase(Locale.ROOT));
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
        String rawPath = uri.getPath();
        String safePath = sanitizeStaticPath(rawPath);
        if (safePath == null) {
            sendPlain(exchange, 400, "bad request");
            return;
        }
        String resourcePath = "/playground" + safePath;
        byte[] body = readResource(resourcePath);
        if (body == null) {
            sendPlain(exchange, 404, "not found: " + safePath);
            return;
        }
        addSecurityHeaders(exchange);
        exchange.getResponseHeaders().add("Content-Type", contentType(safePath));
        exchange.sendResponseHeaders(200, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * Normalise and validate a static-asset path from an inbound URI. Rejects
     * traversal segments ({@code ..}), backslashes, control characters, and
     * anything not matching the {@link #STATIC_PATH_ALLOWLIST} regex after
     * collapsing repeated slashes. Returns the sanitized path or {@code null}
     * when the input is unsafe.
     */
    static String sanitizeStaticPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty() || rawPath.equals("/")) {
            return "/index.html";
        }
        for (int i = 0; i < rawPath.length(); i++) {
            char c = rawPath.charAt(i);
            if (c < 0x20 || c == '\\') {
                return null;
            }
        }
        // Collapse repeated slashes; reject any ".." segment.
        String collapsed = rawPath.replaceAll("/+", "/");
        for (var segment : collapsed.split("/")) {
            if ("..".equals(segment)) {
                return null;
            }
        }
        if (!STATIC_PATH_ALLOWLIST.matcher(collapsed).matches()) {
            return null;
        }
        return collapsed;
    }

    /**
     * Attach a conservative set of security headers to every response. Applied
     * to both JSON and static routes so tests or intermediaries can rely on
     * their presence.
     */
    private static void addSecurityHeaders(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.add("X-Content-Type-Options", "nosniff");
        headers.add("X-Frame-Options", "DENY");
        headers.add("Referrer-Policy", "no-referrer");
        headers.add("Cache-Control", "no-store");
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
        addSecurityHeaders(exchange);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void sendPlain(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        addSecurityHeaders(exchange);
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
