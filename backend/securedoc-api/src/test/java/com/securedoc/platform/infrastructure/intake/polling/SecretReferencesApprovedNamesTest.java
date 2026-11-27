package com.securedoc.platform.infrastructure.intake.polling;

import com.securedoc.platform.api.notifications.AppSecretResolver;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.integration.IntegrationConnectionService;
import com.securedoc.platform.application.intake.polling.PollingSourceException;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.application.port.out.SecretReferenceRule;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.integration.*;
import com.securedoc.platform.infrastructure.secrets.IntegrationSecrets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Hardening: secret references limited to approved names. */
class SecretReferencesApprovedNamesTest {

    private static final TenantId ENTITY = TenantId.generate();
    private static final TenantId OTHER = TenantId.generate();
    private static final String VALUE = "value-that-must-never-show-0109";
    private static final String APPROVED = "env:" + AppSecretResolver.entityPrefix(ENTITY) + "SOURCE_KEY";
    private static final List<String> REFUSED = List.of(
            "env:PATH",
            "env:SPRING_DATASOURCE_PASSWORD",
            "env:" + AppSecretResolver.entityPrefix(OTHER) + "SOURCE_KEY",
            "sys:java.home",
            "sys:" + AppSecretResolver.entityPrefix(ENTITY) + "SOURCE_KEY",
            "vault://anything");

    private final Map<String, String> environment = new HashMap<>();
    private final List<String> looked = new ArrayList<>();
    private final AppSecretResolver resolver = new AppSecretResolver(name -> {
        looked.add(name);
        return environment.get(name);
    }, "");
    private final SecretReferenceRule rule = AppSecretResolver::entityRef;
    private final IntegrationSecrets secrets = new IntegrationSecrets(rule, resolver);

    {
        environment.put(APPROVED.substring(4), VALUE);
        // Present, so a refusal is the rule's and not a missing value.
        environment.put("PATH", VALUE);
        environment.put("SPRING_DATASOURCE_PASSWORD", VALUE);
        environment.put(AppSecretResolver.entityPrefix(OTHER) + "SOURCE_KEY", VALUE);
    }

    // ------------------------------------------------------------------------------------------------ on save

    @Test
    void anApprovedNameIsSaved() {
        List<AuditRecord> audits = new ArrayList<>();
        IntegrationConnectionService service = service(audits);
        IntegrationConnection saved = service.create(ENTITY, ActorId.generate(), CorrelationId.generate(), input(APPROVED));
        assertEquals(APPROVED, saved.secretReference());
        assertTrue(audits.stream().allMatch(a -> a.detail() == null || !a.detail().contains(VALUE)));
        assertTrue(looked.isEmpty(), "saving never reads the secret");
    }

    @Test
    void anUnapprovedEnvOrSysNameIsNotSaved() {
        IntegrationConnectionRepository repository = mock(IntegrationConnectionRepository.class);
        List<AuditRecord> audits = new ArrayList<>();
        IntegrationConnectionService service = new IntegrationConnectionService(repository, new AuditService(audits::add))
                .withSecretReferences(rule);
        for (String reference : REFUSED) {
            ValidationException refused = assertThrows(ValidationException.class,
                    () -> service.create(ENTITY, ActorId.generate(), CorrelationId.generate(), input(reference)), reference);
            assertEquals(LvErrorCode.CFG_003, refused.lvCode(), reference);
            assertTrue(refused.getMessage().contains("secretReference"), "names the field");
            assertFalse(refused.getMessage().contains(reference) || refused.getMessage().contains(VALUE), refused.getMessage());
            assertThrows(ValidationException.class,
                    () -> service.draft(ENTITY, null, input(reference)), "a connection test is refused the same way");
        }
        verify(repository, never()).save(any());
        assertTrue(audits.isEmpty());
        assertTrue(looked.isEmpty(), "a refused name is never looked up");
    }

