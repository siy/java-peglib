# Peglib Playground

The `peglib-playground` module ships with peglib 0.2.7. It provides two
complementary surfaces for experimenting with a grammar: a terminal REPL and
an embedded web UI.

Both share a single backend (`PlaygroundEngine`) so results are consistent
across surfaces.

## Module coordinates

```xml
<dependency>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>peglib-playground</artifactId>
    <version>0.2.7</version>
</dependency>
```

The module depends on `peglib:0.2.7`. Distribution includes a regular
`peglib-playground-0.2.7.jar` (classpath use) and a runnable uberjar
`peglib-playground-0.2.7-uber.jar` (includes `peglib` and `pragmatica-lite:core`).

## CLI REPL

Launch against a grammar file:

```bash
java -cp peglib-playground-0.2.7-uber.jar \
     org.pragmatica.peg.playground.PlaygroundRepl \
     my-grammar.peg [--trace]
```

Each prompt line is parsed as input; each grammar-file save is auto-reloaded on
the next prompt. Built-in meta commands:

| Command | Purpose |
|---------|---------|
| `:reload` | Force grammar reload |
| `:status` | Show current settings |
| `:help` | List commands |
| `:quit` / `:q` / `:exit` | Exit |

The 0.5.x toggles (`:packrat`, `:recovery`, `:start`, `:trivia`, `:trace`) were
removed in 0.7.0. Under the 0.6.x design the grammar *is* the configuration
(spec decision 9): there is no packrat cache, recovery is always on, trivia is
always captured as tokens, and the start rule comes from the grammar.
| `:quit` | Exit |

Example session:

```
peg> 42
OK  nodes=1 trivia=0  0.238 ms
peg> :trace on
peg> 42
OK  nodes=1 trivia=0  0.112 ms
trace (2 events)
  rule entries: 1, cache hits: 0, misses: 0, puts: 0, cuts fired: 0
  RULE_ENTER     Number                         @0     +87us
  RULE_SUCCESS   Number                         @0     +104us
peg> :quit
```

## Web UI

Start the HTTP server:

```bash
java -jar peglib-playground-0.2.7-uber.jar          # default port 8080
java -jar peglib-playground-0.2.7-uber.jar --port 9090
```

Then open `http://localhost:8080` in a browser.

The page shows three panes — **grammar**, **input**, **output** — plus a strip
of controls:

- Start rule (empty = grammar's default)
- Mode: CST / AST
- Recovery: none / basic / advanced
- Packrat cache on/off
- Show trivia on/off
- Auto refresh on/off (default on, debounced 250 ms)

Every change to a pane or control triggers a parse and updates the output
pane with a rendered tree plus a stats line (time, node count, trivia, rule
entries, diagnostics).

No frameworks — vanilla JS + single CSS file. Add CodeMirror via CDN in
`index.html` if you want richer editing.

## HTTP API

### `POST /parse`

Request body (JSON):

```json
{
  "grammar": "Number <- < [0-9]+ >\n%whitespace <- [ \\t]*\n",
  "input": "42"
}
```

`grammar` and `input` are the only fields. The 0.5.x knobs (`startRule`,
`mode`, `recovery`, `packrat`, `trivia`) were removed in 0.7.0 — the grammar is
the configuration. Any of them still present in an inbound body is ignored
rather than rejected, so old clients keep working.

Decoding is handled by `org.pragmatica-lite:jackson`; a malformed body yields
HTTP 400 with the mapper's message in `detail`.

Response body (JSON):

```json
{
  "ok": true,
  "tree": { "kind": "token", "rule": "Number", "start": 0, "end": 2, ... },
  "diagnostics": [],
  "stats": {
    "timeMicros": 120,
    "nodeCount": 1,
    "triviaCount": 0,
    "ruleEntries": 1,
    "cacheHits": 0,
    "cacheMisses": 0,
    "cachePuts": 0,
    "cutsFired": 0,
    "diagnosticCount": 0
  }
}
```

If the grammar fails to parse, the response still returns HTTP 200 with
`"ok": false, "grammarError": "<message>", "tree": null`. Invalid request
bodies (missing `grammar`, malformed JSON) return HTTP 400.

### `GET /`

Serves `index.html` and the associated CSS/JS. All static resources live under
`/playground/` on the classpath.

## Programmatic access

`PlaygroundEngine.run(ParseRequest)` is the single entry point shared by both
surfaces. It returns `Result<ParseOutcome>` containing the CST (or empty on
grammar failure), accumulated diagnostics, stats, and a tracer with the full
event log.

```java
var request = new PlaygroundEngine.ParseRequest(grammarText, inputText);
var outcome = PlaygroundEngine.run(request).unwrap();
System.out.println(outcome.stats());
outcome.tracer().records().forEach(System.out::println);
```

## Tracer

`ParseTracer` is strictly additive — without explicit calls no events are
recorded and parse performance is unchanged. The playground synthesises
rule-entry / success / failure events from the resulting CST (one entry per
non-terminal node). Backtracked attempts are not visible; for that, use the
JMH `PackratStatsProbe` harness in the root module under `src/jmh`.

## Scope

The playground is intentionally small. Not in scope for v1:

- no single-step / breakpoint debugger
- no grammar-side WASM (future enhancement)
- no persistence; each request is stateless
- no authentication; run on `localhost` only

If you need richer capabilities, wrap `PlaygroundEngine` in your own server.
