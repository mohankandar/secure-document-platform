package com.securedoc.platform.application.operations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Task 0020 B1/B2: the rules behind "What LexaVault did for you", shared by Reports and the compliance report so
 * both work it out the same way. Every number is rounded down, never up (the design: "Always round down").
 */
public final class TimeSavedRule {

    public static final double DEFAULT_MINUTES_PER_PAGE = 1.0;
    public static final double MIN_MINUTES_PER_PAGE = 0.5;
    public static final double MAX_MINUTES_PER_PAGE = 30.0;

    private TimeSavedRule() {
    }

    /** 0.5 to 30, in steps of 0.5. */
    public static boolean allowedMinutesPerPage(double value) {
        if (!Double.isFinite(value) || value < MIN_MINUTES_PER_PAGE || value > MAX_MINUTES_PER_PAGE) return false;
        double halves = value * 2;
        return halves == Math.rint(halves);
    }

    /** Pages in cases that needed no person × minutes per page, rounded down to whole minutes. */
    public static long minutesSaved(long pagesNoPerson, double minutesPerPage) {
        if (pagesNoPerson <= 0) return 0;
        return BigDecimal.valueOf(pagesNoPerson).multiply(BigDecimal.valueOf(minutesPerPage))
                .setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    /** The whole percent, rounded down; null when there is nothing to divide by. */
    public static Integer percentRoundedDown(long part, long whole) {
        if (whole <= 0) return null;
        return (int) (part * 100 / whole);
    }

    /** "≈ 161 h" (rounded down), or the minutes under one hour: "≈ 45 min"; "0 min" for none. */
    public static String timeWords(long minutes) {
        if (minutes <= 0) return "0 min";
        if (minutes < 60) return "≈ " + minutes + " min";
        return "≈ " + String.format(Locale.US, "%,d", minutes / 60) + " h";
    }

    /** "1", "2.5": the value as typed, without a trailing ".0". */
    public static String minutesPerPageWords(double minutesPerPage) {
        return BigDecimal.valueOf(minutesPerPage).stripTrailingZeros().toPlainString();
    }

    /** The audit key value: always one decimal, "1.0", "2.5". */
    public static String minutesPerPageKey(double minutesPerPage) {
        return String.format(Locale.ROOT, "%.1f", minutesPerPage);
    }
}
