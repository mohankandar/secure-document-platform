package com.securedoc.platform.infrastructure.delivery;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.securedoc.platform.application.error.LvErrorCodes;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.port.out.DeliveryPort;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.integration.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Task 0028: Retry-After values, and the local folder's health check. The document store's are in ProfileDestinationDeliveryTest. */
class ExportDestinationAdapterFollowupsTest {
    private final TenantId tenant = TenantId.generate();

    @Test
    void retryAfterIsSecondsOrAnHttpDateCappedAtOneHour() {
        Instant now = Instant.parse("2026-11-21T10:00:00Z");
        assertEquals(Duration.ofSeconds(30), RetryAfter.parse("30", now));
        String inTwoMinutes = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(120).atOffset(ZoneOffset.UTC));
        assertEquals(Duration.ofSeconds(120), RetryAfter.parse(inTwoMinutes, now));
        assertEquals(Duration.ofHours(1), RetryAfter.parse("86400", now), "capped");
        assertNull(RetryAfter.parse(null, now));
        assertNull(RetryAfter.parse("soon", now));
        assertNull(RetryAfter.parse("0", now));
        assertNull(RetryAfter.parse("-5", now));
    }

    @Test
    void theLocalFolderCheckWritesAndDeletesATestFileAndFailsWhenItCanNotWrite(@TempDir Path root) throws Exception {
        LocalDeliveryProperties properties = new LocalDeliveryProperties();
        properties.setOutputPath(root.toString());
        LocalFolderDeliveryAdapter adapter = new LocalFolderDeliveryAdapter(properties);

        adapter.checkDestination(tenant);
        Path staging = root.resolve(tenant.value().toString()).resolve(".staging");
        assertTrue(Files.isDirectory(staging));
        try (var left = Files.list(staging)) {
            assertEquals(0, left.count(), "the test file is deleted again");
        }

        // A file where the tenant folder should be: the folder can't be written.
        TenantId blocked = TenantId.generate();
        Files.writeString(root.resolve(blocked.value().toString()), "not a folder");
        TechnicalDependencyException failure = assertThrows(TechnicalDependencyException.class,
                () -> adapter.checkDestination(blocked));
        assertEquals(LvErrorCode.DLV_001, LvErrorCodes.of(failure));
    }

}
