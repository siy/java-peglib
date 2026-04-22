package org.pragmatica.peg.playground;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.playground.internal.JsonDecoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlaygroundServerTest {

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
        String body = """
                {"grammar":"Number <- < [0-9]+ >\\n%whitespace <- [ \\\\t]*\\n",
                 "input":"42",
                 "recovery":"BASIC",
                 "packrat":true,
                 "trivia":true}
                """;

        var response = post("/parse", body);
        assertThat(response.statusCode()).isEqualTo(200);

        Map<String, Object> parsed = JsonDecoder.decodeObject(response.body());
        assertThat(parsed).containsKey("tree");
        assertThat(parsed).containsKey("stats");
        assertThat(parsed).containsKey("diagnostics");
        assertThat(parsed.get("ok")).isEqualTo(Boolean.TRUE);

        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) parsed.get("stats");
        assertThat(stats.get("nodeCount")).isInstanceOf(Long.class);
        assertThat(((Long) stats.get("nodeCount"))).isGreaterThan(0L);
    }

    @Test
    void parseEndpoint_rejectsMissingGrammar() throws Exception {
        String body = "{\"input\":\"x\"}";

        var response = post("/parse", body);

        assertThat(response.statusCode()).isEqualTo(400);
        Map<String, Object> parsed = JsonDecoder.decodeObject(response.body());
        assertThat(parsed.get("error")).isEqualTo("bad request");
    }

    @Test
    void parseEndpoint_reportsGrammarErrorsWithoutFailingHttp() throws Exception {
        String body = "{\"grammar\":\"Broken <- [unclosed\",\"input\":\"x\"}";

        var response = post("/parse", body);

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> parsed = JsonDecoder.decodeObject(response.body());
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
