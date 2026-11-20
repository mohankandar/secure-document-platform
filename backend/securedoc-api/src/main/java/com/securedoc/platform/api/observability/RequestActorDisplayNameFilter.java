package com.securedoc.platform.api.observability;

import com.securedoc.platform.application.audit.RequestActorDisplayName;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Makes sure the audit display name never outlives its request (task 0016). The name is bound where the request's
 * principal is resolved; this filter clears it before and after every request, so a pooled thread never carries one
 * person's name into the next request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
public class RequestActorDisplayNameFilter extends OncePerRequestFilter {

    private final RequestActorDisplayName actorDisplayName;

    public RequestActorDisplayNameFilter(RequestActorDisplayName actorDisplayName) {
        this.actorDisplayName = actorDisplayName;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        actorDisplayName.clear();
        try {
            filterChain.doFilter(request, response);
        } finally {
            actorDisplayName.clear();
        }
    }
}
