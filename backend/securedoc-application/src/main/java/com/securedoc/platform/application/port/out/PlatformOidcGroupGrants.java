package com.securedoc.platform.application.port.out;

import java.net.URI;
import java.util.Map;
import java.util.Set;

/**
 * The customer's platform-wide grants under its ACTIVE login connection (task 0054): external group to stored role names,
 * exactly as stored. Server configuration only, loaded by setup from the role mapping file's {@code *} rows; never the
 * request. An empty map when no connection matches; null when they cannot be read (sign-in is then refused).
 */
@FunctionalInterface
public interface PlatformOidcGroupGrants {
    Map<String, Set<String>> activePlatformGrants(String providerId, URI issuer, String audience);
}
