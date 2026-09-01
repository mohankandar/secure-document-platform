package com.securedoc.platform.api.security;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** Fixed V1 role-to-capability mapping. This is product policy, not tenant-editable business configuration. */
@Component
public class ProductAuthorizationPolicy {

    public Set<UserCapability> capabilitiesFor(Set<String> roles) {
        EnumSet<UserCapability> capabilities = EnumSet.noneOf(UserCapability.class);
        if (roles == null) return Set.of();
        for (String role : roles) {
            if (role == null) continue;
            switch (role.trim().toUpperCase()) {
                case "ADMIN" -> capabilities.addAll(EnumSet.allOf(UserCapability.class));
                case "REVIEWER" -> capabilities.add(UserCapability.MANUAL_REVIEW);
                case "APPROVER" -> {
                    capabilities.add(UserCapability.MANUAL_REVIEW);
                    capabilities.add(UserCapability.PACKAGE_APPROVE_DELIVERY);
                    capabilities.add(UserCapability.PACKAGE_REPROCESS);
                    capabilities.add(UserCapability.PACKAGE_REMOVE_FROM_DASHBOARD);
                    capabilities.add(UserCapability.TECHNICAL_EXCEPTION_VIEW);
                    capabilities.add(UserCapability.TECHNICAL_RETRY);
                }
                case "BUSINESS_ADMIN" -> {
                    capabilities.add(UserCapability.TENANT_POLICY_MANAGE);
                    capabilities.add(UserCapability.REPORT_EXECUTIVE_VIEW);
                }
                case "REPORT_VIEWER" -> {
                    capabilities.add(UserCapability.REPORT_EXECUTIVE_VIEW);
                    capabilities.add(UserCapability.REPORT_EXPORT);
                }
                case "TENANT_IT_ADMIN" -> {
                    capabilities.add(UserCapability.TENANT_INTEGRATION_MANAGE);
                    capabilities.add(UserCapability.TECHNICAL_EXCEPTION_VIEW);
                    capabilities.add(UserCapability.TECHNICAL_ADMIN_VIEW);
                    capabilities.add(UserCapability.TECHNICAL_RETRY);
                    capabilities.add(UserCapability.TECHNICAL_HOLD_MANAGE);
                    capabilities.add(UserCapability.AUDIT_VIEW);
                    capabilities.add(UserCapability.REPORT_EXECUTIVE_VIEW);
                    capabilities.add(UserCapability.REPORT_OPERATIONAL_VIEW);
                    capabilities.add(UserCapability.REPORT_EXPORT);
                }
                case "PLATFORM_OPERATIONS_ADMIN" -> {
                    capabilities.add(UserCapability.TENANT_INTEGRATION_MANAGE);
                    capabilities.add(UserCapability.TECHNICAL_EXCEPTION_VIEW);
                    capabilities.add(UserCapability.TECHNICAL_ADMIN_VIEW);
                    capabilities.add(UserCapability.TECHNICAL_RETRY);
                    capabilities.add(UserCapability.TECHNICAL_HOLD_MANAGE);
                    capabilities.add(UserCapability.AUDIT_VIEW);
                    capabilities.add(UserCapability.REPORT_EXECUTIVE_VIEW);
                    capabilities.add(UserCapability.REPORT_OPERATIONAL_VIEW);
                    capabilities.add(UserCapability.REPORT_EXPORT);
                    capabilities.add(UserCapability.PLATFORM_OPERATIONS);
                }
                default -> {
                    // USER and unknown role names receive no privileged capabilities.
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
