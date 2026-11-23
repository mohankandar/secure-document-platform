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
    void onlyALocalOllamaIsAccepted() {
        assertThrows(IllegalArgumentException.class, () -> new Ollama("https://example.com"));
        new Ollama("http://localhost:11434");
        new Ollama("http://127.0.0.1:11434");
    }
}
