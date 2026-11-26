package com.securedoc.platform.domain.business;

import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0062 (R1): the catalog, the packs, the locked items and the styles. */
class RedactionCatalogTest {

    private static final TenantId TENANT = TenantId.generate();

    @Test
    void theCatalogHasFifteenKindsAndTheRetiredOnesAreGone() {
        assertEquals(15, RedactionCatalog.entries().size());
        assertTrue(Arrays.stream(EntityType.values()).map(Enum::name)
                .noneMatch(Set.of("FINANCIAL_ACCOUNT", "GOVERNMENT_ID")::contains));
        assertEquals(EnumSet.of(EntityType.SSN, EntityType.DATE_OF_BIRTH, EntityType.CREDIT_CARD_NUMBER,
                EntityType.CREDIT_SCORE, EntityType.PERSON_NAME), RedactionCatalog.askedOfAi());
        assertEquals(Set.of(EntityType.PERSON_NAME), RedactionCatalog.alwaysReviewed());
        assertFalse(RedactionCatalog.onByDefault().contains(EntityType.PERSON_NAME), "off until the entity turns it on");
        assertTrue(RedactionCatalog.entries().stream().allMatch(e -> e.defaultStyle() == RedactionStyle.REMOVE_FULLY),
                "today's behaviour is the default style");
    }

    @Test
    void lockedItemsAreTheCardNumberTheCvvAndThePin() {
        Set<EntityType> locked = EnumSet.of(EntityType.CREDIT_CARD_NUMBER, EntityType.CVV, EntityType.PIN);
        assertEquals(locked, RedactionCatalog.lockedKinds());
        for (EntityType kind : locked) {
            assertFalse(RedactionCatalog.lastFourAllowed().contains(kind));
            assertThrows(IllegalArgumentException.class, () -> policy(Set.of(EntityType.SSN), Set.of(kind), true),
                    kind + " never keeps its last four");
        }
        assertTrue(policy(Set.of(EntityType.SSN), Set.of(), true).enabledEntityTypes().containsAll(locked),
                "on although not chosen");
        // Task 0099 (changed from 0062): a locked item is never delivered, also for an entity from before the catalog.
        assertEquals(EnumSet.of(EntityType.SSN, EntityType.CREDIT_CARD_NUMBER, EntityType.CVV, EntityType.PIN),
                EnumSet.copyOf(policy(Set.of(EntityType.SSN), Set.of(), false).enabledEntityTypes()),
                "an entity from before the catalog keeps its kinds, and the locked items are on");
        assertTrue(TenantRedactionPolicy.defaults(TENANT).enabledEntityTypes().containsAll(locked));
        assertEquals(EnumSet.of(EntityType.SSN, EntityType.DATE_OF_BIRTH, EntityType.CREDIT_CARD_NUMBER,
                        EntityType.CREDIT_SCORE, EntityType.CVV, EntityType.PIN),
                EnumSet.copyOf(TenantRedactionPolicy.defaultsBeforeCatalog(TENANT).enabledEntityTypes()));
    }

    @Test
    void onlyNumbersWithFourSpareDigitsCanKeepTheLastFour() {
        assertEquals(EnumSet.of(EntityType.SSN, EntityType.BANK_ROUTING_NUMBER, EntityType.BANK_ACCOUNT_NUMBER,
                EntityType.ITIN, EntityType.EIN, EntityType.DRIVERS_LICENSE_NUMBER, EntityType.PASSPORT_NUMBER),
                RedactionCatalog.lastFourAllowed());
        for (EntityType kind : RedactionCatalog.kinds()) {
            if (RedactionCatalog.lastFourAllowed().contains(kind)) {
                assertEquals(RedactionStyle.KEEP_LAST_FOUR, policy(Set.of(kind), Set.of(kind), true).styleOf(kind));
            }
            assertEquals(RedactionStyle.REMOVE_FULLY, policy(Set.of(EntityType.SSN), Set.of(), true).styleOf(kind));
        }
    }

    @Test
    void everyPackIsMadeOfCatalogKindsAndSaysWhatItDoesNotCover() {
        assertEquals(EnumSet.of(EntityType.CREDIT_CARD_NUMBER, EntityType.CVV, EntityType.PIN),
                EnumSet.copyOf(RegulationPack.PCI_DSS.kinds()));
        assertEquals(EnumSet.of(EntityType.SSN, EntityType.ITIN, EntityType.EIN, EntityType.DATE_OF_BIRTH,
                EntityType.BANK_ACCOUNT_NUMBER, EntityType.CREDIT_CARD_NUMBER),
                EnumSet.copyOf(RegulationPack.FRCP_5_2.kinds()));
        assertEquals(EnumSet.of(EntityType.SSN, EntityType.ITIN, EntityType.DATE_OF_BIRTH,
                EntityType.BANK_ACCOUNT_NUMBER, EntityType.CREDIT_CARD_NUMBER, EntityType.DRIVERS_LICENSE_NUMBER,
                EntityType.PASSPORT_NUMBER), EnumSet.copyOf(RegulationPack.HIPAA.kinds()));
        assertEquals(EnumSet.complementOf(notIn(EntityType.SIGNATURE, EntityType.FACE_PHOTO, EntityType.PERSON_NAME)), EnumSet.copyOf(RegulationPack.GLBA.kinds()));
        assertEquals(Set.of(EntityType.PERSON_NAME), RegulationPack.FRCP_5_2.suggested());
        for (RegulationPack pack : RegulationPack.values()) {
            assertTrue(pack.suggested().stream().noneMatch(pack.kinds()::contains), "a suggestion is not turned on");
            assertTrue(RedactionCatalog.kinds().containsAll(pack.kinds()), pack.name());
            assertFalse(pack.covers().isBlank());
            assertTrue(pack.notCovered().contains("not found"), pack.name());
        }
    }

    /** Everything that is not a catalog kind, and these kinds. */
    private static EnumSet<EntityType> notIn(EntityType... kinds) {
        EnumSet<EntityType> out = EnumSet.allOf(EntityType.class);
        out.removeAll(RedactionCatalog.kinds());
        out.addAll(java.util.List.of(kinds));
        return out;
    }

    private static TenantRedactionPolicy policy(Set<EntityType> enabled, Set<EntityType> lastFour, boolean locked) {
        return new TenantRedactionPolicy(TENANT, enabled, 0.8, ReviewMode.UNCERTAIN_ONLY, true, Instant.now(), true,
                PageBatchSettings.DEFAULTS, Set.of(), lastFour, locked);
    }
}
