package com.securedoc.platform.domain.finding;

import com.securedoc.platform.domain.identity.ActorId;

import java.time.Instant;
import java.util.Objects;

/**
 * Task 0105: how a finding was read, found and placed, kept on the finding itself so a plain query shows where work and
 * misses come from. Names are stored; never rename one. Never a value or text.
 *
 * @param reader    what read the value; never null for a new finding
 * @param method    a pattern rule, an AI model, or a check
 * @param how       how its place on the page was found
 * @param reason    why a person is needed (which placement step failed); null when none is
 * @param placedBy  the person who placed or drew it; null when LexaVault placed it
 * @param placedAt  when the person did
 */
public record FindingPlacement(Reader reader, Method method, How how, String reason, ActorId placedBy, Instant placedAt) {

    /** What read the value. */
    public enum Reader {
        /** The PDF's own text. */
        PDF_TEXT,
        /** OCR of a scanned or photographed page. */
        OCR,
        /** The vision model. */
        VISION,
        /** A form field's value or an annotation's text. */
        FORM_FIELD,
        /** The signature detector. */
        SIGNATURE,
        /** A page check: the page itself goes to a person. */
        PAGE_CHECK,
        /** A person drew it: LexaVault missed it. */
        PERSON
    }

    public enum Method { PATTERN, AI_MODEL, CHECK, PERSON }

    /** How the place was found, in the order LexaVault tries. */
    public enum How {
        /** The reader's own words (PDF text, OCR words, form field box). */
        READER_WORDS,
        /** The vision model's box, checked to lie on the page. */
        MODEL_BOX,
        /** The value matched in the page's words, allowing OCR's usual confusions and separators. */
        MATCHED_WORDS,
        /** The field area right of or below the value's label. */
        LABEL_FIELD_AREA,
        /** No place found: the whole line or field area is covered and a person checks it. */
        GENEROUS_COVER,
        /** A page check: the whole page. */
        PAGE,
        /** No place could be found: a person places it (the reason says which step failed). */
        NONE,
        /** A person placed or moved the box. */
        PERSON
    }

    /** Reasons a person is needed. Stored; never rename one. */
    public static final String NO_PLACE = "NO_PLACE_FOUND";
    public static final String LABEL_WITHOUT_VALUE = "LABEL_WITHOUT_VALUE";

    public FindingPlacement {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(how, "how");
    }

    public static FindingPlacement automatic(Reader reader, Method method, How how) {
        return new FindingPlacement(reader, method, how, null, null, null);
    }

    public static FindingPlacement forPerson(Reader reader, Method method, How how, String reason) {
        return new FindingPlacement(reader, method, how, Objects.requireNonNull(reason, "reason"), null, null);
    }

    /** A person placed or moved the box: what read and found the value stays. */
    public FindingPlacement placedBy(ActorId actor, Instant at) {
        return new FindingPlacement(reader, method, How.PERSON, reason, Objects.requireNonNull(actor),
                Objects.requireNonNull(at));
    }

    /** For the page-batch files: names only, never a value. */
    public String encode() {
        return reader + "|" + method + "|" + how + "|" + (reason == null ? "" : reason);
    }

    /** Null for empty text or a name this version does not know. */
    public static FindingPlacement decode(String text) {
        if (text == null || text.isBlank()) return null;
        String[] parts = text.split("\\|", -1);
        if (parts.length != 4) return null;
        try {
            return new FindingPlacement(Reader.valueOf(parts[0]), Method.valueOf(parts[1]), How.valueOf(parts[2]),
                    parts[3].isEmpty() ? null : parts[3], null, null);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** A region a person drew: LexaVault missed it. */
    public static FindingPlacement drawnBy(ActorId actor, Instant at) {
        return new FindingPlacement(Reader.PERSON, Method.PERSON, How.PERSON, null, Objects.requireNonNull(actor),
                Objects.requireNonNull(at));
    }
}
