package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.integration.IntegrationConnection;

/** Safe connectivity probe for a configured document store destination. Must never send a customer document. */
@FunctionalInterface
public interface DocumentStoreConnectionTestPort {
    void testConnection(IntegrationConnection connection);
}
