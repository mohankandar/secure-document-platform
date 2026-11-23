package com.securedoc.benchmark.vision;

import com.securedoc.benchmark.score.Prediction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionClientTest {

    @Test
    void pixelBoxesAreNormalizedByTheImageSize() {
        var parsed = VisionClient.parse("{\"items\":[{\"type\":\"SSN\",\"box\":[100,50,300,70]}]}",
                VisionClient.BoxConvention.PIXELS, 1000, 500, 3, "m");
        assertFalse(parsed.failed());
        Prediction p = parsed.items().get(0);
        assertEquals(3, p.page());
        assertEquals("SSN", p.type());
        assertArrayEquals(new double[]{0.1, 0.1, 0.2, 0.04}, p.box(), 1e-9);
    }

    @Test
    void perMilleBoxesAreNormalizedByAThousand() {
        var parsed = VisionClient.parse("{\"items\":[{\"type\":\"signature\",\"box\":[500,900,250,950]}]}",
                VisionClient.BoxConvention.PER_MILLE, 1000, 500, 1, "m");
        Prediction p = parsed.items().get(0);
        assertEquals("SIGNATURE", p.type());
        assertArrayEquals(new double[]{0.25, 0.9, 0.25, 0.05}, p.box(), 1e-9);
    }

    @Test
    void onlyTypeAndBoxAreKeptAndUnknownTypesOrBadBoxesAreDropped() {
        String answer = "{\"items\":[{\"type\":\"SSN\",\"box\":[1,1,9,9],\"value\":\"123-45-6789\"},"
                + "{\"type\":\"PERSON_NAME\",\"box\":[1,1,9,9]},{\"type\":\"SSN\",\"box\":[1,1]},{\"type\":\"SSN\",\"box\":[5,5,5,9]}]}";
        var parsed = VisionClient.parse(answer, VisionClient.BoxConvention.PIXELS, 100, 100, 1, "m");
        assertEquals(1, parsed.items().size());
        assertFalse(parsed.items().get(0).toString().contains("6789"), "no value survives parsing");
    }

    @Test
    void textAroundTheJsonIsIgnoredAndNonJsonIsAFailure() {
        var ok = VisionClient.parse("Here you go:\n```json\n{\"items\": []}\n```", VisionClient.BoxConvention.PIXELS, 10, 10, 1, "m");
        assertFalse(ok.failed());
        assertTrue(ok.items().isEmpty());
        assertTrue(VisionClient.parse("no items", VisionClient.BoxConvention.PIXELS, 10, 10, 1, "m").failed());
        assertTrue(VisionClient.parse("{\"found\": 2}", VisionClient.BoxConvention.PIXELS, 10, 10, 1, "m").failed());
    }

    @Test
    void eachModelGetsItsOwnBoxConventionAndTheSamePrompt() {
        assertEquals(VisionClient.BoxConvention.PER_MILLE, VisionClient.BoxConvention.forModel("qwen3-vl:8b"));
        assertEquals(VisionClient.BoxConvention.PIXELS, VisionClient.BoxConvention.forModel("qwen2.5vl:7b"));
        assertEquals(VisionClient.BoxConvention.PIXELS, VisionClient.BoxConvention.forModel("llama3.2-vision:11b"));
        String a = VisionClient.prompt(VisionClient.BoxConvention.PIXELS, 1082, 1400);
        String b = VisionClient.prompt(VisionClient.BoxConvention.PER_MILLE, 1082, 1400);
        assertTrue(a.contains("1082 pixels wide and 1400 pixels high"));
        assertFalse(a.contains("{COORDINATES}"));
        List<String> sharedA = a.lines().filter(l -> !l.contains("box as")).toList();
        List<String> sharedB = b.lines().filter(l -> !l.contains("box as")).toList();
        assertEquals(sharedA, sharedB);
        assertEquals(64, VisionClient.promptSha256().length());
    }

    @Test
    void promptV2KeepsTheOutputContractAndHasItsOwnHash() {
        String v1 = VisionClient.template("v1");
        String v2 = VisionClient.template("v2");
        for (String line : List.of("- {COORDINATES}", "- If the page has none of these items, return {\"items\": []}.",
                "Answer with JSON only, in this form:", "{\"items\": [{\"type\": \"SSN\", \"box\": [x1, y1, x2, y2]}]}",
                "- Never write out, copy or describe any value. Return only the item type and its box.")) {
            assertTrue(v1.contains(line), "v1: " + line);
            assertTrue(v2.contains(line), "v2: " + line);
        }
        for (String type : com.securedoc.benchmark.truth.Truth.ITEM_TYPES) assertTrue(v2.contains("- " + type + ":"), type);
        assertTrue(v2.contains("bottom edge of a check"));
        assertFalse(v2.contains("\r"));
        assertEquals(64, VisionClient.promptSha256("v2").length());
        assertFalse(VisionClient.promptSha256("v1").equals(VisionClient.promptSha256("v2")));
        assertEquals(VisionClient.promptSha256(), VisionClient.promptSha256("v1"));
        assertEquals("vision-prompt-v2", VisionClient.promptName("v2"));
    }

    @Test
    void unknownPromptVersionsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> VisionClient.template("v99"));
        assertThrows(IllegalArgumentException.class, () -> VisionClient.template("../logback"));
        assertThrows(IllegalArgumentException.class, () -> new VisionClient(new Ollama("http://localhost:11434"), "v99", java.util.Map.of()));
    }

    @Test
    void unitBoxesAndPerModelOverrides() {
        var parsed = VisionClient.parse("{\"items\":[{\"type\":\"SSN\",\"box\":[0.1,0.2,0.3,0.25]}]}",
                VisionClient.BoxConvention.UNIT, 1000, 500, 1, "m");
        assertArrayEquals(new double[]{0.1, 0.2, 0.2, 0.05}, parsed.items().get(0).box(), 1e-9);
        var client = new VisionClient(new Ollama("http://localhost:11434"), "v2",
                java.util.Map.of("gemma3:12b", VisionClient.BoxConvention.PER_MILLE));
        assertEquals(VisionClient.BoxConvention.PER_MILLE, client.convention("gemma3:12b"));
        assertEquals(VisionClient.BoxConvention.PER_MILLE, client.convention("qwen3-vl:8b-instruct"));
        assertEquals(VisionClient.BoxConvention.PIXELS, client.convention("granite3.2-vision:2b"));
        assertTrue(VisionClient.prompt("v2", VisionClient.BoxConvention.UNIT, 10, 10).contains("fractions from 0 to 1"));
    }

    @Test
    void onlyALocalOllamaIsAccepted() {
        assertThrows(IllegalArgumentException.class, () -> new Ollama("https://example.com"));
        new Ollama("http://localhost:11434");
        new Ollama("http://127.0.0.1:11434");
    }
}
