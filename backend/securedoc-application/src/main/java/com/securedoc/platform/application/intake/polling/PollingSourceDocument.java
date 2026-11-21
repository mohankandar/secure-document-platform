package com.securedoc.platform.application.intake.polling;

/** One document a polling source offers. {@code sensitivityLevel} is the raw value it sent, or null (task 0021). */
public record PollingSourceDocument(String externalSourceDocumentId, String fileName, String sha256,
                                    String sensitivityLevel) {
    public PollingSourceDocument(String externalSourceDocumentId, String fileName, String sha256) {
        this(externalSourceDocumentId, fileName, sha256, null);
    }
}
