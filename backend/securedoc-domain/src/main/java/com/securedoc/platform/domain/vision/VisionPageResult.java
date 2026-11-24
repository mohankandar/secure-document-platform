package com.securedoc.platform.domain.vision;

import com.securedoc.platform.domain.finding.AiModel;
import com.securedoc.platform.domain.finding.FindingLocation.BoundingBox;

import java.util.List;
import java.util.Objects;

/**
 * Task 0064: what the vision model answered for one page: kinds and boxes, never a value.
 *
 * @param code       why the page was not read, as an LV code; null when it was read or vision is switched off
 * @param reason     an internal code only (for example MODEL_NOT_INSTALLED); null when the page was read
 * @param model      the model that read the page; null when it was not read
 * @param incomplete the model named items that could not be placed: the page also goes to a person
 */
public record VisionPageResult(int page, boolean read, String code, String reason, AiModel model, List<Item> items,
                               boolean incomplete) {

    /** The kinds the prompt asks for. The names are the prompt's; never rename one. */
    public enum Kind {
        SSN, DATE_OF_BIRTH, CREDIT_CARD_NUMBER, CREDIT_SCORE, BANK_ACCOUNT, ROUTING_NUMBER, GOVERNMENT_ID, SIGNATURE,
        FACE_PHOTO
    }

    public record Item(Kind kind, BoundingBox box) {
        public Item {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(box, "box");
        }
    }

    public VisionPageResult {
        if (page < 1) throw new IllegalArgumentException("Page must be >= 1");
        items = List.copyOf(items);
        boolean consistent = read ? model != null && reason == null && code == null
                : model == null && reason != null && items.isEmpty() && !incomplete;
        if (!consistent) throw new IllegalArgumentException("Inconsistent vision result");
    }

    public static VisionPageResult read(int page, AiModel model, List<Item> items, boolean incomplete) {
        return new VisionPageResult(page, true, null, null, model, items, incomplete);
    }

    public static VisionPageResult notRead(int page, String code, String reason) {
        return new VisionPageResult(page, false, code, reason, null, List.of(), false);
    }
}
