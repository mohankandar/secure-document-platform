package com.securedoc.platform.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hardening: endpoints deny by default. Every API endpoint has a rule: a capability, or it is marked and listed as open. */
class EveryEndpointHasARuleTest {

    static final String API = "/api/v1";

    @Test
    void everyApiEndpointHasACapabilityOrIsListedAsOpen() throws Exception {
        List<String> without = new ArrayList<>();
        List<String> open = new ArrayList<>();
        int endpoints = 0;
        for (Method method : apiMethods()) {
            endpoints++;
            if (method.isAnnotationPresent(OpenEndpoint.class)) {
                open.add(name(method));
            } else if (!method.isAnnotationPresent(RequireCapability.class)) {
                // A role mark alone is not a rule: nothing enforces it on its own.
                without.add(name(method));
            }
        }
        assertTrue(endpoints > 100, "the walk found the endpoints: " + endpoints);
        assertEquals(List.of(), without, "API endpoints without a rule");
        assertEquals(OpenEndpoint.LISTED, open.stream().sorted().toList(), "the open endpoints are the listed ones");
    }

    /** Every handler method of the application's controllers mapped under {@value #API}. */
    static List<Method> apiMethods() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<Method> methods = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("com.securedoc.platform")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            // The application's controllers only, not the small ones tests make for themselves.
            if (type.getProtectionDomain().getCodeSource().getLocation().getPath().contains("test-classes")) continue;
            RequestMapping onType = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] prefixes = onType == null || onType.path().length == 0 ? new String[]{""} : onType.path();
            for (Method method : type.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                String[] paths = mapping.path().length == 0 ? new String[]{""} : mapping.path();
                boolean api = false;
                for (String prefix : prefixes) for (String path : paths) api |= (prefix + path).startsWith(API);
                if (api) methods.add(method);
            }
        }
        return methods;
    }

    static String name(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }
}
