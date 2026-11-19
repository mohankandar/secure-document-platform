package com.securedoc.platform.application.integration;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Refuses addresses that a connection test must never call: cloud metadata services. The host is checked by name and
 * by every address it resolves to. A host that does not resolve is refused too (fail closed).
 *
 * <p>This runs once, before the request. A DNS answer that changes between this check and the request is not covered.
 */
public final class OutboundAddressGuard {

    /** Why an address was refused. Safe to return to the caller. */
    public enum Verdict { ALLOWED, METADATA_ADDRESS, UNRESOLVED, INVALID }

    @FunctionalInterface
    public interface Resolver { InetAddress[] resolve(String host) throws UnknownHostException; }

    private static final Set<String> METADATA_HOSTS = Set.of(
            "metadata", "metadata.google.internal", "metadata.goog", "instance-data", "instance-data.ec2.internal",
            "metadata.azure.com", "metadata.tencentyun.com");
    private static final Set<String> METADATA_ADDRESSES = Set.of(
            "100.100.100.200",   // Alibaba Cloud
            "168.63.129.16");    // Azure platform (WireServer)

    private final Resolver resolver;

    public OutboundAddressGuard() { this(InetAddress::getAllByName); }

    public OutboundAddressGuard(Resolver resolver) { this.resolver = Objects.requireNonNull(resolver); }

    public Verdict check(String endpoint) {
        String host;
        try {
            host = URI.create(Objects.requireNonNull(endpoint).trim()).getHost();
        } catch (RuntimeException e) {
            return Verdict.INVALID;
        }
        if (host == null || host.isBlank()) return Verdict.INVALID;
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (METADATA_HOSTS.contains(host)) return Verdict.METADATA_ADDRESS;
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException | RuntimeException e) {
            return Verdict.UNRESOLVED;
        }
        if (addresses == null || addresses.length == 0) return Verdict.UNRESOLVED;
        for (InetAddress address : addresses) {
            if (isMetadata(address)) return Verdict.METADATA_ADDRESS;
        }
        return Verdict.ALLOWED;
    }

    private static boolean isMetadata(InetAddress address) {
        if (address.isLinkLocalAddress()) return true;          // 169.254.0.0/16 (AWS, GCP, Azure, OCI …), fe80::/10
        if (METADATA_ADDRESSES.contains(address.getHostAddress())) return true;
        if (address instanceof Inet6Address v6) {
            byte[] b = v6.getAddress();
            // fd00:ec2::254 (AWS IPv6 metadata)
            boolean awsV6 = (b[0] & 0xff) == 0xfd && b[1] == 0 && (b[2] & 0xff) == 0x0e && (b[3] & 0xff) == 0xc2
                    && b[15] == (byte) 0x54 && (b[14] & 0xff) == 0x02;
            if (awsV6) {
                for (int i = 4; i < 14; i++) if (b[i] != 0) return false;
                return true;
            }
        }
        return false;
    }
}
