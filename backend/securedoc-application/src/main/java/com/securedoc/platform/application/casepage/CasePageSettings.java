package com.securedoc.platform.application.casepage;

/**
 * Task 0108 (addendum 2) and 0111 question 1: the entity's Cases-page settings, set by the Business Admin (table
 * case_page_settings, migration 092). Waiting time on a Needs-you row turns amber after {@code waitAmberMinutes} and red
 * after {@code waitRedMinutes}; a case a person or a folder opened that still has no documents after
 * {@code emptyCaseExpiryDays} is closed as expired.
 */
public record CasePageSettings(int waitAmberMinutes, int waitRedMinutes, int emptyCaseExpiryDays) {
    public static final int DEFAULT_WAIT_AMBER_MINUTES = 4 * 60;
    public static final int DEFAULT_WAIT_RED_MINUTES = 8 * 60;
    /** One week: the longest waiting time either colour may be set to. */
    public static final int MAX_WAIT_MINUTES = 7 * 24 * 60;
    public static final int MAX_EXPIRY_DAYS = 365;

    public CasePageSettings {
        if (waitAmberMinutes < 1 || waitAmberMinutes > MAX_WAIT_MINUTES) {
            throw new IllegalArgumentException("Amber must be from 1 minute to 7 days");
        }
        if (waitRedMinutes <= waitAmberMinutes || waitRedMinutes > MAX_WAIT_MINUTES) {
            throw new IllegalArgumentException("Red must be later than amber, and at most 7 days");
        }
        if (emptyCaseExpiryDays < 1 || emptyCaseExpiryDays > MAX_EXPIRY_DAYS) {
            throw new IllegalArgumentException("A case without documents must expire after 1 to 365 days");
        }
    }

    public static CasePageSettings defaults(int emptyCaseExpiryDays) {
        return new CasePageSettings(DEFAULT_WAIT_AMBER_MINUTES, DEFAULT_WAIT_RED_MINUTES, emptyCaseExpiryDays);
    }
}
