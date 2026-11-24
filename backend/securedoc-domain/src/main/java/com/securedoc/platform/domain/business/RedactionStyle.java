package com.securedoc.platform.domain.business;

/** Task 0062 (R1): how a found value is covered. The names are stored; never rename one. */
public enum RedactionStyle {
    /** The whole value is covered. */
    REMOVE_FULLY,
    /** The value is covered and its last four digits are drawn on the cover. Never for a locked item. */
    KEEP_LAST_FOUR
}
