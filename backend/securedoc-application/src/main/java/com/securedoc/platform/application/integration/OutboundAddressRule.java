package com.securedoc.platform.application.integration;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Task 0110 (hardening: outbound addresses limited). A source or destination address must be https and reach only
 * public addresses. A host the IT Administrator names in the connection's allow-list may also be internal or plain http
 * (on-premises systems); it is allowed by name only, never by a written address. Meant to be checked on save and again
 * at connect time, after the name is resolved.
 */
public final class OutboundAddressRule {

    public enum Refusal { INVALID, NOT_HTTPS, INTERNAL_ADDRESS, NOT_RESOLVED }

    /** Resolves a host to its addresses; the default asks the system resolver each time. */
    @FunctionalInterface
    public interface Resolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    public static final Resolver SYSTEM = host -> List.of(InetAddress.getAllByName(host));

    private OutboundAddressRule() {
    }

    public static Optional<Refusal> check(String endpoint, Collection<String> allowedHosts) {
        return check(endpoint, allowedHosts, SYSTEM);
    }

    public static Optional<Refusal> check(String endpoint, Collection<String> allowedHosts, Resolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        URI uri;
        try {
            uri = URI.create(endpoint == null ? "" : endpoint.trim());
        } catch (IllegalArgumentException e) {
            return Optional.of(Refusal.INVALID);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || host.isBlank() || uri.getUserInfo() != null
                || !("https".equals(scheme) || "http".equals(scheme))) {
            return Optional.of(Refusal.INVALID);
        }
        host = host.replace("[", "").replace("]", "").toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        boolean allowListed = !isAddressLiteral(host) && normalized(allowedHosts).contains(host);
        if (allowListed) return Optional.empty();
        if (!"https".equals(scheme)) return Optional.of(Refusal.NOT_HTTPS);
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            return Optional.of(Refusal.NOT_RESOLVED);
        }
        if (addresses == null || addresses.isEmpty()) return Optional.of(Refusal.NOT_RESOLVED);
        for (InetAddress address : addresses) {
            if (internal(address)) return Optional.of(Refusal.INTERNAL_ADDRESS);
        }
        return Optional.empty();
    }

    /** Names in an allow-list, trimmed and lower case; written addresses are left out (allowed by name only). */
    public static Set<String> normalized(Collection<String> allowedHosts) {
        if (allowedHosts == null) return Set.of();
        return allowedHosts.stream().filter(Objects::nonNull).map(h -> h.trim().toLowerCase(Locale.ROOT))
                .map(h -> h.endsWith(".") ? h.substring(0, h.length() - 1) : h)
                .filter(h -> !h.isEmpty() && !isAddressLiteral(h)).collect(Collectors.toUnmodifiableSet());
    }

    /** Loopback, link-local (cloud metadata included), private, shared, unique-local, unspecified or multicast. */
    static boolean internal(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 0 || (first == 100 && second >= 64 && second <= 127) // shared (carrier) range
                    || (first == 192 && second == 0 && (b[2] & 0xff) == 0) || first >= 240;
        }
        return (b[0] & 0xfe) == 0xfc; // IPv6 unique-local
    }

    private static boolean isAddressLiteral(String host) {
        return host.contains(":") || host.matches("[0-9.]+") || host.matches("(?i)0x[0-9a-f.x]+");
    }
}
