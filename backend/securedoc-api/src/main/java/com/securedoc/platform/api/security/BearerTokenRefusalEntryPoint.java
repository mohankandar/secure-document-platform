package com.securedoc.platform.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.api.dto.ApiErrorResponse;
import com.securedoc.platform.api.observability.RequestCorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

/**
 * Task 0023 C5 (#252): the 401 for {@code /api/v1/**} in OIDC mode. The person still gets only
 * {@code AUTHENTICATION_REQUIRED} / "Authentication is required"; the body now also carries the request's
 * {@code correlationId} and a timestamp like every other error. The log line names the reason category, so IT can
 * tell a wrong issuer or audience from an expired session:
 * {@code AUTHENTICATION_REJECTED reason=<CATEGORY> method=<M> correlation=<id>}.
 *
 * <p>Categories: {@code NO_TOKEN}, {@code EXPIRED}, {@code NOT_YET_VALID}, {@code ISSUER}, {@code AUDIENCE},
 * {@code SIGNATURE}, {@code MALFORMED}, {@code KEYS_UNAVAILABLE} (the issuer's keys could not be fetched),
 * {@code INVALID_CLAIMS} (another claim check failed), {@code OTHER}. The token, its claims and the exception
 * messages (which can quote claim values) are never logged.
 */
public final class BearerTokenRefusalEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(BearerTokenRefusalEntryPoint.class);

    private final ObjectMapper objectMapper;

    public BearerTokenRefusalEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        String correlationId = correlationId(request);
        String reason = reason(exception);
        if ("NO_TOKEN".equals(reason)) {
            log.info("AUTHENTICATION_REJECTED reason={} method={} correlation={}", reason,
                    CapabilityAuthorizationInterceptor.method(request), correlationId);
        } else {
            log.warn("AUTHENTICATION_REJECTED reason={} method={} correlation={}", reason,
                    CapabilityAuthorizationInterceptor.method(request), correlationId);
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(RequestCorrelationFilter.HEADER_CORRELATION_ID, correlationId);
        objectMapper.writeValue(response.getOutputStream(),
                ApiErrorResponse.of("AUTHENTICATION_REQUIRED", "Authentication is required", correlationId));
    }

    /** The reason category; reads only exception types and Spring's own fixed error descriptions. */
    static String reason(AuthenticationException exception) {
        if (exception == null || exception instanceof InsufficientAuthenticationException) return "NO_TOKEN";
        if (exception instanceof AuthenticationServiceException) return "KEYS_UNAVAILABLE";
        for (Throwable cause = exception; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof JwtValidationException invalid) return validationReason(invalid);
            if (cause instanceof BadJwtException bad) return decodeReason(bad);
        }
        if (exception instanceof InvalidBearerTokenException) return "MALFORMED";
        return "OTHER";
    }

    private static String validationReason(JwtValidationException invalid) {
        for (OAuth2Error error : invalid.getErrors()) {
            String description = lower(error.getDescription());
            if (description.contains("expired")) return "EXPIRED";
            if (description.contains("used before")) return "NOT_YET_VALID";
            if (description.contains("iss claim") || description.contains("issuer")) return "ISSUER";
            if (description.contains("audience") || description.contains("aud claim")) return "AUDIENCE";
        }
        return "INVALID_CLAIMS";
    }

    private static String decodeReason(BadJwtException bad) {
        String message = lower(bad.getMessage());
        if (message.contains("signature") || message.contains("no matching key")
                || message.contains("another algorithm expected")) {
            return "SIGNATURE";
        }
        return "MALFORMED";
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String correlationId(HttpServletRequest request) {
        Object established = request == null ? null
                : request.getAttribute(RequestCorrelationFilter.REQUEST_ATTRIBUTE_CORRELATION_ID);
        return established instanceof UUID uuid ? uuid.toString() : UUID.randomUUID().toString();
    }
}
