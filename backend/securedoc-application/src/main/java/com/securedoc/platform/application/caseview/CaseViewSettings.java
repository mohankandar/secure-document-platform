package com.securedoc.platform.application.caseview;

import java.util.List;

/**
 * Task 0108: how one person sees the Cases page (View menu): sort, order, columns and density. Kept on the server with
 * the person and entity. {@code columns} null means "the page's default" (every column offered that the case list
 * carries); an empty list means "no optional column". Names and keys only, never a value.
 *
 * @param sort    {@code received}, {@code waitingLongest}, {@code priority} or {@code field:<key of the primary field>}
 * @param order   {@code asc} or {@code desc}
 * @param density {@code compact} or {@code comfortable}
 */
public record CaseViewSettings(String sort, String order, List<String> columns, String density) {
    public static final String RECEIVED = "received";
    public static final String WAITING_LONGEST = "waitingLongest";
    public static final String PRIORITY = "priority";
    public static final String FIELD_PREFIX = "field:";
    public static final String COLUMN_RECEIVED = "received";
    public static final String COLUMN_UPLOADED = "uploaded";
    public static final String COLUMN_SOURCE = "source";
    public static final List<String> BUILT_IN_COLUMNS = List.of(COLUMN_RECEIVED, COLUMN_UPLOADED, COLUMN_SOURCE);

    /** Received, oldest first; default columns; the approved two-line rows. */
    public static final CaseViewSettings DEFAULT = new CaseViewSettings(RECEIVED, "asc", null, "comfortable");

    public CaseViewSettings {
        // Not List.copyOf: a null inside a request's list must reach the service's check (refused), not fail here.
        columns = columns == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(columns));
    }
}
