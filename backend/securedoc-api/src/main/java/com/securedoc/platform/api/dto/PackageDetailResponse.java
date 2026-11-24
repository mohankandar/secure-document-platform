package com.securedoc.platform.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One case. {@code sensitivityLevel} is the case level as sent by the source system and
 * {@code effectiveSensitivityLevel} the case's effective level (task 0021). LexaVault never changes either.
 */
public record PackageDetailResponse(
        String packageId,
        String packageType,
        String status,
        List<BusinessIdentifierResponse> businessIdentifiers,
        Map<String,String> metadata,
        List<DocumentSummaryResponse> documents,
        Instant createdAt,
        Instant updatedAt,
        String sensitivityLevel,
        String effectiveSensitivityLevel,
        /* Task 0050: a digitally signed document of this case lost its signature by redaction. */
        boolean signatureRemoved
) {}
