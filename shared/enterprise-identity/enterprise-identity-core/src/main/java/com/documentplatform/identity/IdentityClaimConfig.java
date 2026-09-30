package com.documentplatform.identity;

/** Claim names are deployment configuration, never product role names. */
public record IdentityClaimConfig(
        String usernameClaim,
        String emailClaim,
        String displayNameClaim,
        String groupsClaim) {
    public IdentityClaimConfig {
        usernameClaim = defaulted(usernameClaim, "preferred_username");
        emailClaim = defaulted(emailClaim, "email");
        displayNameClaim = defaulted(displayNameClaim, "name");
        groupsClaim = defaulted(groupsClaim, "groups");
    }
    private static String defaulted(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
