package com.securedoc.platform.api.security;

import jakarta.servlet.http.HttpServletRequest;

/** The routes whose caller is a machine, not a person (task 0075: the scanner's too). */
public final class MachineRoutes {
    private static final String PUSH = "/api/v1/external-intakes";
    private static final String SCANNER = "/api/v1/scanner-intakes";

    private MachineRoutes() { }

    public static boolean machine(HttpServletRequest request) {
        return under(request, PUSH) || scanner(request);
    }

    public static boolean scanner(HttpServletRequest request) {
        return under(request, SCANNER);
    }

    private static boolean under(HttpServletRequest request, String route) {
        String path = request == null ? null : request.getServletPath();
        return path != null && (path.equals(route) || path.startsWith(route + "/"));
    }
}
