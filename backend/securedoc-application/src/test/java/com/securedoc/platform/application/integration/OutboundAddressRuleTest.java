package com.securedoc.platform.application.integration;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.securedoc.platform.application.integration.OutboundAddressRule.Refusal.INTERNAL_ADDRESS;
import static com.securedoc.platform.application.integration.OutboundAddressRule.Refusal.INVALID;
import static com.securedoc.platform.application.integration.OutboundAddressRule.Refusal.NOT_HTTPS;
import static com.securedoc.platform.application.integration.OutboundAddressRule.Refusal.NOT_RESOLVED;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Task 0110, hardening: outbound addresses limited. */
class OutboundAddressRuleTest {

    private static final Map<String, String> DNS = Map.of(
            "files.example.com", "93.184.216.34",
            "intranet.corp.example", "10.1.2.3",
            "metadata.example", "169.254.169.254",
            "localhost", "127.0.0.1");

    private static final OutboundAddressRule.Resolver RESOLVER = host -> {
        String address = DNS.get(host);
        if (address == null) {
            if (host.matches("[0-9.]+") || host.contains(":")) return List.of(InetAddress.getByName(host));
            throw new UnknownHostException(host);
        }
        return List.of(InetAddress.getByName(address));
    };

    private static Optional<OutboundAddressRule.Refusal> check(String endpoint, String... allowed) {
        return OutboundAddressRule.check(endpoint, List.of(allowed), RESOLVER);
    }

    @Test
    void aPublicHttpsAddressIsAllowed() {
        assertEquals(Optional.empty(), check("https://files.example.com/api"));
    }

    @Test
    void plainHttpIsRefusedUnlessTheHostIsAllowListed() {
        assertEquals(Optional.of(NOT_HTTPS), check("http://files.example.com/api"));
        assertEquals(Optional.empty(), check("http://files.example.com/api", "FILES.example.com"));
    }

    @Test
    void internalAddressesAreRefused() {
        for (String endpoint : List.of("https://localhost/x", "https://127.0.0.1/x", "https://[::1]/x",
                "https://169.254.169.254/latest", "https://metadata.example/x", "https://10.0.0.5/x",
                "https://192.168.1.10/x", "https://172.16.0.1/x", "https://100.64.0.1/x", "https://0.0.0.0/x",
                "https://[fd00::1]/x", "https://[fe80::1]/x", "https://intranet.corp.example/x")) {
            assertEquals(Optional.of(INTERNAL_ADDRESS), check(endpoint), endpoint);
        }
        assertEquals(Optional.of(NOT_HTTPS), check("http://169.254.169.254/latest"));
        assertEquals(Optional.of(NOT_HTTPS), check("http://localhost:8080/x"));
    }

    @Test
    void anInternalHostIsAllowedByNameOnly() {
        assertEquals(Optional.empty(), check("http://intranet.corp.example/x", "intranet.corp.example"));
        assertEquals(Optional.of(INTERNAL_ADDRESS), check("https://10.1.2.3/x", "10.1.2.3"));
        assertEquals(Optional.of(NOT_HTTPS), check("http://169.254.169.254/x", "169.254.169.254"));
        assertEquals(java.util.Set.of("host.example"),
                OutboundAddressRule.normalized(List.of(" Host.Example. ", "10.0.0.1", "[::1]", "")));
    }

    @Test
    void aNameThatResolvesToAnInternalAddressIsRefused() {
        OutboundAddressRule.Resolver twoAnswers = host -> List.of(InetAddress.getByName("93.184.216.34"),
                InetAddress.getByName("127.0.0.1"));
        assertEquals(Optional.of(INTERNAL_ADDRESS), OutboundAddressRule.check("https://files.example.com/x", List.of(), twoAnswers));
    }

    @Test
    void unknownAndMalformedAddressesAreRefused() {
        assertEquals(Optional.of(NOT_RESOLVED), check("https://unknown.example/x"));
        assertEquals(Optional.of(INVALID), check("ftp://files.example.com/x"));
        assertEquals(Optional.of(INVALID), check("https://user@files.example.com/x"));
        assertEquals(Optional.of(INVALID), check("not a url"));
        assertEquals(Optional.of(INVALID), check(null));
        assertEquals(Optional.of(INVALID), check("https:///x"));
    }
}
