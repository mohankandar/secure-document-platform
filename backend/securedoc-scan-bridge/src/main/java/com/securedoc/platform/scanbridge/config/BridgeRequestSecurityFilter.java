package com.securedoc.platform.scanbridge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
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
            reject(response, "BRIDGE_ORIGIN_NOT_ALLOWED", "This browser origin is not allowed to use the local Scanner Bridge.");
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
                reject(response, "BRIDGE_REQUEST_TOKEN_INVALID",
                        "The Scanner Bridge browser request token is missing or no longer current.");
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

    private void reject(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), Map.of("errorCode", code, "message", message));
    }
}
