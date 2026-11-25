package com.securedoc.platform.application.casefields;

import com.securedoc.platform.domain.business.RedactionCatalog;
import com.securedoc.platform.domain.finding.EntityType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Task 0061: a case field and a redaction item are mutually exclusive per entity: what is removed from an entity's
 * documents is not kept beside them as a field. A field is the item its name or key says it is (words, not content).
 * Locked items and the Social Security Number are never a case field.
 */
public final class CaseFieldRedactionRule {
    private static final Map<EntityType, Pattern> NAMES = names();

    /** Never a case field, whatever the entity's policy: the locked items of the catalog, and the SSN. */
    private static final Set<EntityType> NEVER = never();

    private CaseFieldRedactionRule() {
    }

    /** The redaction items this field's name or key stands for. */
    public static Set<EntityType> itemsOf(String label, String key) {
        EnumSet<EntityType> items = EnumSet.noneOf(EntityType.class);
        for (String text : new String[] {words(label), words(key)}) {
            NAMES.forEach((kind, name) -> {
                if (name.matcher(text).find()) items.add(kind);
            });
        }
        return items;
    }

    public static boolean never(EntityType kind) {
        return NEVER.contains(kind);
    }

    /** The items this field is that are removed from documents: the ones never allowed, and the enabled ones. */
    public static List<EntityType> conflicts(String label, String key, Collection<EntityType> enabled) {
        List<EntityType> found = new ArrayList<>();
        for (EntityType kind : itemsOf(label, key)) {
            if (never(kind) || enabled.contains(kind)) found.add(kind);
        }
        return found;
    }

    public static String nameOf(EntityType kind) {
        return RedactionCatalog.entries().stream().filter(e -> e.kind() == kind).map(RedactionCatalog.Entry::name)
                .findFirst().orElse(kind.name());
    }

    /** Lower case words, single spaces: {@code borrowerSSN}, {@code borrower_ssn} and "Borrower SSN" read the same. */
    private static String words(String text) {
        if (text == null) return "";
        return text.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).replaceAll("['’.]", "")
                .replaceAll("[^a-z0-9#]+", " ").trim();
    }

    private static Set<EntityType> never() {
        EnumSet<EntityType> never = EnumSet.copyOf(RedactionCatalog.lockedKinds());
        never.add(EntityType.SSN);
        return never;
    }

    private static Map<EntityType, Pattern> names() {
        String number = "( ?(no|num|number|numbers|#))";
        Map<EntityType, Pattern> m = new LinkedHashMap<>();
        m.put(EntityType.SSN, word("ssns?" + number + "?|ss" + number + "|social security" + number + "?|soc(ial)? sec"));
        m.put(EntityType.CREDIT_CARD_NUMBER, word("(credit|debit|payment|bank|visa|mastercard|amex) ?cards?" + number
                + "?|card ?(no|num|number|numbers|#)|pan|cc" + number));
        m.put(EntityType.CVV, word("cvv2?|cvc2?|csc|card (security|verification) (code|value|number)|security code"));
        m.put(EntityType.PIN, word("pins?" + number + "?|pin code|personal identification number"));
        m.put(EntityType.DATE_OF_BIRTH, word("dob|date of birth|birth ?dates?|birthdays?"));
        m.put(EntityType.CREDIT_SCORE, word("credit ?scores?|fico( score)?"));
        m.put(EntityType.BANK_ROUTING_NUMBER, word("routing" + number + "?|aba" + number + "?|rtn"));
        m.put(EntityType.BANK_ACCOUNT_NUMBER, word("(bank|checking|savings|deposit) (account|acct)" + number
                + "?|iban|(account|acct)" + number));
        m.put(EntityType.ITIN, word("itins?|individual taxpayer (id|identification)" + number + "?"));
        m.put(EntityType.EIN, word("eins?|fein|employer (id|identification)" + number + "?|tax ?(payer)? ?(id|identification)"
                + number + "?|tin"));
        m.put(EntityType.DRIVERS_LICENSE_NUMBER, word("driver ?s? licen[sc]es?" + number + "?|driving licen[sc]e|dl" + number));
        m.put(EntityType.PASSPORT_NUMBER, word("passports?" + number + "?"));
        m.put(EntityType.PERSON_NAME, Pattern.compile("(^| )(((co ?)?borrower|customer|client|member|patient|person|employee|owner|applicant"
                + "|insured|spouse|contact|first|last|middle|full|given|family|legal|maiden) ?names?|surname)( |$)|^names?$"));
        m.put(EntityType.SIGNATURE, word("signatures?|initials"));
        m.put(EntityType.FACE_PHOTO, word("photos?"));
        return m;
    }

    private static Pattern word(String alternatives) {
        return Pattern.compile("(^| )(" + alternatives + ")( |$)");
    }
}
