package com.securedoc.platform.application.detection.service;

import com.securedoc.platform.application.detection.service.PageBatchFindings.Candidate;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.business.RedactionCatalog;
import com.securedoc.platform.domain.business.ReviewMode;
import com.securedoc.platform.domain.finding.AiModel;
import com.securedoc.platform.domain.finding.DetectionPath;
import com.securedoc.platform.domain.finding.DetectorSource;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.finding.FindingLocation.BoundingBox;
import com.securedoc.platform.domain.finding.FindingOrigin;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vision.VisionPageResult;
import com.securedoc.platform.domain.vision.VisionPageResult.Kind;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0064: vision findings, and their merging with the text path's by place. No value anywhere. */
class VisionFindingsTest {
    private static final AiModel VISION = new AiModel("vl:1", "vision-prompt-v2", "d".repeat(64));
    private static final AiModel TEXT = new AiModel("text:1", "text-1", "e".repeat(64));

    @Test
    void theSamePlaceIsOneFindingThatCoversBothBoxes() {
        Candidate text = text(EntityType.SSN, box(0.10, 0.20, 0.20, 0.02), 0.82);
        Candidate vision = vision(EntityType.SSN, box(0.11, 0.195, 0.22, 0.03));
        List<Candidate> merged = VisionFindings.merge(List.of(text), List.of(vision));
        assertEquals(1, merged.size());
        Candidate one = merged.get(0);
        BoundingBox b = one.location().boundingBox();
        assertTrue(b.left() <= 0.10 && b.top() <= 0.195 && b.left() + b.width() >= 0.329
                && b.top() + b.height() >= 0.2249, "the box covers both");
        assertEquals(5, one.location().startOffset(), "the text place is kept (the final check reads the value there)");
        assertEquals(0.87, one.confidence(), 1e-9, "two independent paths agree");
        assertEquals(Set.of(DetectionPath.TEXT, DetectionPath.VISION), one.origin().paths());
        assertEquals(List.of(TEXT, VISION), one.origin().models());
    }

    @Test
    void differentPlacesPagesAndKindsStayTheirOwnFindings() {
        Candidate text = text(EntityType.SSN, box(0.10, 0.20, 0.20, 0.02), 0.82);
        assertEquals(2, VisionFindings.merge(List.of(text),
                List.of(vision(EntityType.SSN, box(0.10, 0.40, 0.20, 0.02)))).size());
        assertEquals(2, VisionFindings.merge(List.of(text),
                List.of(vision(EntityType.DATE_OF_BIRTH, box(0.28, 0.20, 0.20, 0.02)))).size(), "touching, other kind");
        // Another kind at the very same place is one box: the text path's kind is kept.
        List<Candidate> same = VisionFindings.merge(List.of(text),
                List.of(vision(EntityType.DATE_OF_BIRTH, box(0.10, 0.20, 0.20, 0.02))));
        assertEquals(1, same.size());
        assertEquals(EntityType.SSN, same.get(0).type());
        assertEquals(0.82, same.get(0).confidence(), 1e-9, "another kind is not agreement");
    }

    @Test
    void aBoxOverHalfThePageNeverWidensAFindingThatIsRedactedWithoutAPerson() {
        Candidate text = text(EntityType.SSN, box(0.10, 0.20, 0.20, 0.02), 0.99);
        List<Candidate> merged = VisionFindings.merge(List.of(text),
                List.of(vision(EntityType.SSN, box(0.0, 0.0, 1.0, 0.5))));
        assertEquals(2, merged.size());
        assertEquals(text, merged.get(0));
    }

    @Test
    void aFindingWithoutAPlaceIsNeverMergedAway() {
        Candidate unlocated = new Candidate(EntityType.SSN, FindingLocation.unlocated(1), 0.6,
                new DetectorSource("ai", "1"), FindingOrigin.NOT_RECORDED);
        assertEquals(2, VisionFindings.merge(List.of(unlocated),
                List.of(vision(EntityType.SSN, box(0.1, 0.1, 0.1, 0.1)))).size());
    }

