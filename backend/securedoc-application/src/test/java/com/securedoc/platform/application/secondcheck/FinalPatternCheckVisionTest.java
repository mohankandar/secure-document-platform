package com.securedoc.platform.application.secondcheck;

import com.securedoc.platform.application.port.out.PageImagePort;
import com.securedoc.platform.application.port.out.PageRouteRepository;
import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort;
import com.securedoc.platform.application.port.out.TextExtractionPort;
import com.securedoc.platform.application.port.out.VisionModelPort;
import com.securedoc.platform.domain.business.PageBatchSettings;
import com.securedoc.platform.domain.business.ReviewMode;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.business.VisionSettings;
import com.securedoc.platform.domain.finding.AiModel;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation.BoundingBox;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vision.PageRoute;
import com.securedoc.platform.domain.vision.VisionPageResult;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0103 (owner's run 2, long-60 pages 23 and 32): OCR misread two handwritten dates of birth, so neither detection
 * nor the pattern search of the finished file saw them, and they were delivered. The final check now also asks the
 * vision model about the pages that were pictures in the original. Kinds only; no value anywhere.
 */
class FinalPatternCheckVisionTest {

    private static final AiModel MODEL = new AiModel("vl:1", "second-check-vision-v1", "d".repeat(64));
    private static final BoundingBox DOB_PLACE = BoundingBox.of(0.1, 0.5, 0.3, 0.05);

    private final TenantId tenant = TenantId.generate();
    private final DocumentId document = DocumentId.generate();
    private final List<Integer> asked = new ArrayList<>();
    private VisionPageResult answer = VisionPageResult.read(1, MODEL,
            List.of(new VisionPageResult.Item(VisionPageResult.Kind.DATE_OF_BIRTH, DOB_PLACE)), false);
    private boolean dobCovered;
    private VisionModelPort model;

    @Test
    void aDateOfBirthOcrMisreadOnAPicturePageHoldsTheFile() {
        FinalPatternCheck.Result result = check(policy(true));
        assertEquals(List.of(1), asked, "only the page that was a picture in the original is read again");
        assertEquals(1, result.pagesToCheck().size());
        Finding held = result.pagesToCheck().get(0);
        assertEquals(EntityType.UNREAD_PAGE, held.entityType());
        assertEquals(1, held.location().pageNumber());
        assertEquals(List.of("DOB"), FinalPatternCheck.kindsOf(held));
        assertTrue(result.auditDetail().contains("p1:DOB"), result.auditDetail());
    }

    @Test
    void whatTheModelPointsAtUnderACoverIsNoHit() {
        dobCovered = true;
        assertTrue(check(policy(true)).pagesToCheck().isEmpty());
    }

    @Test
    void aModelThatCannotBeAskedHoldsThePageForAPerson() {
        answer = VisionPageResult.notRead(1, "LV-AI-004", "MODEL_NOT_INSTALLED");
        FinalPatternCheck.Result result = check(policy(true));
        assertEquals(List.of("UNREAD"), FinalPatternCheck.kindsOf(result.pagesToCheck().get(0)));
    }

    @Test
    void anAnswerThatCantBeUsedIsAskedOnceMore() {
        // The owner-files run: on handwritten pages the model's first answer was now and then unusable.
        VisionPageResult good = VisionPageResult.read(1, MODEL, List.of(), false);
        answer = VisionPageResult.notRead(1, "LV-AI-005", "ANSWER_NOT_READABLE");
        model = (image, page) -> {
            asked.add(page);
            return asked.size() == 1 ? answer : good;
        };
        assertTrue(check(policy(true)).pagesToCheck().isEmpty(), "the second answer is used");
        assertEquals(List.of(1, 1), asked);
    }

    @Test
    void withVisionSwitchedOffTheModelIsNotAsked() {
        assertTrue(check(policy(false)).pagesToCheck().isEmpty());
        assertEquals(List.of(), asked);
    }

    // ------------------------------------------------------------------------------------------------- wiring

    private FinalPatternCheck.Result check(TenantRedactionPolicy policy) {
        TextExtractionPort reading = (content, mime) -> new TextExtractionPort.TextExtractionOutput(List.of(
                new TextExtractionPort.PageText(1, 612, 792, "Handwritten note\nSSN [covered]\nDOB 67/09/1990", List.of(),
                        TextExtractionPort.ExtractionSource.OCR, 0.88),
                new TextExtractionPort.PageText(2, 612, 792, "Typed page without values", List.of(),
                        TextExtractionPort.ExtractionSource.NATIVE_PDF_TEXT, 1.0)), 40, true);
        SensitiveDataDetectionPort nothing = pages -> new SensitiveDataDetectionPort.DetectionOutput(List.of());
        PageRouteRepository routes = new PageRouteRepository() {
            @Override public void save(TenantId t, DocumentId d, List<PageRoute> r) { }
            @Override public List<PageRoute> findByDocument(TenantId t, DocumentId d) {
                // Page 1 went the fast way (OCR confidence 0.88) but was a picture; page 2 had its own text.
                return List.of(new PageRoute(1, PageRoute.Path.FAST, Set.of(), 0.88, List.of()),
                        new PageRoute(2, PageRoute.Path.FAST, Set.of(), null, List.of()));
            }
        };
        PageImagePort images = (pdf, page, side) -> new PageImagePort.PageImage(png(), 200, 200);
        VisionModelPort asking = model != null ? model : (image, page) -> {
            asked.add(page);
            return answer;
        };
        return new FinalPatternCheck(reading, nothing).withVision(routes, images, asking)
                .run(tenant, document, policy, () -> InputStream.nullInputStream(), List.of(), List.of(), List.of());
    }

    /** A white page; with the date covered, a black box where the model points. */
    private byte[] png() {
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 200, 200);
        if (dobCovered) {
            g.setColor(Color.BLACK);
            g.fillRect(20, 100, 60, 10);
        }
        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private TenantRedactionPolicy policy(boolean vision) {
        return new TenantRedactionPolicy(tenant, EnumSet.of(EntityType.SSN, EntityType.DATE_OF_BIRTH), 0.80,
                ReviewMode.UNCERTAIN_ONLY, true, Instant.now(), true, PageBatchSettings.DEFAULTS, Set.of(), Set.of(),
                true, new VisionSettings(vision, 0.85));
    }
}
