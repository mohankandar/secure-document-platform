package com.securedoc.platform.domain.integration;

/** Business purpose of a tenant integration connection. */
public enum IntegrationPurpose {
    INGESTION_SOURCE,
    DOCUMENT_STORE_DELIVERY,
    DERIVED_DOCUMENT_DELIVERY
}
