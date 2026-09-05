package com.securedoc.platform.application.manual.result;

import com.securedoc.platform.domain.identity.DocumentId;

public record ManualFallbackDocumentSummary(DocumentId documentId, String fileName, int pageCount) {}
