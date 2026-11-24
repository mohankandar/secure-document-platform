package com.securedoc.platform.api.routing;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.securedoc.platform.application.routing.service.DerivedDeliveryAttentionQuery;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.routing.DerivedDelivery;
import com.securedoc.platform.domain.routing.DerivedDeliveryStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0023 (B4, #222): the derived-delivery and attention responses carry no file name. */
class DerivedDeliveryResponseNoFileNameTest {
    private final ObjectMapper mapper = JsonMapper.builderWithJackson2Defaults().build();

    private DerivedDelivery deliveredWithLegacyFileShareReference() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        // Before task 0023 a file-share delivery stored the written file name as its destination reference.
        return DerivedDelivery.reconstitute(id, TenantId.generate(), PackageId.generate(), DocumentId.generate(), 1,
                UUID.randomUUID(), IntegrationConnectionId.generate(), 1, "Archive", null, null, List.of(1, 2),
                ArtifactId.generate(), "derived-" + id, "MARKER-borrower__Archive.pdf", DerivedDeliveryStatus.DELIVERED,
                1, null, false, "derived-" + id + "__MARKER-borrower__Archive.pdf", now, now, now);
    }

    @Test
    void theDeliveryResponseHasNoFileNameAndTrimsALegacyFileShareReference() throws Exception {
        DerivedDelivery d = deliveredWithLegacyFileShareReference();

        String json = mapper.writeValueAsString(DerivedDeliveryController.Response.of(d));

        assertFalse(json.contains("MARKER"), json);
        assertFalse(json.contains("\"fileName\""), json);
        assertTrue(json.contains("\"destinationReference\":\"derived-" + d.id() + "\""), json);
        assertTrue(json.contains("\"sourceDocumentId\":\"" + d.sourceDocumentId() + "\""), json);
    }

    @Test
    void theAttentionResponseHasNoFileName() throws Exception {
        DerivedDelivery d = deliveredWithLegacyFileShareReference();

        String json = mapper.writeValueAsString(DerivedDeliveryController.AttentionResponse.of(
                new DerivedDeliveryAttentionQuery.AttentionItem(d, null)));

        assertFalse(json.contains("MARKER"), json);
        assertFalse(json.contains("\"fileName\""), json);
    }

    @Test
    void anHttpReceiptIsReturnedAsItIs() {
        assertEquals("receipt__42", DerivedDeliveryController.safeReference("receipt__42"));
        assertNull(DerivedDeliveryController.safeReference(null));
    }
}
