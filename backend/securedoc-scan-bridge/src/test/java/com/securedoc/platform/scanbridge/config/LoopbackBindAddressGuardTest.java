package com.securedoc.platform.scanbridge.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoopbackBindAddressGuardTest {

    @Test
    void acceptsIpv4AndIpv6LoopbackAddresses() {
        assertTrue(LoopbackBindAddressGuard.isLoopbackOnly("127.0.0.1"));
        assertTrue(LoopbackBindAddressGuard.isLoopbackOnly("::1"));
    }

    @Test
    void acceptsLocalhostOnlyWhenEveryResolvedAddressIsLoopback() {
        assertTrue(LoopbackBindAddressGuard.isLoopbackOnly("localhost"));
    }

    @Test
    void rejectsWildcardAndNonLoopbackAddresses() {
        assertFalse(LoopbackBindAddressGuard.isLoopbackOnly("0.0.0.0"));
        assertFalse(LoopbackBindAddressGuard.isLoopbackOnly("::"));
        assertFalse(LoopbackBindAddressGuard.isLoopbackOnly("192.0.2.10"));
    }

    @Test
    void rejectsBlankAndUnresolvableAddresses() {
        assertFalse(LoopbackBindAddressGuard.isLoopbackOnly(""));
        assertFalse(LoopbackBindAddressGuard.isLoopbackOnly("scanner-bridge.invalid"));
    }

    @Test
    void startupValidationFailsClosedForNonLoopbackBinding() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new LoopbackBindAddressGuard("0.0.0.0").validate());
        assertTrue(error.getMessage().contains("loopback"));
    }
}
