package com.securedoc.platform.domain.finding;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Confidence;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0105: a person's placing is recorded only when the box changes; placement names survive the batch files. */
class FindingPlacement0105Test {

    private static Finding finding(FindingLocation.BoundingBox box) {
        Finding f = Finding.create(TenantId.generate(), DocumentId.generate(), EntityType.DATE_OF_BIRTH,
                FindingLocation.withBounds(1, 0, 0, box), Confidence.of(0.75), DetectorSource.of("vision", "v2"),
                FindingOrigin.of(DetectionPath.VISION, java.util.List.of()));
        f.applyPlacement(FindingPlacement.automatic(FindingPlacement.Reader.VISION, FindingPlacement.Method.AI_MODEL,
                FindingPlacement.How.MODEL_BOX));
        return f;
    }

    @Test
    void theSameBoxSentWithAConfirmationIsNoPlacing() {
        Finding f = finding(FindingLocation.BoundingBox.of(0.1, 0.2, 0.3, 0.04));
        assertFalse(f.placeByPerson(FindingLocation.BoundingBox.of(0.1, 0.2, 0.3, 0.04), ActorId.generate(), Instant.now()));
        assertEquals(FindingPlacement.How.MODEL_BOX, f.placement().how());
        assertNull(f.placement().placedBy());
    }

    @Test
    void aMovedBoxIsAPersonsPlacingWithWhoAndWhenAndKeepsTheReader() {
        Finding f = finding(FindingLocation.BoundingBox.of(0.1, 0.2, 0.3, 0.04));
        ActorId person = ActorId.generate();
        Instant at = Instant.parse("2026-11-27T10:00:00Z");
        assertTrue(f.placeByPerson(FindingLocation.BoundingBox.of(0.12, 0.2, 0.3, 0.04), person, at));
        assertEquals(0.12, f.location().boundingBox().left(), 1e-9);
        assertEquals(FindingPlacement.How.PERSON, f.placement().how());
        assertEquals(FindingPlacement.Reader.VISION, f.placement().reader());
        assertEquals(person, f.placement().placedBy());
        assertEquals(at, f.placement().placedAt());
    }

    @Test
    void aFindingWithoutABoxIsPlacedByThePersonsBox() {
        Finding f = Finding.create(TenantId.generate(), DocumentId.generate(), EntityType.SSN, FindingLocation.unlocated(2),
                Confidence.of(0.5), DetectorSource.of("x", "1"));
        assertTrue(f.placeByPerson(FindingLocation.BoundingBox.of(0.1, 0.1, 0.2, 0.03), ActorId.generate(), Instant.now()));
        assertTrue(f.location().hasBoundingBox());
        assertEquals(FindingPlacement.How.PERSON, f.placement().how());
    }

    @Test
    void placementNamesTravelThroughTheBatchFilesAndUnknownNamesAreDropped() {
        var p = FindingPlacement.forPerson(FindingPlacement.Reader.OCR, FindingPlacement.Method.PATTERN,
                FindingPlacement.How.LABEL_FIELD_AREA, FindingPlacement.LABEL_WITHOUT_VALUE);
        assertEquals(p, FindingPlacement.decode(p.encode()));
        var auto = FindingPlacement.automatic(FindingPlacement.Reader.PDF_TEXT, FindingPlacement.Method.AI_MODEL,
                FindingPlacement.How.READER_WORDS);
        assertEquals(auto, FindingPlacement.decode(auto.encode()));
        assertNull(FindingPlacement.decode(""));
        assertNull(FindingPlacement.decode("SOMETHING|NEW|HERE|"));
    }
}
