package com.securedoc.platform.application.exception;

/**
 * Task 0023 B7 (#286): decides whether an exception's message may be passed on to the person. Only a message that
 * LexaVault's own code wrote (the exception was created in a {@code com.securedoc.} class, where every such message is
 * a fixed text) qualifies. A message created by the JDK or a library (for example enum or UUID parsing, which quotes
 * the value sent, or the regular-expression compiler, which quotes the pattern) never does.
 */
public final class AuthoredMessages {

    private AuthoredMessages() {
    }

    public static boolean isLexaVaultAuthored(Throwable ex) {
        if (ex == null || ex.getMessage() == null || ex.getMessage().isBlank()) return false;
        StackTraceElement[] trace = ex.getStackTrace();
        return trace.length > 0 && trace[0].getClassName().startsWith("com.securedoc.");
    }

    /** The exception's message when LexaVault wrote it, otherwise {@code fallback}. */
    public static String messageOr(Throwable ex, String fallback) {
        return isLexaVaultAuthored(ex) ? ex.getMessage() : fallback;
    }
}
