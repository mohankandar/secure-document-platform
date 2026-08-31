package com.securedoc.platform.application.intake.watched;

import com.securedoc.platform.application.intake.model.IntakeRejectionCode;
import java.util.*;

public record WatchedFolderCandidate(
        String sourceReference,
        boolean triggerPresent,
        boolean manifestValid,
        IntakeRejectionCode rejectionCode,
        Map<String,String> businessIdentifiers,
        Map<String,String> metadata,
        List<WatchedFolderDocument> documents
) {
    public WatchedFolderCandidate {
        Objects.requireNonNull(sourceReference);
        businessIdentifiers = businessIdentifiers == null ? Map.of() : Map.copyOf(businessIdentifiers);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        documents = documents == null ? List.of() : List.copyOf(documents);
    }
}
