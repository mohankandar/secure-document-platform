package com.securedoc.platform.application.review.result;

import java.util.Objects;

/**
 * The text a pending finding covers, with a little of the same line around it, for the person
 * deciding it in focused review. It holds raw sensitive text: it is returned only on the
 * MANUAL_REVIEW queue, is never stored, and must never be logged or written to audit detail.
 * {@link #toString()} is redacted so an accidental log line cannot leak it.
 */
public record ReviewFoundText(String text, String before, String after) {

    public ReviewFoundText {
        Objects.requireNonNull(text, "text");
        before = before == null ? "" : before;
        after = after == null ? "" : after;
    }

    @Override
    public String toString() {
        return "ReviewFoundText[redacted]";
    }
}
