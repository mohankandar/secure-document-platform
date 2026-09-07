package com.securedoc.platform.api.observability;

import com.securedoc.platform.application.observability.StructuredLogContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/** Establishes one correlation ID for the entire HTTP request without trusting it as identity data. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    public static final String REQUEST_ATTRIBUTE_CORRELATION_ID =
            RequestCorrelationFilter.class.getName() + ".correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        UUID correlationId = resolve(request.getHeader(HEADER_CORRELATION_ID));
        request.setAttribute(REQUEST_ATTRIBUTE_CORRELATION_ID, correlationId);
        response.setHeader(HEADER_CORRELATION_ID, correlationId.toString());

        try (var ignored = StructuredLogContext.open(Map.of("correlationId", correlationId))) {
            filterChain.doFilter(request, response);
        } finally {
            StructuredLogContext.clearRequestContext();
        }
    }

    private UUID resolve(String raw) {
        if (raw != null && !raw.isBlank()) {
            try {
                return UUID.fromString(raw.trim());
            } catch (IllegalArgumentException ignored) {
                // Invalid correlation metadata is replaced; it is never treated as identity truth.
            }
        }
        return UUID.randomUUID();
    }
}
