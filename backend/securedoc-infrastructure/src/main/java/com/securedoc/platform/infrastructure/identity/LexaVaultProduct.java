package com.securedoc.platform.infrastructure.identity;

import java.util.Map;
import java.util.Set;

/**
 * LexaVault as a product of the platform (task 0037): its product code in licences, login connections and grants, and
 * the role names a grant may carry ({@code product-roles.json} lists the same names).
 */
public final class LexaVaultProduct {

    /** The product code in licences, idn_login_connection and idn_group_grant. */
    public static final String CODE = "LEXAVAULT";

    /** The entity roles a grant may name exactly as stored: the canonical names. */
    public static final Set<String> ENTITY_ROLES = Set.of(
            "DOCUMENT_PROCESSOR", "DOCUMENT_SUPERVISOR", "BUSINESS_ADMIN", "REPORT_VIEWER", "TENANT_IT_ADMIN");

    /**
     * Today's identity-name aliases, also accepted exactly as written (upper case) in a grant and read as the role they
     * stand for. Any other spelling, any other case, and the platform role are refused.
     */
    public static final Map<String, String> ROLE_ALIASES = Map.of(
            "USER", "DOCUMENT_PROCESSOR",
            "REVIEWER", "DOCUMENT_PROCESSOR",
            "APPROVER", "DOCUMENT_SUPERVISOR");

    /** The role that reaches platform-wide screens; never granted through an entity's groups. */
    public static final String PLATFORM_ROLE = "PLATFORM_OPERATIONS_ADMIN";

    private LexaVaultProduct() {
    }

    /** The canonical role for a grant's stored role name, or null when the name is not accepted in a grant. */
    public static String grantRole(String stored) {
        if (stored == null) return null;
        if (ENTITY_ROLES.contains(stored)) return stored;
        return ROLE_ALIASES.get(stored);
    }
}
