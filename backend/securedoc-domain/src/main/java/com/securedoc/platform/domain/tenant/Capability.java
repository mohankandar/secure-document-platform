package com.securedoc.platform.domain.tenant;

import java.util.Objects;

/**
 * Represents a platform capability that can be enabled/disabled per tenant.
 */
public enum Capability {

    /** Document upload and package management. */
    DOCUMENT_UPLOAD("Document Upload"),

    /** OCR and text extraction processing. */
    OCR_PROCESSING("OCR Processing"),

    /** Sensitive-data detection. */
    SENSITIVE_DATA_DETECTION("Sensitive Data Detection"),

    /** Policy-driven redaction. */
    REDACTION("Redaction"),

    /** Human review workflows. */
    HUMAN_REVIEW("Human Review"),

    /** Secure vault registration. */
    VAULT("Vault"),

    /** Audit timeline and reporting. */
    AUDIT("Audit"),

    /** AI-assisted processing. */
    AI_PROCESSING("AI Processing"),

    /** Agent-based automation. */
    AGENT_AUTOMATION("Agent Automation"),

    /** API-based intake. */
    API_INTAKE("API Intake"),

    /** Folder-watch intake. */
    FOLDER_INTAKE("Folder Intake");

    private final String displayName;

    Capability(String displayName) {
        this.displayName = Objects.requireNonNull(displayName);
    }

    public String displayName() {
        return displayName;
    }
}
