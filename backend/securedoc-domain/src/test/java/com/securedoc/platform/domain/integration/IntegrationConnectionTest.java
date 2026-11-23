package com.securedoc.platform.domain.integration;

import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationConnectionTest {
    private final TenantId tenantId = TenantId.generate();
    private final Instant now = Instant.parse("2026-09-02T20:00:00Z");

    @Test
    void documentStoreDeliveryRequiresOutboundHttpAndSubmissionAcknowledgement() {
        var connection = IntegrationConnection.create(tenantId, "Primary store", null,
                IntegrationPurpose.DOCUMENT_STORE_DELIVERY, IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API,
                IntegrationAuthenticationType.NONE, null, "https://store.example.test", null, null, 60, 2, 24, 50, 262144000L, 500,
                IntegrationAcknowledgementMode.SUBMISSION_ACK, false, now);
        assertEquals(IntegrationPurpose.DOCUMENT_STORE_DELIVERY, connection.purpose());
        assertFalse(connection.enabled());
        assertEquals(IntegrationHealthStatus.DISABLED, connection.healthStatus());

        assertThrows(IllegalArgumentException.class, () -> IntegrationConnection.create(tenantId, "Bad store", null,
                IntegrationPurpose.DOCUMENT_STORE_DELIVERY, IntegrationDirection.OUTBOUND, IntegrationProviderType.FILE_SHARE,
                IntegrationAuthenticationType.NONE, null, null, "/share", null, 60, 2, 24, 50, 262144000L, 500,
                IntegrationAcknowledgementMode.DURABLE_FILE_WRITE, false, now));
    }

    @Test
    void fileShareIsOnlyDerivedDeliveryAndUsesDurableWriteAcknowledgement() {
        var connection = IntegrationConnection.create(tenantId, "Atlanta Share", null,
                IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY, IntegrationDirection.OUTBOUND,
                IntegrationProviderType.FILE_SHARE, IntegrationAuthenticationType.NONE, null, null, "/secure/title", null,
                60, 2, 24, 50, 262144000L, 500, IntegrationAcknowledgementMode.DURABLE_FILE_WRITE, true, now);
        assertTrue(connection.enabled());
        assertEquals(IntegrationHealthStatus.UNKNOWN, connection.healthStatus());
    }

    @Test
    void authenticatedEnabledConnectionRequiresSecretReferenceButDisabledDraftDoesNot() {
        assertThrows(IllegalArgumentException.class, () -> IntegrationConnection.create(tenantId, "Title API", null,
                IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY, IntegrationDirection.OUTBOUND,
                IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.JWT, null, "https://title.example.test", null,
                null, 60, 2, 24, 50, 262144000L, 500, IntegrationAcknowledgementMode.DESTINATION_RECEIPT, true, now));

        var draft = IntegrationConnection.create(tenantId, "Title API", null,
                IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY, IntegrationDirection.OUTBOUND,
                IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.JWT, null, "https://title.example.test", null,
                null, 60, 2, 24, 50, 262144000L, 500, IntegrationAcknowledgementMode.DESTINATION_RECEIPT, false, now);
        assertFalse(draft.secretConfigured());
        assertThrows(IllegalArgumentException.class, () -> draft.setEnabled(true, now.plusSeconds(1)));
    }

    @Test
    void enabledPushSourceRequiresClientPrincipalAndBoundedIncompleteWindow() {
        var connection = IntegrationConnection.create(tenantId, "LOS API", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.INBOUND, IntegrationProviderType.PUSH_API,
                IntegrationAuthenticationType.NONE, "los-client", null, null, null, 60, 2, 24, 50, 262144000L, 500,
                IntegrationAcknowledgementMode.NONE, true, now);
        assertEquals("los-client", connection.clientPrincipal());
        assertEquals(24, connection.incompleteWindowHours());
        assertDoesNotThrow(() -> IntegrationConnection.create(tenantId, "LOS JWT", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.INBOUND, IntegrationProviderType.PUSH_API,
                IntegrationAuthenticationType.JWT, "los-jwt-client", null, null, null, 60, 2, 24,
                50, 262144000L, 500, IntegrationAcknowledgementMode.NONE, true, now));
        assertThrows(IllegalArgumentException.class, () -> IntegrationConnection.create(tenantId, "Bad source", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.INBOUND, IntegrationProviderType.PUSH_API,
                IntegrationAuthenticationType.NONE, null, null, null, null, 60, 2, 24, 50, 262144000L, 500,
                IntegrationAcknowledgementMode.NONE, true, now));
        assertThrows(IllegalArgumentException.class, () -> IntegrationConnection.create(tenantId, "Forever source", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.INBOUND, IntegrationProviderType.PUSH_API,
                IntegrationAuthenticationType.NONE, "los-client", null, null, null, 60, 2, 169, 50, 262144000L, 500,
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
                "vault://tenant/title", 60, 2, 24, 50, 262144000L, 500, IntegrationAcknowledgementMode.DESTINATION_RECEIPT, true, now);
        var updated = connection.updateConfiguration("Title API", "Updated", IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY,
                IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.JWT,
                null, "https://two.example.test", null, null, true, 90, 3, 24, 50, 262144000L, 500,
                null, null, null, null, null, IntegrationAcknowledgementMode.DESTINATION_RECEIPT, now.plusSeconds(10));
        assertEquals(2, updated.configurationVersion());
        assertEquals("vault://tenant/title", updated.secretReference());
        assertEquals(IntegrationHealthStatus.UNKNOWN, updated.healthStatus());
    }

    @Test
    void nonPushConnectionCannotCarryInboundClientPrincipalBinding() {
        assertThrows(IllegalArgumentException.class, () -> IntegrationConnection.create(
                TenantId.generate(), "Store", null, IntegrationPurpose.DOCUMENT_STORE_DELIVERY, IntegrationDirection.OUTBOUND,
                IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.NONE, "should-not-be-here",
                "https://store.example", null, null, 60, 2, 24, 50, 1024L, 100,
                IntegrationAcknowledgementMode.SUBMISSION_ACK, true, Instant.now()));
    }
    @Test
    void pollingSourceRequiresTypedScheduleAndSourcePickupAcknowledgement() {
        var polling = IntegrationConnection.create(tenantId, "Nightly LOS", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API,
                IntegrationAuthenticationType.NONE, null, "https://source.example.test", null, null,
                60, 2, 24, 50, 262144000L, 500,
                IntegrationPollingScheduleType.DAILY, null, java.time.LocalTime.of(1, 0), "America/New_York", 100,
                IntegrationAcknowledgementMode.SOURCE_PICKUP_ACK, true, now);
        assertTrue(polling.pollingSource());
        assertEquals(IntegrationPollingScheduleType.DAILY, polling.pollingScheduleType());
        assertEquals("America/New_York", polling.pollingTimeZone());

        assertThrows(IllegalArgumentException.class, () -> IntegrationConnection.create(tenantId, "Bad poll", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API,
                IntegrationAuthenticationType.NONE, null, "https://source.example.test", null, null,
                60, 2, 24, 50, 262144000L, 500,
                IntegrationPollingScheduleType.INTERVAL, 60, null, null, 100,
                IntegrationAcknowledgementMode.HTTP_SUCCESS, true, now));
    }

    @Test
    void pollingSourceRequiresOutboundHttpPickupAckAndValidSchedule() {
        var connection = IntegrationConnection.create(tenantId, "Nightly Source", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API,
                IntegrationAuthenticationType.NONE, null, "https://source.example.test", null, null,
                60, 2, 24, 50, 262144000L, 500,
                IntegrationPollingScheduleType.DAILY, null, java.time.LocalTime.of(1, 0), "America/New_York", 100,
                IntegrationAcknowledgementMode.SOURCE_PICKUP_ACK, true, now);
        assertTrue(connection.pollingSource());
        assertEquals(IntegrationPollingScheduleType.DAILY, connection.pollingScheduleType());

        assertThrows(IllegalArgumentException.class, () -> IntegrationConnection.create(tenantId, "Bad Polling", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API,
                IntegrationAuthenticationType.NONE, null, "https://source.example.test", null, null,
                60, 2, 24, 50, 262144000L, 500,
                IntegrationPollingScheduleType.DAILY, null, java.time.LocalTime.of(1, 0), "America/New_York", 100,
                IntegrationAcknowledgementMode.NONE, true, now));
    }

    /* Task 0039: a source can't promise more than a case holds (CaseLimits: 50 files, 500 pages per file). */

    private IntegrationConnection push(Integer files, Integer pages) {
        return IntegrationConnection.create(tenantId, "Push " + files + "/" + pages, null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.INBOUND, IntegrationProviderType.PUSH_API,
                IntegrationAuthenticationType.NONE, "los-client", null, null, null, 60, 2, 24, files, 262144000L, pages,
                IntegrationAcknowledgementMode.NONE, true, now);
    }

    @Test
    void theSourceLimitsAreTheCaseLimitsAndAreTheDefaults() {
        assertEquals(com.securedoc.platform.domain.document.CaseLimits.MAX_FILES_PER_CASE, IntegrationConnection.MAX_DOCUMENTS_PER_INTAKE);
        assertEquals(com.securedoc.platform.domain.document.CaseLimits.MAX_PAGES_PER_FILE, IntegrationConnection.MAX_PDF_PAGES);
        IntegrationConnection defaults = push(null, null);
        assertEquals(50, defaults.maxDocumentsPerIntake());
        assertEquals(500, defaults.maxPdfPages());
        IntegrationConnection atTheLimit = push(50, 500);
        assertEquals(50, atTheLimit.maxDocumentsPerIntake());
        assertEquals(500, atTheLimit.maxPdfPages());
    }

    @Test
    void savingMoreThanACaseHoldsIsRefusedWithAMessageThatSaysWhy() {
        IllegalArgumentException files = assertThrows(IllegalArgumentException.class, () -> push(51, 500));
        assertEquals("Maximum documents per intake must be between 1 and 50: a case holds up to 50 files", files.getMessage());
        IllegalArgumentException pages = assertThrows(IllegalArgumentException.class, () -> push(50, 501));
        assertEquals("Maximum PDF pages must be between 1 and 500: a case takes files of up to 500 pages", pages.getMessage());
        assertThrows(IllegalArgumentException.class, () -> push(1000, 2000));

        IntegrationConnection saved = push(10, 100);
        assertThrows(IllegalArgumentException.class, () -> saved.updateConfiguration(saved.name(), null, saved.purpose(),
                saved.direction(), saved.providerType(), saved.authenticationType(), saved.clientPrincipal(), null, null,
                null, true, null, null, null, 51, null, null, null, null, null, null, null, null, now));
        assertThrows(IllegalArgumentException.class, () -> saved.updateConfiguration(saved.name(), null, saved.purpose(),
                saved.direction(), saved.providerType(), saved.authenticationType(), saved.clientPrincipal(), null, null,
                null, true, null, null, null, null, null, 2000, null, null, null, null, null, null, now));
    }

    @Test
    void aSourceStoredBeforeTheCapWithHigherValuesStillLoadsAndReadsAsTheCap() {
        IntegrationConnection c = push(10, 100);
        // As the database adapter builds it from a row saved before task 0039 (1,000 files and 2,000 pages).
        IntegrationConnection stored = new IntegrationConnection(c.id(), c.tenantId(), c.name(), c.description(), c.purpose(),
                c.direction(), c.providerType(), c.authenticationType(), c.clientPrincipal(), c.endpoint(), c.fileShareRoot(),
                c.secretReference(), c.timeoutSeconds(), c.retryMaxAttempts(), c.incompleteWindowHours(), 1000,
                c.maxPdfSizeBytes(), 2000, c.pollingScheduleType(), c.pollingIntervalMinutes(), c.pollingLocalTime(),
                c.pollingTimeZone(), c.pollingBatchLimit(), c.acknowledgementMode(), c.enabled(), c.configurationVersion(),
                c.healthStatus(), c.lastSuccessAt(), c.lastFailureAt(), c.lastFailureCategory(), c.createdAt(), c.updatedAt());
        assertEquals(50, stored.maxDocumentsPerIntake());
        assertEquals(500, stored.maxPdfPages());
        // Saving it again without touching the limits keeps the capped values and is not refused.
        IntegrationConnection resaved = stored.updateConfiguration(stored.name(), null, stored.purpose(), stored.direction(),
                stored.providerType(), stored.authenticationType(), stored.clientPrincipal(), null, null, null, true,
                null, null, null, null, null, null, null, null, null, null, null, null, now);
        assertEquals(50, resaved.maxDocumentsPerIntake());
        assertEquals(500, resaved.maxPdfPages());
        // Switching it on or off is not refused either.
        assertEquals(500, stored.setEnabled(false, now).maxPdfPages());
        // Values below 1 are still refused.
        assertThrows(IllegalArgumentException.class, () -> push(0, 500));
        assertThrows(IllegalArgumentException.class, () -> push(50, 0));
    }

}
