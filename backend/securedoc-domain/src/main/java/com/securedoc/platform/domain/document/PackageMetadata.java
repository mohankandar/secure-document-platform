package com.securedoc.platform.domain.document;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Extensible metadata associated with a document package.
 * Immutable key-value store for classification, tags, and contextual information.
 */
public final class PackageMetadata {

    private final Map<String, String> entries;

    private PackageMetadata(Map<String, String> entries) {
        this.entries = Collections.unmodifiableMap(new HashMap<>(entries));
    }

    public static PackageMetadata empty() {
        return new PackageMetadata(Map.of());
    }

    public static PackageMetadata of(Map<String, String> entries) {
        Objects.requireNonNull(entries, "Metadata entries must not be null");
        return new PackageMetadata(entries);
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    public boolean containsKey(String key) {
        return entries.containsKey(key);
    }

    public Map<String, String> asMap() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Returns a new PackageMetadata with the given entry added (or replaced).
     */
    public PackageMetadata with(String key, String value) {
        Objects.requireNonNull(key, "Metadata key must not be null");
        Objects.requireNonNull(value, "Metadata value must not be null");
        Map<String, String> updated = new HashMap<>(entries);
        updated.put(key, value);
        return new PackageMetadata(updated);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PackageMetadata that = (PackageMetadata) o;
        return entries.equals(that.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return "PackageMetadata{" + entries + "}";
    }
}
