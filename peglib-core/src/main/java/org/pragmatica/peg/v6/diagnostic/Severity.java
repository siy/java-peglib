package org.pragmatica.peg.v6.diagnostic;

public enum Severity {
    ERROR("error"),
    WARNING("warning"),
    INFO("info");

    private final String label;

    Severity(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
