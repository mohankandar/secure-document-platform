package com.securedoc.platform.api.security;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** Fixed V1 role-to-capability mapping. This is product policy, not tenant-editable business configuration. */
@Component
public class ProductAuthorizationPolicy {

    private static final EnumSet<UserCapability> DOCUMENT_PROCESSOR_CAPABILITIES = EnumSet.of(
            UserCapability.DASHBOARD_ACCESS,
            UserCapability.DOCUMENT_INTAKE,
            UserCapability.MANUAL_REVIEW,
            UserCapability.FINDING_DECISION,
            UserCapability.REDACTION_ADJUST,
            UserCapability.REVIEW_SUBMIT,
            UserCapability.PACKAGE_APPROVE_DELIVERY);

    private static final EnumSet<UserCapability> DOCUMENT_SUPERVISOR_CAPABILITIES = EnumSet.of(
            UserCapability.PACKAGE_REPROCESS,
            UserCapability.PACKAGE_REMOVE_FROM_DASHBOARD);

    private static final EnumSet<UserCapability> REPORT_READ_CAPABILITIES = EnumSet.of(
            UserCapability.REPORT_EXECUTIVE_VIEW,
            UserCapability.REPORT_OPERATIONAL_VIEW);

    public Set<UserCapability> capabilitiesFor(Set<String> roles) {
        EnumSet<UserCapability> capabilities = EnumSet.noneOf(UserCapability.class);
        if (roles == null) return Set.of();
        for (String role : roles) {
            if (role == null) continue;
            switch (role.trim().toUpperCase()) {
                case "ADMIN" -> capabilities.addAll(EnumSet.allOf(UserCapability.class));
                case "DOCUMENT_PROCESSOR" -> capabilities.addAll(DOCUMENT_PROCESSOR_CAPABILITIES);
                case "DOCUMENT_SUPERVISOR" -> {
                    capabilities.addAll(DOCUMENT_PROCESSOR_CAPABILITIES);
                    capabilities.addAll(DOCUMENT_SUPERVISOR_CAPABILITIES);
                }
                case "BUSINESS_ADMIN" -> {
                    capabilities.addAll(DOCUMENT_PROCESSOR_CAPABILITIES);
                    capabilities.addAll(DOCUMENT_SUPERVISOR_CAPABILITIES);
                    capabilities.addAll(REPORT_READ_CAPABILITIES);
                    capabilities.add(UserCapability.REPORT_EXPORT);
                    capabilities.add(UserCapability.TENANT_POLICY_MANAGE);
                    capabilities.add(UserCapability.TENANT_IDENTIFIER_MANAGE);
                }
                case "REPORT_VIEWER" -> capabilities.add(UserCapability.REPORT_EXECUTIVE_VIEW);
                case "TENANT_IT_ADMIN" -> {
                    capabilities.add(UserCapability.DASHBOARD_ACCESS);
                    capabilities.addAll(REPORT_READ_CAPABILITIES);
                    capabilities.add(UserCapability.REPORT_EXPORT);
                    capabilities.add(UserCapability.TENANT_INTEGRATION_MANAGE);
                    capabilities.add(UserCapability.TENANT_AUTH_MANAGE);
                    capabilities.add(UserCapability.TECHNICAL_EXCEPTION_VIEW);
                    capabilities.add(UserCapability.TECHNICAL_ADMIN_VIEW);
                    capabilities.add(UserCapability.TECHNICAL_RETRY);
                    capabilities.add(UserCapability.TECHNICAL_HOLD_MANAGE);
                    capabilities.add(UserCapability.AUDIT_VIEW);
                }
                case "PLATFORM_OPERATIONS_ADMIN" -> {
                    capabilities.add(UserCapability.DASHBOARD_ACCESS);
                    capabilities.addAll(REPORT_READ_CAPABILITIES);
                    capabilities.add(UserCapability.REPORT_EXPORT);
                    capabilities.add(UserCapability.TENANT_INTEGRATION_MANAGE);
                    capabilities.add(UserCapability.TECHNICAL_EXCEPTION_VIEW);
                    capabilities.add(UserCapability.TECHNICAL_ADMIN_VIEW);
                    capabilities.add(UserCapability.TECHNICAL_RETRY);
                    capabilities.add(UserCapability.TECHNICAL_HOLD_MANAGE);
                    capabilities.add(UserCapability.AUDIT_VIEW);
                    capabilities.add(UserCapability.PLATFORM_DIAGNOSTICS);
                    capabilities.add(UserCapability.PLATFORM_OPERATIONS);
                }
                default -> {
                    // Unknown names receive no capabilities. Legacy aliases are normalized only at identity boundaries.
                }
            }
        }
        return Set.copyOf(capabilities);
    }

    public Set<String> capabilityNamesFor(Set<String> roles) {
        Set<String> names = new LinkedHashSet<>();
        capabilitiesFor(roles).stream().map(Enum::name).sorted().forEach(names::add);
        return Set.copyOf(names);
    }

    public boolean has(AuthenticatedPrincipal principal, UserCapability capability) {
        return principal != null && capabilitiesFor(principal.roles()).contains(capability);
    }
}
