package org.pragmatica.peg.playground;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.playground.internal.JsonDecoder;
import org.pragmatica.peg.playground.internal.JsonEncoder;
import org.pragmatica.peg.playground.internal.JsonEncoder.Diagnostics;
import org.pragmatica.peg.playground.PlaygroundEngine;
import org.pragmatica.peg.playground.PlaygroundEngine.ParseOutcome;
import org.pragmatica.peg.playground.PlaygroundEngine.ParseRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

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

    /**
     * JBCT boundary: CLI entry point invoked by the JVM. The HTTP handler
     * methods ({@link #handleParse}, {@link #handleStatic}) own the adapter
     * lift — {@link #parseRequestBody} returns {@code Result<ParseRequest>}
     * and request-body decode failures surface through that channel rather
     * than through {@code main}'s untyped boundary. This method merely
     * starts the server and registers a shutdown hook.
     */
    public static void main(String[] args) throws IOException {
        int port = parsePort(args);
        var server = start(port);
        System.out.println("peglib playground: http://localhost:" + server.port());
        System.out.println("press Ctrl-C to stop");
        Runtime.getRuntime()
               .addShutdownHook(new Thread(server::stop));
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
        return new PlaygroundServer(server,
                                    server.getAddress()
                                          .getPort());
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
                sendJson(exchange,
                         413,
                         Map.of("error",
                                "payload too large",
                                "detail",
                                "request body exceeds " + MAX_REQUEST_BODY_BYTES + " bytes"));
                return;
            }
            body = new String(bytes, StandardCharsets.UTF_8);
        }
        // 0.4.0 — Result.lift wraps the JSON-parse adapter; the validate step
        // stays as a pure Result so the bad-request branch surfaces both
        // decode and missing-field failures uniformly.
        var response = parseRequestBody(body).fold(PlaygroundServer::badRequest,
                                                   PlaygroundServer::runParse);
        sendJson(exchange,
                 response.status(),
                 response.payload());
    }

    /** An HTTP status paired with the JSON body to render at it. */
    private record JsonResponse(int status, Map<String, Object> payload) {
        static JsonResponse jsonResponse(int status, Map<String, Object> payload) {
            return new JsonResponse(status, payload);
        }
    }

    private static JsonResponse badRequest(Cause cause) {
        return JsonResponse.jsonResponse(400,
                                         Map.of("error", "bad request", "detail", cause.message()));
    }

    /**
     * Grammar-compile failures are a normal playground outcome, not an HTTP
     * error: both branches render into a 200 payload the SPA understands.
     */
    private static JsonResponse runParse(ParseRequest request) {
        return JsonResponse.jsonResponse(200,
                                         PlaygroundEngine.run(request)
                                                           .fold(PlaygroundServer::grammarErrorPayload,
                                                                 PlaygroundServer::buildResponse));
    }

    private static Map<String, Object> grammarErrorPayload(Cause cause) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("ok", Boolean.FALSE);
        payload.put("grammarError", cause.message());
        payload.put("tree", null);
        payload.put("diagnostics", List.of());
        payload.put("stats", Stats.empty());
        return payload;
    }

    /**
     * 0.6.x — every parse yields a tree, so {@code ok} reduces to "no
     * diagnostics were recorded"; the legacy "did we get a node at all" test
     * no longer has a false case.
     */
    private static Map<String, Object> buildResponse(ParseOutcome outcome) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("ok", !outcome.hasErrors());
        payload.put("tree", outcome.cst());
        payload.put("diagnostics",
                    Diagnostics.diagnostics(outcome.diagnostics(),
                                            outcome.cst()
                                                   .input()));
        payload.put("stats", statsWithTrace(outcome));
        return payload;
    }

    /**
     * Re-walk the finished CST with a {@link ParseTracer} so the response
     * carries the {@code ruleEntries} counter the SPA's stats line displays.
     * Timing and diagnostic counts stay as the engine measured them — the
     * tracer runs after the parse and its own clock would report walk time,
     * not parse time. {@code walk.trivia()} and the engine's
     * {@code triviaCount} are the same number by construction (both come from
     * {@link ParseTracer#countTrivia}). Packrat and cut counters are
     * structurally zero in 0.6.x (no memoization; cuts are elided at lex time).
     */
    private static Stats statsWithTrace(ParseOutcome outcome) {
        var tracer = ParseTracer.start();
        var walk = tracer.walkCst(outcome.cst());
        var measured = outcome.stats();
        return new Stats(measured.timeMicros(),
                         walk.nodes(),
                         walk.trivia(),
                         tracer.ruleEntries(),
                         0,
                         0,
                         0,
                         0,
                         measured.diagnosticCount());
    }

    /**
     * 0.4.0 — JBCT adapter boundary: {@link Result#lift} captures any
     * {@link IllegalArgumentException} raised by {@link JsonDecoder#decodeObject}
     * and the validation step propagates the missing-grammar failure through
     * the same monadic channel.
     */
    static Result<ParseRequest> parseRequestBody(String body) {
        return Result.lift(BadRequest::new,
                           () -> JsonDecoder.decodeObject(body))
                     .flatMap(PlaygroundServer::buildRequest);
    }

    /**
     * 0.6.x — the grammar is the configuration (spec decision 9), so a parse
     * request carries only grammar text and input. The 0.5.x {@code startRule},
     * {@code packrat}, {@code recovery} and {@code mode} fields are gone; any
     * still present in an inbound body are ignored rather than rejected.
     */
    private static Result<ParseRequest> buildRequest(Map<String, Object> obj) {
        String grammar = stringField(obj, "grammar", "");
        if (grammar.isEmpty()) {
            return new BadRequest("grammar field is required").result();
        }
        return Result.success(new ParseRequest(grammar,
                                               stringField(obj, "input", "")));
    }

    /** Adapter-boundary cause for parse-request decoding/validation failures. */
    record BadRequest(String message) implements Cause {
        BadRequest(Throwable t) {
            this(t.getMessage() == null
                 ? t.getClass()
                    .getSimpleName()
                 : t.getMessage());
        }
    }

    private static String stringField(Map<String, Object> obj, String key, String fallback) {
        Object value = obj.get(key);
        return value instanceof String s
               ? s
               : fallback;
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
        exchange.getResponseHeaders()
                .add("Content-Type",
                     contentType(safePath));
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
        for (int i = 0; i < rawPath.length(); i++ ) {
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
        if (!STATIC_PATH_ALLOWLIST.matcher(collapsed)
                                  .matches()) {
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
        byte[] body = JsonEncoder.encode(payload)
                                 .getBytes(StandardCharsets.UTF_8);
        addSecurityHeaders(exchange);
        exchange.getResponseHeaders()
                .add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void sendPlain(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        addSecurityHeaders(exchange);
        exchange.getResponseHeaders()
                .add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static int parsePort(String[] args) {
        for (int i = 0; i < args.length; i++ ) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                try{
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
