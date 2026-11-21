package com.securedoc.platform.application.intake.external;

/**
 * A warning about an intake that was still taken in (task 0021). {@code code} is a stable LexaVault code such as
 * LV-INT-005; {@code scope} is CASE or DOCUMENT; {@code externalSourceDocumentId} names the source's own document for
 * a DOCUMENT warning. The value the source sent is never echoed.
 */
public record IntakeWarning(String code, String message, String scope, String externalSourceDocumentId) {
}
