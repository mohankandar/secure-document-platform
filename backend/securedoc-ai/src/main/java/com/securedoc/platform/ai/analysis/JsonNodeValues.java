package com.securedoc.platform.ai.analysis;

import tools.jackson.core.io.NumberInput;
import tools.jackson.databind.JsonNode;

/**
 * Task 0056: reads a model's loosely typed values exactly as before Jackson 3 (a number sent as text, a fraction, true),
 * because Jackson 3's own {@code asInt(default)} and {@code asString(default)} take the default in some of these cases.
 */
final class JsonNodeValues {

    private JsonNodeValues() {
    }

    static int asInt(JsonNode node, int defaultValue) {
        if (node.isNumber()) return node.numberValue().intValue();
        if (node.isBoolean()) return node.booleanValue() ? 1 : 0;
        return node.isString() ? NumberInput.parseAsInt(node.stringValue(), defaultValue) : defaultValue;
    }

    static double asDouble(JsonNode node, double defaultValue) {
        if (node.isNumber()) return node.numberValue().doubleValue();
        if (node.isBoolean()) return node.booleanValue() ? 1.0 : 0.0;
        return node.isString() ? NumberInput.parseAsDouble(node.stringValue(), defaultValue, false) : defaultValue;
    }

    /** An array or object is an empty text; null and missing are the default. */
    static String asText(JsonNode node, String defaultValue) {
        return node.isContainer() ? "" : node.asString(defaultValue);
    }
}
