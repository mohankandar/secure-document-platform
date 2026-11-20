package com.securedoc.platform.api.csv;

/**
 * CSV cell helpers shared by the audit and reports CSV exports (task 0017).
 *
 * <p>A spreadsheet treats a cell that starts with {@code =}, {@code +}, {@code -} or {@code @} as a formula, even
 * inside quotes. Such a cell (after any leading spaces) gets a leading {@code '} so it is shown as text. Only the
 * CSV exports use this; JSON is unchanged.
 */
public final class CsvCells {

    private CsvCells() {
    }

    /** The value with a leading {@code '} when it would otherwise start a formula. Null stays null. */
    public static String neutralise(String value) {
        if (value == null) return null;
        int i = 0;
        while (i < value.length() && value.charAt(i) == ' ') i++;
        if (i < value.length()) {
            char c = value.charAt(i);
            if (c == '=' || c == '+' || c == '-' || c == '@') return "'" + value;
        }
        return value;
    }

    /** A quoted, neutralised cell. Null is an empty cell. */
    public static String quoted(String value) {
        if (value == null) return "";
        return "\"" + neutralise(value).replace("\"", "\"\"") + "\"";
    }
}
