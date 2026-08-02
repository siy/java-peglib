package org.pragmatica.peg.playground;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlaygroundServerTest {

    /**
     * 0.6.x request bodies carry grammar and input only — the 0.5.x
     * {@code recovery} / {@code packrat} / {@code startRule} / {@code mode}
     * knobs no longer exist server-side.
     */
    private static final String VALID_BODY = """
            {"grammar":"Sum <- Number '+' Number\\nNumber <- [0-9]+\\n%whitespace <- [ \\\\t]*\\n",
             "input":"12 + 34"}
            """;

    /** Parses with recovery: 'x' is unexpected where 'b' was required. */
    private static final String FAILING_BODY = """
            {"grammar":"Pair <- Head 'b'\\nHead <- 'a' '#'\\n",
             "input":"a#x"}
            """;

    private PlaygroundServer server;
    private HttpClient client;

    @BeforeEach
    void start() throws Exception {
        server = PlaygroundServer.start(0);
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void parseEndpoint_returnsValidJsonWithTreeAndStats() throws Exception {
        var response = post("/parse", VALID_BODY);
        assertThat(response.statusCode()).isEqualTo(200);

        Map<String, Object> parsed = TestJson.object(response.body());
        assertThat(parsed).containsKey("tree");
        assertThat(parsed).containsKey("stats");
        assertThat(parsed).containsKey("diagnostics");
        assertThat(parsed.get("ok")).isEqualTo(Boolean.TRUE);

        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) parsed.get("stats");
        assertThat(stats.get("nodeCount")).isInstanceOf(Number.class);
        assertThat(TestJson.num(stats, "nodeCount")).isGreaterThan(0L);
    }

    /**
     * Guards the wire contract {@code playground.js} renders against: the tree
     * node keys {@code renderNode} reads and the stats keys {@code renderStats}
     * interpolates. {@code ruleEntries} additionally proves the ParseTracer
     * walk is still wired into the response.
     */
    @Test
    void parseEndpoint_emitsJsonShapeTheFrontendRenders() throws Exception {
        var response = post("/parse", VALID_BODY);

        Map<String, Object> parsed = TestJson.object(response.body());

        @SuppressWarnings("unchecked")
        Map<String, Object> tree = (Map<String, Object>) parsed.get("tree");
        assertThat(tree).containsKeys("kind", "rule", "start", "end", "line", "column");
        assertThat(tree.get("kind")).isEqualTo("non-terminal");
        assertThat(tree.get("children")).isInstanceOf(List.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) parsed.get("stats");
        assertThat(stats).containsKeys("timeMicros",
                                       "nodeCount",
                                       "triviaCount",
                                       "ruleEntries",
                                       "diagnosticCount");
        assertThat(TestJson.num(stats, "ruleEntries")).isGreaterThan(0L);
    }

    @Test
    void parseEndpoint_diagnosticsCarrySeverityLineAndColumn() throws Exception {
        var response = post("/parse", FAILING_BODY);

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> parsed = TestJson.object(response.body());
        assertThat(parsed.get("ok")).isEqualTo(Boolean.FALSE);

        var diagnostics = (List< ? >) parsed.get("diagnostics");
        assertThat(diagnostics).isNotEmpty();
        assertThat(asJsonObject(diagnostics.getFirst())).containsKeys("severity",
                                                                     "message",
                                                                     "line",
                                                                     "column",
                                                                     "start",
                                                                     "end");
    }

    /** Decoded JSON objects arrive as raw {@code Object}; AssertJ needs the key type. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asJsonObject(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void parseEndpoint_rejectsMissingGrammar() throws Exception {
        String body = "{\"input\":\"x\"}";

        var response = post("/parse", body);

        assertThat(response.statusCode()).isEqualTo(400);
        Map<String, Object> parsed = TestJson.object(response.body());
        assertThat(parsed.get("error")).isEqualTo("bad request");
    }

    @Test
    void parseEndpoint_reportsGrammarErrorsWithoutFailingHttp() throws Exception {
        String body = "{\"grammar\":\"Broken <- [unclosed\",\"input\":\"x\"}";

        var response = post("/parse", body);

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> parsed = TestJson.object(response.body());
        assertThat(parsed.get("ok")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void staticIndex_isServedAtRoot() throws Exception {
        var response = get("/");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/html");
        assertThat(response.body()).contains("peglib playground");
    }

    @Test
    void staticJs_isServed() throws Exception {
        var response = get("/playground.js");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("/parse");
    }

    @Test
    void parseEndpoint_rejectsGet() throws Exception {
        var response = get("/parse");
        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    void staticHandler_rejectsPathTraversal() throws Exception {
        // Path containing ".." must be rejected with 400 regardless of whether
        // the underlying resource exists. java.net.http.HttpClient normalises
        // the URI so we pass the raw encoded form through to the server.
        var response = get("/../../etc/passwd");
        assertThat(response.statusCode()).isIn(400, 404);
    }

    @Test
    void staticHandler_attachesSecurityHeaders() throws Exception {
        var response = get("/");
        assertThat(response.headers().firstValue("X-Content-Type-Options").orElse(""))
            .isEqualTo("nosniff");
        assertThat(response.headers().firstValue("X-Frame-Options").orElse(""))
            .isEqualTo("DENY");
        assertThat(response.headers().firstValue("Referrer-Policy").orElse(""))
            .isEqualTo("no-referrer");
        assertThat(response.headers().firstValue("Cache-Control").orElse(""))
            .isEqualTo("no-store");
    }

    @Test
    void parseEndpoint_rejectsOversizedBody() throws Exception {
        // Build a body larger than the 1 MiB cap — content does not need to be
        // valid JSON because the size check precedes JSON parsing.
        int size = 1024 * 1024 + 1024;
        var body = "{\"grammar\":\"" + "a".repeat(size) + "\"}";
        var response = post("/parse", body);
        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void sanitizeStaticPath_acceptsNormalizedAssets() {
        assertThat(PlaygroundServer.sanitizeStaticPath("/").unwrap()).isEqualTo("/index.html");
        assertThat(PlaygroundServer.sanitizeStaticPath("/playground.js").unwrap()).isEqualTo("/playground.js");
        assertThat(PlaygroundServer.sanitizeStaticPath("//playground.js").unwrap()).isEqualTo("/playground.js");
    }

    @Test
    void sanitizeStaticPath_rejectsTraversalAndControlChars() {
        assertThat(PlaygroundServer.sanitizeStaticPath("/../secret").isEmpty()).isTrue();
        assertThat(PlaygroundServer.sanitizeStaticPath("/foo/../bar").isEmpty()).isTrue();
        assertThat(PlaygroundServer.sanitizeStaticPath("/foo\\bar").isEmpty()).isTrue();
        assertThat(PlaygroundServer.sanitizeStaticPath("/foobar").isEmpty()).isTrue();
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + server.port() + path))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                                 .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + server.port() + path))
                                 .GET()
                                 .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
