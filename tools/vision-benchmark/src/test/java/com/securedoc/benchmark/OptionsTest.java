package com.securedoc.benchmark;

import com.securedoc.benchmark.vision.VisionClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionsTest {

    @Test
    void boxOverridesAreParsedPerModelAndBadOnesRefused() {
        Map<String, VisionClient.BoxConvention> m = BenchmarkMain.boxOverrides("gemma3:12b=PER_MILLE, granite3.2-vision:2b=UNIT");
        assertEquals(VisionClient.BoxConvention.PER_MILLE, m.get("gemma3:12b"));
        assertEquals(VisionClient.BoxConvention.UNIT, m.get("granite3.2-vision:2b"));
        assertTrue(BenchmarkMain.boxOverrides(null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> BenchmarkMain.boxOverrides("gemma3:12b"));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkMain.boxOverrides("gemma3:12b=INCHES"));
    }
}
