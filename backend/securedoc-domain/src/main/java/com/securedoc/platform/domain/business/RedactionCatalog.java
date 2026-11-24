package com.securedoc.platform.domain.business;

import com.securedoc.platform.domain.finding.EntityType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Task 0062 (R1): the redaction catalog, one entry per identifier kind. It is the only list of the kinds an entity's
 * policy can hold; the defaults, the locked items, what the AI is asked for and the styles allowed are read here.
 */
public final class RedactionCatalog {

    /** How a kind is found. */
    public enum DetectionMethod {
        /** The AI and the pattern detectors. */
        AI_AND_PATTERN,
        /** Pattern detectors only. */
        PATTERN
    }

    /**
     * @param onByDefault     on for a new entity
     * @param locked          always on, always removed in full, not switchable
     * @param lastFourAllowed the style "keep the last 4" can be chosen
     */
    public record Entry(EntityType kind, String name, DetectionMethod detection, RedactionStyle defaultStyle,
                        boolean onByDefault, boolean locked, boolean lastFourAllowed) {
        public Entry {
            if (locked && (lastFourAllowed || !onByDefault || defaultStyle != RedactionStyle.REMOVE_FULLY)) {
                throw new IllegalArgumentException("A locked item is on and always removed in full");
            }
            if (defaultStyle == RedactionStyle.KEEP_LAST_FOUR && !lastFourAllowed) {
                throw new IllegalArgumentException("The default style must be one the kind allows");
            }
        }
    }

    private static final Map<EntityType, Entry> ENTRIES = index(
            onByDefault(EntityType.SSN, "Social Security Number", true),
            onByDefault(EntityType.DATE_OF_BIRTH, "Date of birth", false),
            locked(EntityType.CREDIT_CARD_NUMBER, "Credit card number", DetectionMethod.AI_AND_PATTERN),
            onByDefault(EntityType.CREDIT_SCORE, "Credit score", false),
            optIn(EntityType.BANK_ROUTING_NUMBER, "Bank routing number"),
            optIn(EntityType.BANK_ACCOUNT_NUMBER, "Bank account number"),
            optIn(EntityType.ITIN, "ITIN"),
            optIn(EntityType.EIN, "EIN"),
            optIn(EntityType.DRIVERS_LICENSE_NUMBER, "Driver’s license number"),
            optIn(EntityType.PASSPORT_NUMBER, "Passport number"),
            locked(EntityType.CVV, "Card security code (CVV)", DetectionMethod.PATTERN),
            locked(EntityType.PIN, "PIN", DetectionMethod.PATTERN));

    private static final Set<EntityType> LOCKED = where(Entry::locked);
    private static final Set<EntityType> ON_BY_DEFAULT = where(Entry::onByDefault);
    private static final Set<EntityType> ASKED_OF_AI = where(e -> e.detection() == DetectionMethod.AI_AND_PATTERN);
    private static final Set<EntityType> LAST_FOUR = where(Entry::lastFourAllowed);

    private RedactionCatalog() {
    }

    /** In the order the screens list them. */
    public static List<Entry> entries() {
        return List.copyOf(ENTRIES.values());
    }

    public static Set<EntityType> kinds() {
        return ENTRIES.keySet();
    }

    public static Set<EntityType> lockedKinds() {
        return LOCKED;
    }

    public static Set<EntityType> onByDefault() {
        return ON_BY_DEFAULT;
    }

    public static Set<EntityType> askedOfAi() {
        return ASKED_OF_AI;
    }

    public static Set<EntityType> lastFourAllowed() {
        return LAST_FOUR;
    }

    public static boolean isLocked(EntityType kind) {
        return LOCKED.contains(kind);
    }

    private static Entry onByDefault(EntityType kind, String name, boolean lastFourAllowed) {
        return new Entry(kind, name, DetectionMethod.AI_AND_PATTERN, RedactionStyle.REMOVE_FULLY, true, false,
                lastFourAllowed);
    }

    private static Entry optIn(EntityType kind, String name) {
        return new Entry(kind, name, DetectionMethod.PATTERN, RedactionStyle.REMOVE_FULLY, false, false, true);
    }

    private static Entry locked(EntityType kind, String name, DetectionMethod detection) {
        return new Entry(kind, name, detection, RedactionStyle.REMOVE_FULLY, true, true, false);
    }

    private static Map<EntityType, Entry> index(Entry... all) {
        Map<EntityType, Entry> map = new LinkedHashMap<>();
        for (Entry entry : all) {
            if (map.put(entry.kind(), entry) != null) throw new IllegalStateException("One entry per kind");
        }
        return Collections.unmodifiableMap(map);
    }

    private static Set<EntityType> where(Predicate<Entry> test) {
        EnumSet<EntityType> kinds = EnumSet.noneOf(EntityType.class);
        ENTRIES.values().stream().filter(test).forEach(e -> kinds.add(e.kind()));
        return Collections.unmodifiableSet(kinds);
    }
}
