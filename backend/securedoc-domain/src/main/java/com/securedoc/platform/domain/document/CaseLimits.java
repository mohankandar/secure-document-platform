package com.securedoc.platform.domain.document;

/**
 * Task 0038: the limits every case keeps, however it is filled (manual upload, push, scanner, polling, watched folder).
 * A source's own limits can be lower, never higher. Refused as LV-INT-028 (pages) and LV-INT-029 (files).
 */
public final class CaseLimits {
    /** Pages in one PDF. */
    public static final int MAX_PAGES_PER_FILE = 500;
    /** Files (documents) in one case. */
    public static final int MAX_FILES_PER_CASE = 50;

    private CaseLimits() { }
}
