package com.securedoc.platform.application.vision;

import com.securedoc.platform.application.port.out.PageImagePort.CheckLine;
import com.securedoc.platform.domain.finding.AiModel;
import com.securedoc.platform.domain.vision.VisionPageResult;
import com.securedoc.platform.domain.vision.VisionPageResult.Item;
import com.securedoc.platform.domain.vision.VisionPageResult.Kind;
import com.securedoc.platform.domain.finding.FindingLocation.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Task 0092: the two numbers of a check's line stand for what the model said there, and for nothing else. */
class CheckLineItemsTest {
    private static final AiModel MODEL = AiModel.of("m", "1", null);
    private static final CheckLine LINE = new CheckLine(BoundingBox.of(0.23, 0.39, 0.12, 0.02), BoundingBox.of(0.38, 0.39, 0.13, 0.02));

    @Test
    void swappedAndShiftedNumbersAreSetRightAndTheRestStays() {
        Item accountCalledRouting = new Item(Kind.ROUTING_NUMBER, BoundingBox.of(0.385, 0.391, 0.12, 0.016));
        Item checkNumberCalledAccount = new Item(Kind.BANK_ACCOUNT, BoundingBox.of(0.54, 0.39, 0.06, 0.02));
        Item wide = new Item(Kind.BANK_ACCOUNT, BoundingBox.of(0.2, 0.39, 0.4, 0.02));
        Item signature = new Item(Kind.SIGNATURE, BoundingBox.of(0.385, 0.391, 0.12, 0.016));
        var out = VisionPageReader.withCheckLines(VisionPageResult.read(3, MODEL, List.of(accountCalledRouting,
                checkNumberCalledAccount, wide, signature), true), List.of(LINE));
        assertEquals(List.of(checkNumberCalledAccount, wide, signature, new Item(Kind.ROUTING_NUMBER, LINE.routing(), true),
                new Item(Kind.BANK_ACCOUNT, LINE.account(), true)), out.items());
        assertEquals(true, out.incomplete());
        assertEquals(3, out.page());
        assertEquals(MODEL, out.model());
    }
}