    @Test
    void withoutTheAppsRuleEveryReferenceIsRefused() {
        IntegrationConnectionService service = new IntegrationConnectionService(mock(IntegrationConnectionRepository.class),
                new AuditService(r -> { }));
        assertThrows(ValidationException.class,
                () -> service.create(ENTITY, ActorId.generate(), CorrelationId.generate(), input(APPROVED)));
    }

    // ------------------------------------------------------------------------------------------------ at run time

    @Test
    void theReaderGivesAnApprovedNamesValueAndRefusesTheRestWithoutLookingThemUp() {
        assertEquals(VALUE, secrets.read(connection(APPROVED)));
        looked.clear();
        for (String reference : REFUSED) {
            IntegrationSecrets.NotRead refused = assertThrows(IntegrationSecrets.NotRead.class,
                    () -> secrets.read(connection(reference)), reference);
            assertEquals(IntegrationSecrets.Problem.REFUSED, refused.problem());
            assertFalse(refused.getMessage().contains(reference) || refused.getMessage().contains(VALUE));
        }
        assertTrue(looked.isEmpty(), "a refused name is never looked up: " + looked);
        System.setProperty("lexavault.test.0109", VALUE);
        try {
            assertThrows(IntegrationSecrets.NotRead.class, () -> secrets.read(connection("sys:lexavault.test.0109")));
        } finally {
            System.clearProperty("lexavault.test.0109");
        }
    }

    @Test
    void aSavedSourceWithAnUnapprovedNameDoesNotRunAndSendsNothing(@TempDir Path staging) throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpPollingSourceAdapter adapter = new HttpPollingSourceAdapter(client, JsonMapper.builderWithJackson2Defaults().build(),
                staging, secrets);
        for (String reference : REFUSED) {
            PollingSourceException refused = assertThrows(PollingSourceException.class,
                    () -> adapter.discoverAvailable(connection(reference), 10), reference);
            assertEquals("POLL_AUTH_CONFIGURATION_ERROR", refused.category());
            assertFalse(refused.getMessage().contains(reference) || refused.getMessage().contains(VALUE));
        }
        verify(client, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void aSavedSourceWithAnApprovedNameSignsInWithItsValue(@TempDir Path staging) throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<java.io.InputStream> answer = mock(HttpResponse.class);
        when(answer.statusCode()).thenReturn(200);
        // Task 0110: answers are read as a stream, up to the size limit.
        when(answer.body()).thenReturn(new java.io.ByteArrayInputStream("{\"items\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        HttpRequest[] sent = new HttpRequest[1];
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            sent[0] = call.getArgument(0);
            return answer;
        });
        HttpPollingSourceAdapter adapter = new HttpPollingSourceAdapter(client, JsonMapper.builderWithJackson2Defaults().build(),
                staging, secrets);
        assertDoesNotThrow(() -> adapter.discoverAvailable(connection(APPROVED), 10));
        assertEquals(Optional.of(VALUE), sent[0].headers().firstValue("X-API-Key"));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private IntegrationConnectionService service(List<AuditRecord> audits) {
        return new IntegrationConnectionService(mock(IntegrationConnectionRepository.class), new AuditService(audits::add))
                .withSecretReferences(rule);
    }

    private static IntegrationConnectionService.ConnectionInput input(String reference) {
        return new IntegrationConnectionService.ConnectionInput("Source", null, IntegrationPurpose.INGESTION_SOURCE,
                IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.API_KEY, null,
                "https://source.example.test", null, reference, false, 60, 2, 24, 50, 262144000L, 500,
                IntegrationPollingScheduleType.INTERVAL, 15, null, null, 100,
                IntegrationAcknowledgementMode.SOURCE_PICKUP_ACK, true);
    }

    static IntegrationConnection connection(String reference) {
        return IntegrationConnection.create(ENTITY, "Source", null, IntegrationPurpose.INGESTION_SOURCE,
                IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.API_KEY, null,
                "https://source.example.test", null, reference, 30, 0, 24, 10, 10_000_000L, 100,
                IntegrationPollingScheduleType.INTERVAL, 15, null, null, 100,
                IntegrationAcknowledgementMode.SOURCE_PICKUP_ACK, true, Instant.now());
    }
}
