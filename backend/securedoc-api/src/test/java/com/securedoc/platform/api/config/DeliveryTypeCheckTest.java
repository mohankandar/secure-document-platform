package com.securedoc.platform.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0045: the delivery setting is one of the two known values, or the start stops in one sentence. */
class DeliveryTypeCheckTest {

    private static MockEnvironment with(String value) {
        MockEnvironment environment = new MockEnvironment();
        if (value != null) environment.setProperty(DeliveryTypeCheck.PROPERTY, value);
        return environment;
    }

    @Test
    void theTwoKnownValuesPass() {
        assertDoesNotThrow(() -> DeliveryTypeCheck.check(with("document-store")));
        assertDoesNotThrow(() -> DeliveryTypeCheck.check(with("local")));
        assertDoesNotThrow(() -> DeliveryTypeCheck.check(with("  Document-Store ")));
    }

    @Test
    void anythingElseStopsTheStart() {
        for (String value : new String[] {null, "", "store", "folder", "document_store"}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> DeliveryTypeCheck.check(with(value)));
            assertTrue(e.getMessage().startsWith("securedoc.delivery.type must be 'document-store' or 'local'"), e.getMessage());
        }
    }

    @Test
    void aValueThatIsNotPlainlyAWordIsNotRepeated() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DeliveryTypeCheck.check(with("x\nFAKE LOG LINE")));
        assertFalse(e.getMessage().contains("FAKE"), e.getMessage());
        assertTrue(e.getMessage().contains("not this value"), e.getMessage());
    }

    @Test
    void theApplicationDefaultIsKnown() throws Exception {
        // application.yml: type: ${SECUREDOC_DELIVERY_TYPE:document-store}
        String yml = new String(getClass().getResourceAsStream("/application.yml").readAllBytes());
        assertTrue(yml.contains("type: ${SECUREDOC_DELIVERY_TYPE:document-store}"), "default delivery type");
    }
}
