package com.securedoc.platform.api.protection;

import com.securedoc.platform.application.port.out.WorkGate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/** Task 0060: while no work may be taken, every request that could change something is refused (503); reading goes on. */
@Component
public class WorkGateInterceptor implements HandlerInterceptor {
    private static final Set<String> READING = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final WorkGate gate;

    public WorkGateInterceptor(WorkGate gate) {
        this.gate = gate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!READING.contains(request.getMethod())) gate.requireWork();
        return true;
    }
}
