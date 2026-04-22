"use strict";

const els = {
    grammar: document.getElementById("grammar"),
    input: document.getElementById("input"),
    startRule: document.getElementById("start-rule"),
    mode: document.getElementById("mode"),
    recovery: document.getElementById("recovery"),
    packrat: document.getElementById("packrat"),
    trivia: document.getElementById("trivia"),
    autoRefresh: document.getElementById("auto-refresh"),
    parseBtn: document.getElementById("parse-btn"),
    output: document.getElementById("output"),
    diagnostics: document.getElementById("diagnostics"),
    stats: document.getElementById("stats-line"),
    status: document.getElementById("status"),
};

let debounceTimer = null;
let inflight = null;

function buildRequest() {
    return {
        grammar: els.grammar.value,
        input: els.input.value,
        startRule: els.startRule.value.trim(),
        mode: els.mode.value,
        recovery: els.recovery.value,
        packrat: els.packrat.checked,
        trivia: els.trivia.checked,
    };
}

async function parseNow() {
    if (inflight) {
        inflight.abort();
    }
    const controller = new AbortController();
    inflight = controller;
    setStatus("parsing…", "");
    try {
        const response = await fetch("/parse", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(buildRequest()),
            signal: controller.signal,
        });
        if (!response.ok) {
            setStatus("server error " + response.status, "err");
            return;
        }
        const data = await response.json();
        render(data);
    } catch (err) {
        if (err.name === "AbortError") return;
        setStatus("network error: " + err.message, "err");
    } finally {
        if (inflight === controller) inflight = null;
    }
}

function render(data) {
    renderTree(data.tree);
    renderDiagnostics(data.diagnostics || []);
    renderStats(data.stats, data.ok, data.grammarError);
    if (data.grammarError) {
        setStatus("grammar error", "err");
    } else if (data.ok) {
        setStatus("ok", "ok");
    } else {
        setStatus("parsed with errors", "err");
    }
}

function renderTree(tree) {
    els.output.innerHTML = "";
    if (!tree) {
        const div = document.createElement("div");
        div.className = "tree-line tree-error";
        div.textContent = "(no tree)";
        els.output.appendChild(div);
        return;
    }
    renderNode(tree, 0, els.output);
}

function renderNode(node, depth, parent) {
    const indent = "  ".repeat(depth);
    const showTrivia = els.trivia.checked;
    if (showTrivia && node.leadingTrivia) {
        for (const tr of node.leadingTrivia) {
            appendLine(parent, indent + "· leading " + tr.kind + " " + JSON.stringify(tr.text), "tree-trivia");
        }
    }
    if (node.kind === "non-terminal") {
        appendLine(parent, indent + node.rule + " [" + node.start + "-" + node.end + "]", "tree-rule");
        for (const c of node.children || []) {
            renderNode(c, depth + 1, parent);
        }
    } else if (node.kind === "terminal") {
        appendLine(parent, indent + node.rule + " = " + JSON.stringify(node.text || ""), "tree-terminal");
    } else if (node.kind === "token") {
        appendLine(parent, indent + "<" + node.rule + "> " + JSON.stringify(node.text || ""), "tree-token");
    } else if (node.kind === "error") {
        appendLine(parent, indent + "<error> skipped=" + JSON.stringify(node.skipped || "") + " expected=" + JSON.stringify(node.expected || ""), "tree-error");
    } else {
        appendLine(parent, indent + "(" + node.kind + ")", "tree-rule");
    }
    if (showTrivia && node.trailingTrivia) {
        for (const tr of node.trailingTrivia) {
            appendLine(parent, indent + "· trailing " + tr.kind + " " + JSON.stringify(tr.text), "tree-trivia");
        }
    }
}

function appendLine(parent, text, cls) {
    const div = document.createElement("div");
    div.className = "tree-line " + cls;
    div.textContent = text;
    parent.appendChild(div);
}

function renderDiagnostics(list) {
    els.diagnostics.innerHTML = "";
    for (const diag of list) {
        const div = document.createElement("div");
        div.className = "diag";
        div.textContent = diag.severity + " at " + diag.line + ":" + diag.column + ": " + diag.message;
        els.diagnostics.appendChild(div);
    }
}

function renderStats(stats, ok, grammarError) {
    if (grammarError) {
        els.stats.textContent = "grammar error";
        return;
    }
    if (!stats) {
        els.stats.textContent = "—";
        return;
    }
    const ms = (stats.timeMicros / 1000).toFixed(2);
    els.stats.textContent = `${ms} ms · nodes=${stats.nodeCount} trivia=${stats.triviaCount} rules=${stats.ruleEntries} diag=${stats.diagnosticCount}`;
}

function setStatus(text, kind) {
    els.status.textContent = text;
    els.status.parentElement.className = "status-bar" + (kind ? " " + kind : "");
}

function scheduleAutoRefresh() {
    if (!els.autoRefresh.checked) return;
    if (debounceTimer) clearTimeout(debounceTimer);
    debounceTimer = setTimeout(parseNow, 250);
}

els.parseBtn.addEventListener("click", parseNow);
for (const el of [els.grammar, els.input, els.startRule]) {
    el.addEventListener("input", scheduleAutoRefresh);
}
for (const el of [els.mode, els.recovery]) {
    el.addEventListener("change", parseNow);
}
for (const el of [els.packrat, els.trivia, els.autoRefresh]) {
    el.addEventListener("change", parseNow);
}

// initial parse
parseNow();
