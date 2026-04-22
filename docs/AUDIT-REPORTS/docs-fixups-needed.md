# Doc / CHANGELOG fix-ups identified by audit

These are the only discrepancies found between CHANGELOG claims, reference docs, and implementation for releases 0.2.3–0.3.3. All are documentation / CHANGELOG issues — no code changes required. Severity: low (nothing affects the shipped behavior).

## 1. CHANGELOG 0.2.7 — incorrect uber-jar invocation

**Location:** `CHANGELOG.md` lines 222–224.

**Claim:**
```
CLI REPL: java -jar peglib-playground-0.2.7-uber.jar repl <grammar.peg>
Web UI:   java -jar peglib-playground-0.2.7-uber.jar server [--port 8080]
```

**Reality:** the uber-jar's `mainClass` is `PlaygroundServer` (see `peglib-playground/pom.xml` and `dependency-reduced-pom.xml`). There is no `repl` / `server` sub-command dispatcher. The actual invocations are:

- Web UI: `java -jar peglib-playground-0.2.7-uber.jar [--port 8080]` (no `server` keyword).
- REPL:   `java -cp peglib-playground-0.2.7-uber.jar org.pragmatica.peg.playground.PlaygroundRepl <grammar.peg> [--trace]`.

`docs/PLAYGROUND.md` documents the real invocation. Only the CHANGELOG entry is wrong.

**Fix:** update the CHANGELOG 0.2.7 "Added" bullets to match the actual invocation, or add a `MainDispatcher` that routes `args[0] == "repl"` / `args[0] == "server"` and update the uber-jar `mainClass` accordingly.

---

## 2. CHANGELOG 0.3.2 — nonexistent `IncrementalParser.builder(...)` API

**Location:** `CHANGELOG.md` line 43.

**Claim:**
```
Flag-gated via IncrementalParser.builder(...).triviaFastPathEnabled(true) — default off
```

**Reality:** no `IncrementalParser.builder()` method exists. Flag is exposed via the 3-arg factory `IncrementalParser.create(grammar, config, boolean triviaFastPathEnabled)`. `peglib-incremental/README.md` line 25 correctly shows the real signature.

**Fix:** update CHANGELOG 0.3.2 to reference `create(grammar, config, /* triviaFastPathEnabled */ true)`, or add a builder façade if a fluent API is actually desired.

---

## 3. CHANGELOG 0.3.3 — `Doc` algebra enumeration is incomplete

**Location:** `CHANGELOG.md` lines 14–15.

**Claim:** `Doc` sealed interface with records `Text, Line, Softline, Group, Indent, Concat, Empty` (7 records). `Docs` builders `text, line, softline, group, indent, concat, empty`.

**Reality:** `Doc.java` also ships `HardLine` (8 records). `Docs.java` also ships `hardline()`. `HardLine` is load-bearing for trivia preservation and is documented correctly in `docs/PRETTY-PRINTING.md`.

**Fix:** extend CHANGELOG 0.3.3 bullets to enumerate `HardLine` and `hardline()`.

---

## 4. (Informational) `RoundTripTest` still `@Disabled`

Not new — disclosed in 0.2.4 and referenced in every CHANGELOG entry through 0.3.3. Trailing intra-rule trivia attribution remains deferred. No action unless the deferred work is being scheduled.

---

## Scope confirmation

- No code stubs (`TODO`/`FIXME`/`UnsupportedOperationException`/`NotImplementedException`) exist in any `src/main` or `src/test` Java source across all 5 modules.
- All advertised public API methods, grammar directives, mojos, and CLI mains are wired end-to-end.
- All 10 design docs referenced by CHANGELOGs are present under `docs/`, plus both module READMEs under `peglib-incremental/` and `peglib-formatter/`.
- The three issues above are the entirety of the drift between CHANGELOG, docs, and implementation.
