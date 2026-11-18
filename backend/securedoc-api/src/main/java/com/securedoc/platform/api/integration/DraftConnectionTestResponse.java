package com.securedoc.platform.api.integration;

/** Answer of a test before saving: whether it works and, if not, a fixed reason code. Nothing else. */
public record DraftConnectionTestResponse(boolean works, String reasonCode) {
}
