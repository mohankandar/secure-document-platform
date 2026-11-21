package com.securedoc.platform.application.exception;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0023 B7 (#286): only messages LexaVault's own code wrote are passed on to the person. */
class AuthoredMessagesTest {
    enum Mode { A }

    @Test
    void jdkAndLibraryMessagesAreReplaced() {
        assertEquals("fallback", AuthoredMessages.messageOr(
                assertThrows(IllegalArgumentException.class, () -> UUID.fromString("Jane Doe")), "fallback"));
        assertEquals("fallback", AuthoredMessages.messageOr(
                assertThrows(IllegalArgumentException.class, () -> Mode.valueOf("SECRET")), "fallback"));
        assertEquals("fallback", AuthoredMessages.messageOr(
                assertThrows(IllegalArgumentException.class, () -> Pattern.compile("(secret")), "fallback"));
    }

    @Test
    void aMessageWrittenInLexaVaultCodeIsKept() {
        IllegalArgumentException own = new IllegalArgumentException("minLength must be <= maxLength");
        assertEquals("minLength must be <= maxLength", AuthoredMessages.messageOr(own, "fallback"));
        assertEquals("fallback", AuthoredMessages.messageOr(new IllegalArgumentException(" "), "fallback"));
    }
}
