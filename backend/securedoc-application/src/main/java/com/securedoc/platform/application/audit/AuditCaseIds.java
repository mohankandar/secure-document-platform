package com.securedoc.platform.application.audit;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The case ID an audit record is about, worked out from what the record already holds, with no query (task 0016).
 * The case ID is the package id, the same "Case ID" the Cases page shows. It is never a business identifier such as
 * a loan number.
 *
 * <ul>
 *   <li>The resource is the case itself ({@code DocumentPackage}, or {@code IntakeBatch} on the record that
 *       creates it): its resource id.</li>
 *   <li>Otherwise the detail names the case: {@code package=<uuid>} or {@code packageId=<uuid>}.</li>
 *   <li>Otherwise none. Anything that is not a UUID is refused, not guessed.</li>
 * </ul>
 */
public final class AuditCaseIds {

    /** Resource types whose resource id is the package id. {@code IntakeBatch} is the case's creation record. */
    private static final Set<String> CASE_RESOURCE_TYPES = Set.of("DocumentPackage", "Package", "IntakeBatch");
    private static final Pattern DETAIL_PACKAGE = Pattern.compile(
            "\\bpackage(?:Id)?=([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?![0-9a-fA-F-])");

    private AuditCaseIds() {
    }

    public static String caseIdFor(String resourceType, String resourceId, String detail) {
        if (resourceType != null && CASE_RESOURCE_TYPES.contains(resourceType)) {
            return uuidOrNull(resourceId);
        }
        if (detail == null) return null;
        Matcher m = DETAIL_PACKAGE.matcher(detail);
        return m.find() ? uuidOrNull(m.group(1)) : null;
    }

    private static String uuidOrNull(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.length() != 36) return null;
        try {
            return UUID.fromString(v).toString().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
