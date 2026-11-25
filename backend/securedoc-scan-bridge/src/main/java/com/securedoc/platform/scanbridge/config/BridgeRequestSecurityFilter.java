package com.securedoc.platform.scanbridge.config;

import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.ObjectMapper;
import com.securedoc.platform.scanbridge.model.ApiErrorResponse;
import com.securedoc.platform.scanbridge.service.BridgeRuntimeIdentity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * Hardens the browser -> loopback Scanner Bridge boundary.
 *
 * <p>CORS alone does not prevent a hostile web page from issuing every kind of state-changing
 * request. Browser-origin calls are therefore checked server-side and all mutating API requests
 * require a per-process nonce obtained by the trusted LexaVault UI from the configuration endpoint.</p>
 */
@Component
public final class BridgeRequestSecurityFilter extends OncePerRequestFilter {
    public static final String REQUEST_TOKEN_HEADER = "X-LexaVault-Bridge-Token";
    /** Task 0055: a JSON body holds the case's few values; a test PDF (multipart) has its own limit. */
    static final long MAX_JSON_BYTES = 64 * 1024;
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final ScanBridgeProperties properties;
    private final BridgeRuntimeIdentity runtimeIdentity;
    private final ObjectMapper objectMapper;

    public BridgeRequestSecurityFilter(ScanBridgeProperties properties, BridgeRuntimeIdentity runtimeIdentity,
                                       ObjectMapper objectMapper) {
        this.properties = properties;
        this.runtimeIdentity = runtimeIdentity;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank() && properties.getAllowedOrigins().stream().noneMatch(origin::equals)) {
            reject(response, "BRIDGE_ORIGIN_NOT_ALLOWED");
            return;
        }
        if (origin != null && !origin.isBlank()) {
            // Handle CORS at the servlet-filter boundary instead of relying only on MVC. Browser
            // mutations use a custom anti-CSRF header and therefore always preflight. A failed
            // OPTIONS request looks like a network outage to fetch(), even while bridge GETs are
            // healthy, which is particularly confusing on the Scanner Intake screen.
            addCorsHeaders(request, response, origin);
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())
                    && request.getHeader("Access-Control-Request-Method") != null) {
                response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                return;
            }
        }

        if ("/api/v1/configuration".equals(request.getRequestURI())) {
            response.setHeader("Cache-Control", "no-store, max-age=0");
            response.setHeader("Pragma", "no-cache");
        }

        if (MUTATING_METHODS.contains(request.getMethod())) {
            String supplied = request.getHeader(REQUEST_TOKEN_HEADER);
            if (!constantTimeEquals(runtimeIdentity.browserRequestToken(), supplied)) {
                reject(response, "BRIDGE_REQUEST_TOKEN_INVALID");
                return;
            }
            String type = request.getContentType();
            boolean file = type != null && type.strip().toLowerCase(java.util.Locale.ROOT).startsWith("multipart/");
            // The trusted screen always announces the length; a body that does not is refused too.
            if (!file && (request.getContentLengthLong() > MAX_JSON_BYTES || request.getHeader("Transfer-Encoding") != null)) {
                reject(response, "INVALID_BUSINESS_IDENTIFIER_INPUT", HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private void addCorsHeaders(HttpServletRequest request, HttpServletResponse response, String origin) {
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, " + REQUEST_TOKEN_HEADER);
        response.setHeader("Access-Control-Max-Age", "600");
        response.addHeader("Vary", "Origin");
        response.addHeader("Vary", "Access-Control-Request-Method");
        response.addHeader("Vary", "Access-Control-Request-Headers");

        // Chromium's Private Network Access / Local Network Access preflight may ask for this
        // when the trusted UI origin and loopback bridge fall into different address spaces.
        // The bridge is still protected by exact origin allow-listing, loopback-only binding,
        // and the per-process mutation token.
        if ("true".equalsIgnoreCase(request.getHeader("Access-Control-Request-Private-Network"))) {
            response.setHeader("Access-Control-Allow-Private-Network", "true");
            response.addHeader("Vary", "Access-Control-Request-Private-Network");
        }
    }

    private boolean constantTimeEquals(String expected, String supplied) {
        if (supplied == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    /** Task 0025: the same body as every other bridge error: errorCode, the LV code, and its title as message. */
    private void reject(HttpServletResponse response, String errorCode) throws IOException {
        reject(response, errorCode, HttpServletResponse.SC_FORBIDDEN);
    }

    private void reject(HttpServletResponse response, String errorCode, int status) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            objectMapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(errorCode));
        } catch (JacksonIOException e) {
            throw e.getCause();
        }
    }
}
