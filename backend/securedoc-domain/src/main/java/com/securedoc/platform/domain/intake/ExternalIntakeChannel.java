package com.securedoc.platform.domain.intake;

/** Machine-to-machine intake channels that use an explicit source package/manifest contract. */
public enum ExternalIntakeChannel {
    PUSH_API,
    POLLING
}
