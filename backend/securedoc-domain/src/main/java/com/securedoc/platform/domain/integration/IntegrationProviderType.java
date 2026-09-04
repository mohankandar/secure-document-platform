package com.securedoc.platform.domain.integration;

/** Transport/provider family. Provider-specific behavior remains typed in the application/infrastructure layers. */
public enum IntegrationProviderType {
    PUSH_API,
    HTTP_API,
    FILE_SHARE,
    WATCHED_FOLDER,
    SCANNER
}
