package com.securedoc.platform.domain.finding;

/** Task 0064: the path that found something on a page. The names are stored; never rename one. */
public enum DetectionPath {
    /** The page's text: patterns and the text model. */
    TEXT,
    /** The signature detector. */
    SIGNATURE,
    /** The vision model. */
    VISION,
    /** The routing check: the page could not be read by the vision model and waits for a person. */
    PAGE_CHECK
}
