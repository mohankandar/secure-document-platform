package com.securedoc.platform.infrastructure.intake.polling;

import com.securedoc.platform.application.intake.polling.PollingSourceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Hardening (task 0110): polling answer size limit. A larger answer is refused and nothing is stored. */
class PollingAnswerSizeLimitTest {

    private static final String EMPTY_LIST = "{\"items\":[]}";

    @Test
    void anAnswerUpToTheLimitIsRead(@TempDir Path staging) throws Exception {
        HttpPollingSourceAdapter adapter = adapter(staging, EMPTY_LIST, EMPTY_LIST.length());
        assertDoesNotThrow(() -> adapter.discoverAvailable(connection(), 10));
    }

    @Test
    void aLargerAnswerIsRefusedWithItsOwnCodeAndNothingIsStored(@TempDir Path staging) throws Exception {
        HttpPollingSourceAdapter adapter = adapter(staging, EMPTY_LIST, EMPTY_LIST.length() - 1);
        PollingSourceException refused = assertThrows(PollingSourceException.class,
                () -> adapter.discoverAvailable(connection(), 10));
        assertEquals("POLL_RESPONSE_TOO_LARGE", refused.category());
        assertEquals("LV-INT-045", com.securedoc.platform.application.error.LvErrorCodes.forPollingCategory(
                "POLL_RESPONSE_TOO_LARGE").code());
        try (var stored = Files.list(staging)) {
            assertTrue(stored.findAny().isEmpty(), "nothing is stored");
        }
    }

    @Test
    void theDefaultLimitIsTwentyMegabytes() {
        assertEquals(20 * 1024 * 1024, HttpPollingSourceAdapter.DEFAULT_MAX_ANSWER_BYTES);
    }

    private static com.securedoc.platform.domain.integration.IntegrationConnection connection() {
        return com.securedoc.platform.domain.integration.IntegrationConnection.create(
                com.securedoc.platform.domain.identity.TenantId.generate(), "Source", null,
                com.securedoc.platform.domain.integration.IntegrationPurpose.INGESTION_SOURCE,
                com.securedoc.platform.domain.integration.IntegrationDirection.OUTBOUND,
                com.securedoc.platform.domain.integration.IntegrationProviderType.HTTP_API,
                com.securedoc.platform.domain.integration.IntegrationAuthenticationType.NONE, null,
                "https://source.example.test", null, null, 30, 0, 24, 10, 10_000_000L, 100,
                com.securedoc.platform.domain.integration.IntegrationPollingScheduleType.INTERVAL, 15, null, null, 100,
                com.securedoc.platform.domain.integration.IntegrationAcknowledgementMode.SOURCE_PICKUP_ACK, true,
                java.time.Instant.now());
    }

    private static HttpPollingSourceAdapter adapter(Path staging, String body, int limit) throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<InputStream> answer = mock(HttpResponse.class);
        when(answer.statusCode()).thenReturn(200);
        when(answer.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> answer);
        return new HttpPollingSourceAdapter(client, JsonMapper.builderWithJackson2Defaults().build(), staging,
                mock(com.securedoc.platform.infrastructure.secrets.IntegrationSecrets.class), limit);
    }
}
