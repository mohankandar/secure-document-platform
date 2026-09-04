package com.securedoc.platform.application.intake.polling;

public record PollingSourceDocument(String externalSourceDocumentId, String fileName, String sha256) {}
