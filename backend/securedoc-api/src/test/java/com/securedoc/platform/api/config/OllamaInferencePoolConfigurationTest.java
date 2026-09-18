package com.securedoc.platform.api.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OllamaInferencePoolConfigurationTest {

    @Test
    void parsesStableIdsAndAutomaticIds() {
        var nodes = OllamaInferencePoolConfiguration.parse(
                "gpu-east=http://ai-east:11434,https://ai-west:11434");

        assertEquals(2, nodes.size());
        assertEquals("gpu-east", nodes.get(0).id());
        assertEquals("http://ai-east:11434", nodes.get(0).baseUrl());
        assertEquals("ai-2", nodes.get(1).id());
        assertEquals("https://ai-west:11434", nodes.get(1).baseUrl());
    }

    @Test
    void rejectsDuplicateEndpoints() {
        assertThrows(IllegalArgumentException.class, () -> OllamaInferencePoolConfiguration.parse(
                "ai-1=http://localhost:11434,ai-2=http://localhost:11434"));
    }

    @Test
    void rejectsLoopbackAliasesAsDuplicateCapacity() {
        assertThrows(IllegalArgumentException.class, () -> OllamaInferencePoolConfiguration.parse(
                "ai-1=http://127.0.0.1:11434,ai-2=http://localhost:11434"));
    }
}
