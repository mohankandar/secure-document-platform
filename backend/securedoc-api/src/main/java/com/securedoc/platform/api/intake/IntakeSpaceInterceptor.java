package com.securedoc.platform.api.intake;

import com.securedoc.platform.application.intake.IntakeGate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/** Task 0067: the safety stop on every request that makes a case or brings a document (LV-INT-027, 429). */
@Component
public class IntakeSpaceInterceptor implements HandlerInterceptor {
    /** Submitting or asking about a case that is already here stays possible. */
    public static final List<String> PATHS = List.of("/api/v1/packages", "/api/v1/packages/*/documents",
            "/api/v1/external-intakes", "/api/v1/external-intakes/*/documents/*",
            "/api/v1/scanner-intakes", "/api/v1/scanner-intakes/*/documents/*");

    private final IntakeGate gate;

    public IntakeSpaceInterceptor(IntakeGate gate) {
        this.gate = gate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("POST".equals(request.getMethod())) gate.requireSpace();
        return true;
    }
}
