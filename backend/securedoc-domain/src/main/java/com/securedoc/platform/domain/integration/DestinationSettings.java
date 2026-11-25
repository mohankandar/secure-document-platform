package com.securedoc.platform.domain.integration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Task 0087: what the installation adds to a destination's published profile (the profile itself is a file IT gets from
 * the destination's owner). Everything about how the destination works is in that file; here is only what the
 * installation chooses. No secret: the credential is the connection's secret reference.
 *
 * @param profileFile        the profile file's name in the installation's profile folder
 * @param profileName        the name in that file when IT saved the connection (set by the server)
 * @param profileVersion     the version in that file when IT saved the connection (set by the server)
 * @param tokenAddress       where the token comes from (OAuth 2.0 client credentials)
 * @param clientId           the client ID (OAuth 2.0) or the user name (Basic)
 * @param clientSecretBasic  the client secret goes as HTTP Basic instead of in the form (OAuth 2.0)
 * @param sendAllFields      every case field is sent under its own key (names in {@code fieldMapping} win)
 * @param fieldMapping       the destination's field name to the case field's key
 * @param sensitiveFields    the keys of sensitive case fields that may be sent
 * @param caseLevel          what to do with a case level the profile has no place for
 * @param levelRefusalCodes  the destination's codes that mean "sensitivity level refused" ({@code ABC-*} is a prefix)
 * @param settings           the profile's own settings; missing ones take the profile's default
 */
public record DestinationSettings(String profileFile, String profileName, String profileVersion, String tokenAddress,
                                  String clientId, String scope, String audience, boolean clientSecretBasic,
                                  boolean sendAllFields, Map<String, String> fieldMapping, List<String> sensitiveFields,
                                  CaseLevel caseLevel, List<String> levelRefusalCodes, Map<String, String> settings) {

    public enum CaseLevel {
        /** A case above STANDARD is not sent (LV-DLV-003) when the profile has no place for the case's level. */
        REFUSE,
        /** Each document is sent with the higher of its own level and the case's. */
        ON_EACH_DOCUMENT
    }

    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}\\.json");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.:-]{1,40}\\*?");
    private static final int MAX_ENTRIES = 100;

    public DestinationSettings {
        profileFile = text(profileFile, 110, "Destination profile");
        if (profileFile == null || !FILE.matcher(profileFile).matches()) {
            throw new IllegalArgumentException("Destination profile must be the name of a .json file in the profile folder");
        }
        profileName = text(profileName, 100, "Destination profile name");
        profileVersion = text(profileVersion, 50, "Destination profile version");
        tokenAddress = text(tokenAddress, 1000, "Token address");
        clientId = text(clientId, 255, "Client ID");
        scope = text(scope, 1000, "Scope");
        audience = text(audience, 500, "Audience");
        Map<String, String> mapping = new LinkedHashMap<>();
        if (fieldMapping != null) {
            fieldMapping.forEach((name, key) -> {
                if (name == null || !NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("A destination's field name is 1-100 letters, digits, '_', '.' or '-'");
                }
                if (key == null || !NAME.matcher(key).matches()) throw new IllegalArgumentException("A case field key is not valid");
                mapping.put(name, key);
            });
        }
        fieldMapping = Collections.unmodifiableMap(mapping);
        for (String key : sensitiveFields == null ? List.<String>of() : sensitiveFields) {
            if (key == null || !NAME.matcher(key).matches()) throw new IllegalArgumentException("A case field key is not valid");
        }
        sensitiveFields = sensitiveFields == null ? List.of() : List.copyOf(new TreeSet<>(sensitiveFields));
        caseLevel = caseLevel == null ? CaseLevel.REFUSE : caseLevel;
        List<String> codes = new ArrayList<>();
        for (String code : levelRefusalCodes == null ? List.<String>of() : levelRefusalCodes) {
            if (code == null || !CODE.matcher(code).matches()) throw new IllegalArgumentException("A destination's refusal code is not valid");
            codes.add(code);
        }
        levelRefusalCodes = List.copyOf(codes);
        Map<String, String> chosen = new TreeMap<>();
        if (settings != null) {
            settings.forEach((name, value) -> {
                if (name == null || !NAME.matcher(name).matches() || value == null || value.length() > 500) {
                    throw new IllegalArgumentException("A profile setting is not valid");
                }
                chosen.put(name, value);
            });
        }
        settings = Collections.unmodifiableMap(chosen);
        if (fieldMapping.size() > MAX_ENTRIES || sensitiveFields.size() > MAX_ENTRIES || levelRefusalCodes.size() > MAX_ENTRIES
                || settings.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("A destination's lists hold at most " + MAX_ENTRIES + " entries");
        }
    }

    /** With the name and version found in the file. */
    public DestinationSettings withProfile(String name, String version) {
        return new DestinationSettings(profileFile, name, version, tokenAddress, clientId, scope, audience,
                clientSecretBasic, sendAllFields, fieldMapping, sensitiveFields, caseLevel, levelRefusalCodes, settings);
    }

    /** Whether the destination's own code is one IT listed as "sensitivity level refused". */
    public boolean refusesLevel(String destinationCode) {
        if (destinationCode == null) return false;
        return levelRefusalCodes.stream().anyMatch(c -> c.endsWith("*")
                ? destinationCode.startsWith(c.substring(0, c.length() - 1)) : destinationCode.equals(c));
    }

    private static String text(String value, int max, String what) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        if (v.length() > max || v.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw new IllegalArgumentException(what + " is at most " + max + " characters, without control characters");
        }
        return v;
    }
}
