package com.securedoc.platform.domain.intake;

/** Lifecycle for an external intake manifest before the normal LexaVault package pipeline takes over. */
public enum ExternalIntakeStatus {
    AWAITING_DOCUMENTS,
    ACCEPTED_FOR_PROCESSING,
    INCOMPLETE_EXPIRED
}
