package com.securedoc.platform.domain.finding;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Task 0064 (model governance BL7): the AI model that took part in a finding.
 *
 * @param name    the model's name, for example {@code qwen3-vl:8b-instruct}
 * @param version the version of the prompt it was asked with, for example {@code vision-prompt-v2}
 * @param digest  the model's digest as its server states it; {@link #NO_DIGEST} where the server states none
 */
public record AiModel(String name, String version, String digest) {
    public static final String NO_DIGEST = "not-stated";
    private static final int MAX = 128;
    /** What the parts may hold, so the stored text form can be read back and printed as it is. */
    private static final Pattern PART = Pattern.compile("[A-Za-z0-9._:/@+\\-]{1,128}");

    public AiModel {
        requirePart(name);
        requirePart(version);
        requirePart(digest);
    }

    private static void requirePart(String value) {
        Objects.requireNonNull(value, "AI model part");
        if (!PART.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid AI model name, version or digest");
        }
    }

    /** A server's or setting's text made safe for a part (any other character becomes {@code _}). */
    public static AiModel of(String name, String version, String digest) {
        return new AiModel(clean(name, "unknown"), clean(version, "unknown"), clean(digest, NO_DIGEST));
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String safe = value.trim().replaceAll("[^A-Za-z0-9._:/@+\\-]", "_");
        return safe.length() > MAX ? safe.substring(0, MAX) : safe;
    }

    /** {@code name|version|digest}. */
    public String encode() {
        return name + "|" + version + "|" + digest;
    }

    public static AiModel decode(String text) {
        String[] parts = text.split("\\|", -1);
        if (parts.length != 3) throw new IllegalArgumentException("Invalid AI model text");
        return new AiModel(parts[0], parts[1], parts[2]);
    }
}
