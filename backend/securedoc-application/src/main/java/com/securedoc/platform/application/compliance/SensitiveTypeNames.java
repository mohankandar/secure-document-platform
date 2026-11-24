package com.securedoc.platform.application.compliance;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sensitive-type names in the words the app uses, ported once from the frontend's {@code studio/words.ts}
 * ({@code SENSITIVE_TYPES}, {@code SENSITIVE_ALIASES}, {@code sentenceCase}), so the PDF says
 * "Social Security Number" exactly as the screens do. Names of categories only, never a found value.
 */
public final class SensitiveTypeNames {

    /** words.ts {@code SENSITIVE_TYPES}, in its order. */
    private static final List<Map.Entry<String, String>> KNOWN = List.of(
            Map.entry("SSN", "Social Security Number"),
            Map.entry("DATE_OF_BIRTH", "Date of birth"),
            Map.entry("CREDIT_CARD_NUMBER", "Credit card number"),
            Map.entry("CREDIT_SCORE", "Credit score"),
            Map.entry("BANK_ROUTING_NUMBER", "Bank routing number"),
            Map.entry("BANK_ACCOUNT_NUMBER", "Bank account number"),
            Map.entry("ITIN", "ITIN"),
            Map.entry("EIN", "EIN"),
            Map.entry("DRIVERS_LICENSE_NUMBER", "Driver’s license number"),
            Map.entry("PASSPORT_NUMBER", "Passport number"),
            Map.entry("CVV", "Card security code (CVV)"),
            Map.entry("PIN", "PIN"));

    /** words.ts {@code SENSITIVE_ALIASES}. */
    private static final Map<String, String> ALIASES = Map.of(
            "US_SSN", "SSN", "SOCIAL_SECURITY_NUMBER", "SSN",
            "DOB", "DATE_OF_BIRTH", "BIRTH_DATE", "DATE_OF_BIRTH",
            "CARD_NUMBER", "CREDIT_CARD_NUMBER", "PAYMENT_CARD_NUMBER", "CREDIT_CARD_NUMBER");

    /** words.ts {@code ACRONYMS}. */
    private static final Set<String> ACRONYMS = Set.of("SSN", "ID", "EIN", "ITIN", "IBAN", "TIN", "VIN", "NPI",
            "ABA", "FICO", "PDF", "API", "URL");

    private SensitiveTypeNames() {
    }

    public static String name(String key) {
        if (key == null || key.isBlank()) return "Unknown type";
        String k = canonical(key);
        for (Map.Entry<String, String> known : KNOWN) {
            if (known.getKey().equals(k)) return known.getValue();
        }
        return sentenceCase(k);
    }

    /** Position in the words.ts list; unknown types after the known ones, by name. */
    public static Comparator<String> order() {
        return Comparator.<String>comparingInt(SensitiveTypeNames::rank).thenComparing(SensitiveTypeNames::name);
    }

    private static String canonical(String key) {
        String k = key.trim().toUpperCase(Locale.ROOT);
        return ALIASES.getOrDefault(k, k);
    }

    private static int rank(String key) {
        String k = key == null ? "" : canonical(key);
        for (int i = 0; i < KNOWN.size(); i++) {
            if (KNOWN.get(i).getKey().equals(k)) return i;
        }
        return KNOWN.size();
    }

    /** words.ts {@code sentenceCase}: "CREDIT_SCORE" becomes "Credit score"; acronyms are kept ("SSN"). */
    public static String sentenceCase(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder();
        for (String w : raw.trim().split("[_\\s]+")) {
            if (w.isEmpty()) continue;
            boolean first = out.isEmpty();
            if (!first) out.append(' ');
            if (ACRONYMS.contains(w.toUpperCase(Locale.ROOT))) {
                out.append(w.toUpperCase(Locale.ROOT));
            } else {
                String lower = w.toLowerCase(Locale.ROOT);
                out.append(first ? Character.toUpperCase(lower.charAt(0)) + lower.substring(1) : lower);
            }
        }
        return out.toString();
    }
}