    @Test
    void onlyTheEntitysKindsAreKeptAndAVisionOnlyFindingWaitsForAPersonAtTheDefaultThreshold() {
        VisionPageResult result = VisionPageResult.read(3, VISION, List.of(
                new VisionPageResult.Item(Kind.SSN, box(0.1, 0.1, 0.1, 0.02)),
                new VisionPageResult.Item(Kind.SIGNATURE, box(0.1, 0.8, 0.2, 0.05)),
                new VisionPageResult.Item(Kind.FACE_PHOTO, box(0.6, 0.1, 0.2, 0.2))), false);
        TenantRedactionPolicy defaults = TenantRedactionPolicy.defaults(TenantId.generate());
        List<Candidate> found = VisionFindings.of(result, defaults);
        assertEquals(List.of(EntityType.SSN), found.stream().map(Candidate::type).toList());
        assertTrue(found.get(0).confidence() < defaults.autoRedactThreshold());
        assertTrue(found.get(0).location().boundingBox().width() > 0.1, "a margin around the value");
        assertEquals(3, VisionFindings.of(result, policy(EntityType.SSN, EntityType.SIGNATURE, EntityType.FACE_PHOTO))
                .size());
    }

    @Test
    void thePromptsKindsBecomeTheEntitysKinds() {
        assertEquals(EntityType.BANK_ACCOUNT_NUMBER,
                VisionFindings.type(Kind.BANK_ACCOUNT, Set.of(EntityType.BANK_ACCOUNT_NUMBER)));
        assertEquals(EntityType.BANK_ROUTING_NUMBER,
                VisionFindings.type(Kind.ROUTING_NUMBER, Set.of(EntityType.BANK_ROUTING_NUMBER)));
        assertEquals(EntityType.ITIN, VisionFindings.type(Kind.SSN, Set.of(EntityType.ITIN)));
        assertEquals(EntityType.PASSPORT_NUMBER,
                VisionFindings.type(Kind.GOVERNMENT_ID, Set.of(EntityType.PASSPORT_NUMBER)));
        assertEquals(EntityType.DRIVERS_LICENSE_NUMBER, VisionFindings.type(Kind.GOVERNMENT_ID,
                Set.of(EntityType.PASSPORT_NUMBER, EntityType.DRIVERS_LICENSE_NUMBER)));
        for (Kind kind : Kind.values()) assertNull(VisionFindings.type(kind, Set.of()), kind.name());
    }

    @Test
    void aPageThatWasNotReadOrNotFullyPlacedGoesToAPerson() {
        TenantRedactionPolicy policy = TenantRedactionPolicy.defaults(TenantId.generate());
        for (VisionPageResult result : List.of(VisionPageResult.notRead(2, "LV-AI-008", "MODEL_DIGEST_MISMATCH"),
                VisionPageResult.notRead(2, null, "VISION_OFF"), VisionPageResult.read(2, VISION, List.of(), true))) {
            List<Candidate> found = VisionFindings.of(result, policy);
            assertEquals(1, found.size());
            assertEquals(EntityType.UNREAD_PAGE, found.get(0).type());
            assertTrue(found.get(0).unlocated(), "no place: it can never be redacted without a decision");
            assertEquals(Set.of(DetectionPath.PAGE_CHECK), found.get(0).origin().paths());
        }
        assertTrue(VisionFindings.of(VisionPageResult.read(2, VISION, List.of(), false), policy).isEmpty());
    }

    @Test
    void aBoxAtTheEdgeOfThePageStaysOnIt() {
        VisionPageResult result = VisionPageResult.read(1, VISION,
                List.of(new VisionPageResult.Item(Kind.SSN, box(0.9, 0.98, 0.1, 0.02))), false);
        BoundingBox b = VisionFindings.of(result, policy(EntityType.SSN)).get(0).location().boundingBox();
        assertTrue(b.left() + b.width() <= 1.0 && b.top() + b.height() <= 1.0);
    }

    @Test
    void theVisionFileAndTheBatchFileComeBackAsTheyWere() throws Exception {
        for (VisionPageResult result : List.of(VisionPageResult.notRead(7, null, "VISION_OFF"),
                VisionPageResult.notRead(7, "LV-AI-004", "MODEL_NOT_INSTALLED"),
                VisionPageResult.read(7, VISION, List.of(new VisionPageResult.Item(Kind.SIGNATURE,
                        box(0.1, 0.2, 0.3, 0.4))), true),
                VisionPageResult.read(8, VISION, List.of(new VisionPageResult.Item(Kind.ROUTING_NUMBER,
                        box(0.1, 0.2, 0.3, 0.4), true)), false))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            VisionFindings.write(new DataOutputStream(out), result);
            assertEquals(result, VisionFindings.read(new DataInputStream(new ByteArrayInputStream(out.toByteArray()))));
        }
        PageBatchFindings.Result batch = new PageBatchFindings.Result("SSN", 1, List.of(
                text(EntityType.SSN, box(0.1, 0.2, 0.3, 0.4), 0.9)), List.of(TEXT));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PageBatchFindings.write(new DataOutputStream(out), batch);
        assertEquals(batch, PageBatchFindings.read(new DataInputStream(new ByteArrayInputStream(out.toByteArray()))));
    }

