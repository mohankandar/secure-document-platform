package com.securedoc.platform.application.detection.service;

import com.securedoc.platform.domain.finding.DetectionPath;
import com.securedoc.platform.domain.finding.DetectorSource;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.finding.FindingOrigin;
import com.securedoc.platform.domain.finding.FindingPlacement;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0105: a candidate's placement is kept in a page batch's file. */
class PageBatchPlacement0105Test {

    @Test
    void thePlacementIsWrittenAndReadBack() throws Exception {
        var placed = FindingPlacement.automatic(FindingPlacement.Reader.FORM_FIELD, FindingPlacement.Method.AI_MODEL,
                FindingPlacement.How.MATCHED_WORDS);
        var a = new PageBatchFindings.Candidate(EntityType.SSN, FindingLocation.withBounds(1, 3, 14,
                FindingLocation.BoundingBox.of(0.1, 0.1, 0.2, 0.02)), 0.9, DetectorSource.of("ollama:x", "1"),
                FindingOrigin.of(DetectionPath.TEXT, List.of()), placed);
        var b = new PageBatchFindings.Candidate(EntityType.SSN, FindingLocation.unlocated(2), 0.5,
                DetectorSource.of("x", "1"), FindingOrigin.of(DetectionPath.TEXT, List.of()));
        var out = new ByteArrayOutputStream();
        PageBatchFindings.write(new DataOutputStream(out), new PageBatchFindings.Result("stamp", 1, List.of(a, b), List.of()));
        var read = PageBatchFindings.read(new DataInputStream(new ByteArrayInputStream(out.toByteArray())));
        assertEquals(placed, read.candidates().get(0).placement());
        assertNull(read.candidates().get(1).placement());
    }
}
