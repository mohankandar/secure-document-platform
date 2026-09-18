package com.securedoc.platform.domain.integration;

import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationConnectionTest {
    private final TenantId tenantId = TenantId.generate();
    private final Instant now = Instant.parse("2026-09-02T20:00:00Z");

    @Test
    void sdvDeliveryRequiresOutboundHttpAndSubmissionAcknowledgement() {
        var connection = IntegrationConnection.create(tenantId, "Primary SDV", null,
                IntegrationPurpose.SDV_DELIVERY, IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API,
                IntegrationAuthenticationType.NONE, null, "https://sdv.example.test", null, null, 60, 2, 24, 100, 262144000L, 2000,
                IntegrationAcknowledgementMode.SDV_SUBMISSION_ACK, false, now);
        assertEquals(IntegrationPurpose.SDV_DELIVERY, connection.purpose());
        assertFalse(connection.enabled());
        assertEquals(IntegrationHealthStatus.DISABLED, connection.healthStatus());

        assertThrows(IllegalArgumentException.class, () -> IntegrationConnection.create(tenantId, "Bad SDV", null,
                IntegrationPurpose.SDV_DELIVERY, IntegrationDirection.OUTBOUND, IntegrationProviderType.FILE_SHARE,
                IntegrationAuthenticationType.NONE, null, null, "/share", null, 60, 2, 24, 100, 262144000L, 2000,
                IntegrationAcknowledgementMode.DURABLE_FILE_WRITE, false, now));
    }

    @Test
    void fileShareIsOnlyDerivedDeliveryAndUsesDurableWriteAcknowledgement() {
        var connection = IntegrationConnection.create(tenantId, "Atlanta Share", null,
                IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY, IntegrationDirection.OUTBOUND,
                IntegrationProviderType.FILE_SHARE, IntegrationAuthenticationType.NONE, null, null, "/secure/title", null,
                60, 2, 24, 100, 262144000L, 2000, IntegrationAcknowledgementMode.DURABLE_FILE_WRITE, true, now);
        assertTrue(connection.enabled());
        assertEquals(IntegrationHealthStatus.UNKNOWN, connection.healthStatus());
    }

    @Test
    void authenticatedEnabledConnectionRequiresSecretReferenceButDisabledDraftDoesNot() {
        assertThrows(IllegalArgumentException.class, () -> IntegrationConnection.create(tenantId, "Title API", null,
                IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY, IntegrationDirection.OUTBOUND,
                IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.JWT, null, "https://title.example.test", null,
                null, 60, 2, 24, 100, 262144000L, 2000, IntegrationAcknowledgementMode.DESTINATION_RECEIPT, true, now));

        var draft = IntegrationConnection.create(tenantId, "Title API", null,
                IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY, IntegrationDirection.OUTBOUND,
                IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.JWT, null, "https://title.example.test", null,
                null, 60, 2, 24, 100, 262144000L, 2000, IntegrationAcknowledgementMode.DESTINATION_RECEIPT, false, now);
        assertFalse(draft.secretConfigured());
        assertThrows(IllegalArgumentException.class, () -> draft.setEnabled(true, now.plusSeconds(1)));
    }

    @Test
    void enabledPushSourceRequiresClientPrincipalAndBoundedIncompleteWindow() {
        var connection = IntegrationConnection.create(tenantId, "LOS API", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.INBOUND, IntegrationProviderType.PUSH_API,
                IntegrationAuthenticationType.NONE, "los-client", null, null, null, 60, 2, 24, 100, 262144000L, 2000,
                IntegrationAcknowledgementMode.NONE, true, now);
        assertEquals("los-client", connection.clientPrincipal());
        assertEquals(24, connection.incompleteWindowHours());
        assertDoesNotThrow(() -> IntegrationConnection.create(tenantId, "LOS JWT", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.INBOUND, IntegrationProviderType.PUSH_API,
                IntegrationAuthenticationType.JWT, "los-jwt-client", null, null, null, 60, 2, 24,
                100, 262144000L, 2000, IntegrationAcknowledgementMode.NONE, true, now));
        assertThrows(IllegalArgumentException.class, () -> IntegrationConnection.create(tenantId, "Bad source", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.INBOUND, IntegrationProviderType.PUSH_API,
                IntegrationAuthenticationType.NONE, null, null, null, null, 60, 2, 24, 100, 262144000L, 2000,
                IntegrationAcknowledgementMode.NONE, true, now));
        assertThrows(IllegalArgumentException.class, () -> IntegrationConnection.create(tenantId, "Forever source", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.INBOUND, IntegrationProviderType.PUSH_API,
                IntegrationAuthenticationType.NONE, "los-client", null, null, null, 60, 2, 169, 100, 262144000L, 2000,
                IntegrationAcknowledgementMode.NONE, true, now));
    }

    @Test
    void scannerSourceRequiresOneConsolidatedPdfAndAuthenticatedClientBinding() {
        var scanner = IntegrationConnection.create(tenantId, "Las Vegas Scanner", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.INBOUND, IntegrationProviderType.SCANNER,
                IntegrationAuthenticationType.NONE, "scanner-las-01", null, null, null, 60, 2, 24, 1,
                IntegrationConnection.MAX_PDF_SIZE_BYTES, IntegrationConnection.MAX_PDF_PAGES,
                IntegrationAcknowledgementMode.NONE, true, now);
        assertEquals(1, scanner.maxDocumentsPerIntake());
        assertEquals("scanner-las-01", scanner.clientPrincipal());

        var scannerDefaults = IntegrationConnection.create(tenantId, "Default scanner limits", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.INBOUND, IntegrationProviderType.SCANNER,
                IntegrationAuthenticationType.NONE, "scanner-las-default", null, null, null, 60, 2, 24, null, null, null,
                IntegrationAcknowledgementMode.NONE, true, now);
        assertEquals(1, scannerDefaults.maxDocumentsPerIntake());
        assertEquals(IntegrationConnection.MAX_PDF_SIZE_BYTES, scannerDefaults.maxPdfSizeBytes());
        assertEquals(IntegrationConnection.MAX_PDF_PAGES, scannerDefaults.maxPdfPages());

        assertThrows(IllegalArgumentException.class, () -> IntegrationConnection.create(tenantId, "Bad scanner", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.INBOUND, IntegrationProviderType.SCANNER,
                IntegrationAuthenticationType.NONE, "scanner-las-02", null, null, null, 60, 2, 24, 2,
                IntegrationConnection.MAX_PDF_SIZE_BYTES, IntegrationConnection.MAX_PDF_PAGES,
                IntegrationAcknowledgementMode.NONE, true, now));
        assertThrows(IllegalArgumentException.class, () -> IntegrationConnection.create(tenantId, "Unbound scanner", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.INBOUND, IntegrationProviderType.SCANNER,
                IntegrationAuthenticationType.NONE, null, null, null, null, 60, 2, 24, 1,
                IntegrationConnection.MAX_PDF_SIZE_BYTES, IntegrationConnection.MAX_PDF_PAGES,
                IntegrationAcknowledgementMode.NONE, true, now));
        assertThrows(IllegalArgumentException.class, () -> IntegrationConnection.create(tenantId, "Bad scanner ack", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.INBOUND, IntegrationProviderType.SCANNER,
                IntegrationAuthenticationType.NONE, "scanner-las-03", null, null, null, 60, 2, 24, 1,
                IntegrationConnection.MAX_PDF_SIZE_BYTES, IntegrationConnection.MAX_PDF_PAGES,
                IntegrationAcknowledgementMode.HTTP_SUCCESS, true, now));
    }

    @Test
    void configurationUpdateIncrementsVersionAndCanPreserveSecretReference() {
        var connection = IntegrationConnection.create(tenantId, "Title API", null,
                IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY, IntegrationDirection.OUTBOUND,
                IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.JWT, null, "https://one.example.test", null,
                "vault://tenant/title", 60, 2, 24, 100, 262144000L, 2000, IntegrationAcknowledgementMode.DESTINATION_RECEIPT, true, now);
        var updated = connection.updateConfiguration("Title API", "Updated", IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY,
                IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.JWT,
                null, "https://two.example.test", null, null, true, 90, 3, 24, 100, 262144000L, 2000,
                null, null, null, null, null, IntegrationAcknowledgementMode.DESTINATION_RECEIPT, now.plusSeconds(10));
        assertEquals(2, updated.configurationVersion());
        assertEquals("vault://tenant/title", updated.secretReference());
        assertEquals(IntegrationHealthStatus.UNKNOWN, updated.healthStatus());
    }

    @Test
    void nonPushConnectionCannotCarryInboundClientPrincipalBinding() {
        assertThrows(IllegalArgumentException.class, () -> IntegrationConnection.create(
                TenantId.generate(), "SDV", null, IntegrationPurpose.SDV_DELIVERY, IntegrationDirection.OUTBOUND,
                IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.NONE, "should-not-be-here",
                "https://sdv.example", null, null, 60, 2, 24, 100, 1024L, 100,
                IntegrationAcknowledgementMode.SDV_SUBMISSION_ACK, true, Instant.now()));
    }
    @Test
    void pollingSourceRequiresTypedScheduleAndSourcePickupAcknowledgement() {
        var polling = IntegrationConnection.create(tenantId, "Nightly LOS", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API,
                IntegrationAuthenticationType.NONE, null, "https://source.example.test", null, null,
                60, 2, 24, 100, 262144000L, 2000,
                IntegrationPollingScheduleType.DAILY, null, java.time.LocalTime.of(1, 0), "America/New_York", 100,
                IntegrationAcknowledgementMode.SOURCE_PICKUP_ACK, true, now);
        assertTrue(polling.pollingSource());
        assertEquals(IntegrationPollingScheduleType.DAILY, polling.pollingScheduleType());
        assertEquals("America/New_York", polling.pollingTimeZone());

        assertThrows(IllegalArgumentException.class, () -> IntegrationConnection.create(tenantId, "Bad poll", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API,
                IntegrationAuthenticationType.NONE, null, "https://source.example.test", null, null,
                60, 2, 24, 100, 262144000L, 2000,
                IntegrationPollingScheduleType.INTERVAL, 60, null, null, 100,
                IntegrationAcknowledgementMode.HTTP_SUCCESS, true, now));
    }

    @Test
    void pollingSourceRequiresOutboundHttpPickupAckAndValidSchedule() {
        var connection = IntegrationConnection.create(tenantId, "Nightly Source", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API,
                IntegrationAuthenticationType.NONE, null, "https://source.example.test", null, null,
                60, 2, 24, 100, 262144000L, 2000,
                IntegrationPollingScheduleType.DAILY, null, java.time.LocalTime.of(1, 0), "America/New_York", 100,
                IntegrationAcknowledgementMode.SOURCE_PICKUP_ACK, true, now);
        assertTrue(connection.pollingSource());
        assertEquals(IntegrationPollingScheduleType.DAILY, connection.pollingScheduleType());

        assertThrows(IllegalArgumentException.class, () -> IntegrationConnection.create(tenantId, "Bad Polling", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API,
                IntegrationAuthenticationType.NONE, null, "https://source.example.test", null, null,
                60, 2, 24, 100, 262144000L, 2000,
                IntegrationPollingScheduleType.DAILY, null, java.time.LocalTime.of(1, 0), "America/New_York", 100,
                IntegrationAcknowledgementMode.NONE, true, now));
    }

}
