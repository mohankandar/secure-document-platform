package com.securedoc.platform.api.intake.external;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.exception.GlobalExceptionHandler;
import com.securedoc.platform.application.intake.external.ExternalIntakeService;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/** Task 0063: where the PDF password may come from, how it is handed on, and that no answer repeats it. */
class PdfPasswordUploadTest {

    private static final String PASSWORD = "Quartz-Lantern-58\u00e9";

    private final ExternalIntakeService intake = mock(ExternalIntakeService.class);
    private final List<String> seen = new ArrayList<>();
    private final List<char[]> arrays = new ArrayList<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        RequestContextResolver context = mock(RequestContextResolver.class);
        when(context.resolveIntegrationPrincipal(any())).thenReturn(
                AuthenticatedPrincipal.of(ActorId.generate(), TenantId.generate(), "source", Set.of()));
        when(context.resolveCorrelationId(any())).thenReturn(CorrelationId.generate());
        when(intake.receivePushDocument(any(), any(), any(), any(), any(), any(), any(char[].class), any(), any()))
                .thenAnswer(call -> {
                    char[] password = call.getArgument(6);
                    seen.add(new String(password));
                    arrays.add(password);
                    throw new com.securedoc.platform.application.exception.ValidationException(
                            com.securedoc.platform.domain.error.LvErrorCode.INT_036, "The password does not open this PDF");
                });
        mvc = MockMvcBuilders.standaloneSetup(new ExternalIntakeController(intake, context))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void thePartIsHandedOnOnceAndClearedAndTheAnswerDoesNotRepeatIt() throws Exception {
        MvcResult result = send(upload("").part(password(PASSWORD)), true, null);
        assertEquals(400, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("LV-INT-036"));
        assertFalse(result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains(PASSWORD));
        assertEquals(List.of(PASSWORD), seen);
        assertArrayEquals(new char[PASSWORD.length()], arrays.get(0), "cleared after use, also when refused");
    }

    @Test
    void theAddressAndPlainHttpAreRefused() throws Exception {
        for (String query : List.of("?password=" + "Quartz", "?x=1&PASSWORD=Quartz", "?pass%77ord=Quartz")) {
            assertRefused(send(upload(query), true, null));
        }
        assertRefused(send(upload("").part(password(PASSWORD)), false, null));
        assertRefused(send(upload("").part(password(PASSWORD)), false, "http"));
        assertTrue(seen.isEmpty());
        verify(intake, never()).receivePushDocument(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void httpsAtTheProxyCounts() throws Exception {
        send(upload("").part(password(PASSWORD)), false, "https");
        assertEquals(1, seen.size());
    }

    @Test
    void aPasswordNoPdfCanHaveIsRefusedWithoutReadingIt() throws Exception {
        MvcResult result = send(upload("").part(password("x".repeat(128))), true, null);
        assertTrue(result.getResponse().getContentAsString().contains("LV-INT-036"));
        assertTrue(seen.isEmpty());
    }

    @Test
    void withoutAPasswordTheUploadIsAsBefore() throws Exception {
        send(upload(""), false, null);
        verify(intake).receivePushDocument(any(), any(), any(), any(), any(), any(), any(), any());
        assertTrue(seen.isEmpty());
    }

    /** Spring's default (0) writes every part, a password too, to a temporary file. */
    @Test
    void smallPartsStayInMemory() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Object threshold = yaml.getObject().get("spring.servlet.multipart.file-size-threshold");
        assertNotNull(threshold);
        assertTrue(org.springframework.util.unit.DataSize.parse(threshold.toString()).toBytes() > PdfPasswordPart.MAX_BYTES);
    }

    private static MockPart password(String value) {
        return new MockPart("password", value.getBytes(StandardCharsets.UTF_8));
    }

    private static MockMultipartHttpServletRequestBuilder upload(String query) {
        return multipart(java.net.URI.create("/api/v1/external-intakes/" + UUID.randomUUID() + "/documents/DOC-1" + query))
                .file(new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[]{'%', 'P', 'D', 'F'}));
    }

    private MvcResult send(MockMultipartHttpServletRequestBuilder upload, boolean secure, String forwardedProto) throws Exception {
        upload.header("Idempotency-Key", "k").header("X-Content-SHA256", "a".repeat(64));
        if (forwardedProto != null) upload.header("X-Forwarded-Proto", forwardedProto);
        upload.with(request -> {
            request.setSecure(secure);
            request.setRemoteAddr("10.1.2.3");
            return request;
        });
        return mvc.perform(upload).andReturn();
    }

    private static void assertRefused(MvcResult result) throws Exception {
        assertEquals(400, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("LV-INT-037"), result.getResponse().getContentAsString());
        assertFalse(result.getResponse().getContentAsString().contains("Quartz"));
    }
}
