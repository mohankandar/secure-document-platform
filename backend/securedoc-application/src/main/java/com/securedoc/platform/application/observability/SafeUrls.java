package com.securedoc.platform.application.observability;

import java.net.URI;

/**
 * Task 0023 B8 (#328): the form of a configured URL that may be logged or put into a start-up error. User info
 * ({@code user:password@}), the query (which can carry a key) and the fragment are removed; only scheme, host, port
 * and path remain. A value that is not an absolute URL is never echoed.
 */
public final class SafeUrls {

    public static final String UNREADABLE = "<not a valid absolute URL>";

    private SafeUrls() {
    }

    public static String forLog(String value) {
        if (value == null || value.isBlank()) return UNREADABLE;
        try {
            URI uri = URI.create(value.trim());
            if (uri.getScheme() == null || uri.getHost() == null) return UNREADABLE;
            StringBuilder safe = new StringBuilder(uri.getScheme()).append("://").append(uri.getHost());
            if (uri.getPort() >= 0) safe.append(':').append(uri.getPort());
            if (uri.getRawPath() != null) safe.append(uri.getRawPath());
            return safe.toString();
        } catch (IllegalArgumentException notAUri) {
            return UNREADABLE;
        }
    }

    public static String forLog(URI uri) {
        return uri == null ? UNREADABLE : forLog(uri.toString());
    }
}
