package com.securedoc.platform.infrastructure.casefields;

import com.securedoc.platform.application.casefields.CaseFieldText;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

import java.util.ArrayList;
import java.util.List;

/**
 * Task 0061: a case field as it is sent: a text (or number), an address
 * ({@code {"street", "street2", "city", "state", "zip", "country"}}), or a list of these for a field that takes several.
 * Anything else becomes text the field's rules refuse. Nothing sent is echoed.
 */
public final class CaseFieldJson extends ValueDeserializer<String> {
    private static final int MAX_VALUES = 51;

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        return text(context.readTree(parser));
    }

    /** Null for JSON null. */
    public static String text(JsonNode sent) {
        if (sent == null || sent.isNull() || sent.isMissingNode()) return null;
        if (!sent.isArray()) return one(sent);
        List<String> values = new ArrayList<>();
        for (JsonNode value : sent) {
            // One more than a field may hold, so it is refused, and no more than that is read.
            if (values.size() == MAX_VALUES) break;
            String text = value.isNull() ? null : one(value);
            if (text != null) values.add(text);
        }
        return CaseFieldText.several(values);
    }

    private static String one(JsonNode value) {
        if (value.isObject()) {
            return CaseFieldText.address(part(value, "street"), part(value, "street2"), part(value, "city"),
                    part(value, "state"), part(value, "zip"), part(value, "country"));
        }
        return value.isString() ? value.stringValue() : value.toString();
    }

    private static String part(JsonNode address, String name) {
        JsonNode part = address.get(name);
        return part == null || part.isNull() ? "" : part.isString() ? part.stringValue() : part.toString();
    }
}
