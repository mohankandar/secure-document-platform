package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.integration.IntegrationConnection;

/** Safe connectivity probe for a configured SDV destination. Must never send a customer document. */
@FunctionalInterface
public interface SdvConnectionTestPort {
    void testConnection(IntegrationConnection connection);
}
