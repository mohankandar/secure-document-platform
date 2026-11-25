package com.securedoc.platform.api.intake;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.dto.ApiErrorResponse;
import com.securedoc.platform.api.security.MachineRoutes;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.integration.IntegrationConnectionService;
import com.securedoc.platform.application.port.out.IntakeRefusalPort;
import com.securedoc.platform.domain.error.LvErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Task 0081: counts a case or document that push, the scanner or a manual upload was refused, by source system and LV code
 * only. The answer is already made and is never changed. Not counted: a caller who may not do it (LV-AUT), a busy answer the
 * sender repeats (429), an address that is not there, and LexaVault's own faults (5xx).
 */
@RestControllerAdvice
public class IntakeRefusals implements ResponseBodyAdvice<Object> {
    private static final Logger log = LoggerFactory.getLogger(IntakeRefusals.class);
    private static final Pattern MANUAL = Pattern.compile("/api/v1/packages(/[^/]+/(documents|submit))?");
    private static final Set<Integer> NOT_REFUSALS = Set.of(401, 404, 405, 429);

    private final RequestContextResolver contextResolver;
    private final IntegrationConnectionService connections;
    private final IntakeRefusalPort refusals;

    public IntakeRefusals(RequestContextResolver contextResolver, IntegrationConnectionService connections,
                          IntakeRefusalPort refusals) {
        this.contextResolver = contextResolver;
        this.connections = connections;
        this.refusals = refusals;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body instanceof ApiErrorResponse error && request instanceof ServletServerHttpRequest in
                && response instanceof ServletServerHttpResponse out) {
            try {
                count(error, in.getServletRequest(), out.getServletResponse().getStatus());
            } catch (RuntimeException failure) {
                // No admitted caller to count it for: the answer stands.
                log.debug("INTAKE_REFUSAL_NOT_COUNTED reason={}", failure.getClass().getSimpleName());
            }
        }
        return body;
    }

    private void count(ApiErrorResponse error, HttpServletRequest request, int status) {
        if (!"POST".equals(request.getMethod()) || status < 400 || status >= 500 || NOT_REFUSALS.contains(status)) return;
        boolean machine = MachineRoutes.machine(request);
        if (!machine && (request.getServletPath() == null || !MANUAL.matcher(request.getServletPath()).matches())) return;
        LvErrorCode code = LvErrorCode.fromCode(error.code()).orElse(null);
        if (code == null || code.area() == LvErrorCode.Area.AUT) return;
        AuthenticatedPrincipal principal = machine ? contextResolver.resolveIntegrationPrincipal(request)
                : contextResolver.resolvePrincipal(request);
        refusals.record(principal.tenantId(), machine ? source(principal, MachineRoutes.scanner(request)) : IntakeRefusalPort.MANUAL, code);
    }

    private String source(AuthenticatedPrincipal principal, boolean scanner) {
        return (scanner ? connections.resolveEnabledScannerSource(principal.tenantId(), principal.username())
                : connections.resolveEnabledPushSource(principal.tenantId(), principal.username())).id().value().toString();
    }
}