    @Test
    void modelTextIsSafeToStoreAndReadBack() {
        AiModel odd = AiModel.of("a b;c|d", null, " ");
        assertEquals("a_b_c_d|unknown|" + AiModel.NO_DIGEST, odd.encode());
        assertEquals(odd, AiModel.decode(odd.encode()));
        assertThrows(IllegalArgumentException.class, () -> new AiModel("a;b", "1", "d"));
        FindingOrigin both = FindingOrigin.of(DetectionPath.VISION, List.of(VISION))
                .with(FindingOrigin.of(DetectionPath.TEXT, List.of(TEXT, VISION)));
        assertEquals(both, FindingOrigin.decode(both.encodePaths(), both.encodeModels()));
        assertEquals("TEXT+VISION", both.encodePaths());
        assertEquals(FindingOrigin.NOT_RECORDED, FindingOrigin.decode(null, null));
    }

    @Test
    void theNewKindsAreOffByDefaultAndAnUnreadPageIsNeverAKindToSwitchOnOrOff() {
        assertFalse(TenantRedactionPolicy.defaults(TenantId.generate()).isEnabled(EntityType.SIGNATURE));
        assertTrue(RedactionCatalog.kinds().containsAll(Set.of(EntityType.SIGNATURE, EntityType.FACE_PHOTO)));
        assertFalse(RedactionCatalog.kinds().contains(EntityType.UNREAD_PAGE));
    }

    /**
     * Task 0103 (owner's run 2, long-60 pages 23 and 32): the SSN of a handwritten note was found and the date of birth
     * next to it was not. One value of a person's group without the other sends the page to a person.
     */
    @Test
    void aVisionPageWithAnSsnAndNoDateOfBirthGoesToAPerson() {
        Set<EntityType> both = EnumSet.of(EntityType.SSN, EntityType.DATE_OF_BIRTH);
        Candidate ssn = vision(EntityType.SSN, box(0.1, 0.2, 0.27, 0.03));
        Candidate dob = vision(EntityType.DATE_OF_BIRTH, box(0.1, 0.29, 0.25, 0.03));

        Candidate held = VisionFindings.groupIncomplete(1, List.of(), List.of(ssn), both, VISION);
        assertEquals(EntityType.UNREAD_PAGE, held.type());
        assertEquals(1, held.location().pageNumber());
        assertFalse(held.location().hasBoundingBox(), "a page for a person, never redacted or delivered by itself");
        assertEquals("GROUP_INCOMPLETE", held.source().version());

        assertNull(VisionFindings.groupIncomplete(1, List.of(), List.of(ssn, dob), both, VISION), "the group is whole");
        assertNull(VisionFindings.groupIncomplete(1, List.of(text(EntityType.DATE_OF_BIRTH, box(0.1, 0.29, 0.25, 0.03),
                0.9)), List.of(ssn), both, VISION), "the text path found the date");
        assertNull(VisionFindings.groupIncomplete(1, List.of(), List.of(), both, VISION), "nothing of the group");
        assertNull(VisionFindings.groupIncomplete(1, List.of(), List.of(ssn), EnumSet.of(EntityType.SSN), VISION),
                "the entity does not redact dates of birth");
        assertNull(VisionFindings.groupIncomplete(2, List.of(), List.of(ssn), both, VISION), "another page");
    }

    private static TenantRedactionPolicy policy(EntityType first, EntityType... more) {
        return new TenantRedactionPolicy(TenantId.generate(), EnumSet.of(first, more), 0.8, ReviewMode.UNCERTAIN_ONLY,
                true, Instant.now());
    }

    private static Candidate text(EntityType type, BoundingBox box, double confidence) {
        return new Candidate(type, FindingLocation.withBounds(1, 5, 16, box), confidence,
                new DetectorSource("rules+ai", "1"), FindingOrigin.of(DetectionPath.TEXT, List.of(TEXT)));
    }

    private static Candidate vision(EntityType type, BoundingBox box) {
        return new Candidate(type, FindingLocation.withBounds(1, 0, 0, box), VisionFindings.VISION_CONFIDENCE,
                new DetectorSource("vision", "vision-prompt-v2"), FindingOrigin.of(DetectionPath.VISION, List.of(VISION)));
    }

    private static BoundingBox box(double l, double t, double w, double h) {
        return BoundingBox.of(l, t, w, h);
    }
}
