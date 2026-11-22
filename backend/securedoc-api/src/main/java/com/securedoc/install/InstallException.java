package com.securedoc.install;

/**
 * A refusal or failure of an install command: its exit code (1 failed, 2 refused) and a code that starts the message.
 * Messages never hold secrets.
 */
final class InstallException extends RuntimeException {

    private final int exitCode;
    private final String code;

    InstallException(int exitCode, String code, String message) {
        super(message);
        this.exitCode = exitCode;
        this.code = code;
    }

    int exitCode() {
        return exitCode;
    }

    String code() {
        return code;
    }
}
