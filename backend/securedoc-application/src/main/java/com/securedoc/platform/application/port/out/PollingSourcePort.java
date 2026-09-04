package com.securedoc.platform.application.port.out;

import com.securedoc.platform.application.intake.polling.PollingSourceDocument;
import com.securedoc.platform.application.intake.polling.PollingSourcePackage;
import com.securedoc.platform.application.intake.polling.RetrievedPollingDocument;
import com.securedoc.platform.domain.integration.IntegrationConnection;

import java.util.List;

/** Connector abstraction for LexaVault-owned pull/poll ingestion. */
public interface PollingSourcePort {
    List<PollingSourcePackage> discoverAvailable(IntegrationConnection connection, int limit);
    RetrievedPollingDocument retrieveDocument(IntegrationConnection connection, PollingSourcePackage sourcePackage,
                                               PollingSourceDocument document);
    String acknowledgeReceived(IntegrationConnection connection, PollingSourcePackage sourcePackage, int attemptNumber);
}
