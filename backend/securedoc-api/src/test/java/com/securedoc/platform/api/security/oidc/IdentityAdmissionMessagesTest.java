package com.securedoc.platform.api.security.oidc;

import com.documentplatform.identity.admission.IdentityAdmissionException;
import com.documentplatform.identity.admission.IdentityAdmissionException.Reason;
import com.securedoc.platform.api.context.SecurityContextException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Phase 2 step A3: LexaVault owns the words. Every library reason maps to one LexaVault message, unchanged from 0.3.0. */
class IdentityAdmissionMessagesTest {

    private static final Map<Reason, String> TEXTS_BEFORE_0_4_0 = Map.ofEntries(
            Map.entry(Reason.NOT_ENABLED, "Shared-issuer tenant selection is not enabled"),
            Map.entry(Reason.NO_VERIFIED_MEMBERSHIP, "No verified ACTIVE tenant membership is available"),
            Map.entry(Reason.MEMBERSHIP_NOT_BACKED, "Tenant membership is not backed by ACTIVE identity connections"),
            Map.entry(Reason.AMBIGUOUS_SELECTION, "A unique authorized tenant context is required"),
            Map.entry(Reason.NOT_AUTHORIZED_FOR_ENTITY, "Authenticated identity is not authorized for the requested tenant"),
            Map.entry(Reason.INVALID_SELECTION, "Invalid selected tenant identifier"),
            Map.entry(Reason.NO_ROLE_FOR_ENTITY, "Authenticated identity has no tenant-authorized role"),
            Map.entry(Reason.UNKNOWN_ROLE, "Invalid tenant role configuration"),
            Map.entry(Reason.INVALID_ROLE_GRANT, "Invalid tenant-scoped role grant"),
            Map.entry(Reason.PLATFORM_ROLE_IN_ENTITY_GRANT, "Platform Operations cannot be granted through tenant IdP groups"),
            Map.entry(Reason.GROUPS_NOT_A_COLLECTION, "Trusted IdP group collection is required"),
            Map.entry(Reason.NO_ADMISSION_POLICY, "Tenant has no trusted group admission policy"),
            Map.entry(Reason.CONTEXT_UNAVAILABLE, "Verified OIDC tenant membership context is unavailable"),
            Map.entry(Reason.POLICY_UNAVAILABLE, "OIDC tenant admission policy or ACTIVE tenant bindings are unavailable"),
            Map.entry(Reason.MACHINE_BINDING_NOT_UNIQUE, "Machine identity has no unique ACTIVE tenant integration binding"));

    @Test
    void everyReasonHasLexaVaultsOwnUnchangedText() {
        for (Reason reason : Reason.values()) {
            assertEquals(TEXTS_BEFORE_0_4_0.get(reason), IdentityAdmissionMapping.message(reason), "text for " + reason);
        }
        assertEquals(Reason.values().length, new HashSet<>(TEXTS_BEFORE_0_4_0.values()).size(), "one text per reason");
    }

    @Test
    void aLibraryRefusalBecomesA403WithLexaVaultsWordsNotTheLibrarys() {
        SecurityContextException refused = assertThrows(SecurityContextException.class, () -> IdentityAdmissionMapping.refusingAsSecurityContext(() -> {
            throw new IdentityAdmissionException(Reason.AMBIGUOUS_SELECTION, "library wording");
        }));
        assertEquals("A unique authorized tenant context is required", refused.getMessage());
        Set<Reason> covered = new HashSet<>(Arrays.asList(Reason.values()));
        assertEquals(TEXTS_BEFORE_0_4_0.keySet(), covered);
    }
}
