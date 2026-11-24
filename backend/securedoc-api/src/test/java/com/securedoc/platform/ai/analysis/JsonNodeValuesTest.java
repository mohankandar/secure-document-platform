package com.securedoc.platform.ai.analysis;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Task 0056: a model's loosely typed values mean what they meant with Jackson 2. */
class JsonNodeValuesTest {

    private static final JsonMapper MAPPER = JsonMapper.builderWithJackson2Defaults().build();

    @Test
    void pageNumbersAndConfidence() {
        assertEquals(3, JsonNodeValues.asInt(json("3"), -1));
        assertEquals(3, JsonNodeValues.asInt(json("3.9"), -1));
        assertEquals(3, JsonNodeValues.asInt(json("\" 3 \""), -1));
        assertEquals(3, JsonNodeValues.asInt(json("\"3.7\""), -1));
        assertEquals(-1, JsonNodeValues.asInt(json("\"x\""), -1));
        assertEquals(1, JsonNodeValues.asInt(json("true"), -1));
        assertEquals((int) 12345678901L, JsonNodeValues.asInt(json("12345678901"), -1));
        assertEquals(-1, JsonNodeValues.asInt(json("null"), -1));
        assertEquals(-1, JsonNodeValues.asInt(json("[3]"), -1));
        assertEquals(-1, JsonNodeValues.asInt(json("{}").path("missing"), -1));
        assertEquals(0.5, JsonNodeValues.asDouble(json("\"0.5\""), -1));
        assertEquals(1.0, JsonNodeValues.asDouble(json("true"), -1));
        assertEquals(-1, JsonNodeValues.asDouble(json("\"high\""), -1));
        assertEquals(-1, JsonNodeValues.asDouble(json("{}"), -1));
    }

    @Test
    void texts() {
        assertEquals("12.5", JsonNodeValues.asText(json("12.50"), "d"));
        assertEquals("true", JsonNodeValues.asText(json("true"), "d"));
        assertEquals("", JsonNodeValues.asText(json("[\"x\"]"), "d"));
        assertEquals("d", JsonNodeValues.asText(json("null"), "d"));
        assertNull(JsonNodeValues.asText(json("{}").path("missing"), null));
    }

    @Test
    void anAnswerWithNoJsonValueIsRefused() {
        for (String answer : new String[]{"", "  ", "{", "123-45-6789"}) {
            IOException refused = assertThrows(IOException.class,
                    () -> SensitiveContentResponseParser.readTreeWithoutEcho(MAPPER, answer, "fixed"));
            assertEquals("fixed", refused.getMessage());
            assertNull(refused.getCause());
        }
    }

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }
}
