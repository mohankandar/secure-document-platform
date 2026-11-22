package com.securedoc.install;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;

/**
 * Database settings for LexaVault's install commands (schema step, demo step, identity migration), read from the
 * environment only, never from options, so a password never lands in a command line or its history. Each command has
 * its own prefix: {@code <PREFIX>_DB_URL}, {@code <PREFIX>_DB_USER}, {@code <PREFIX>_DB_PASSWORD}.
 *
 * <p>Error output names the settings and the SQL state only: never the password, never the driver's message (which
 * can repeat connection details).
 */
record InstallDb(String url, String user, String password) {

    InstallDb {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(password, "password");
    }

    /** @throws InstallException (exit 2) naming the missing settings, never their values */
    static InstallDb fromEnvironment(String prefix, Map<String, String> env) {
        String url = env.get(prefix + "_DB_URL");
        String user = env.get(prefix + "_DB_USER");
        String password = env.get(prefix + "_DB_PASSWORD");
        StringBuilder missing = new StringBuilder();
        if (blank(url)) missing.append(' ').append(prefix).append("_DB_URL");
        if (blank(user)) missing.append(' ').append(prefix).append("_DB_USER");
        if (password == null) missing.append(' ').append(prefix).append("_DB_PASSWORD");
        if (!missing.isEmpty()) {
            throw new InstallException(2, "DB_SETTINGS_MISSING", "set these in the environment:" + missing);
        }
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new InstallException(2, "DB_SETTINGS_INVALID", prefix + "_DB_URL must be a jdbc:postgresql: address");
        }
        if (url.contains("password=")) {
            throw new InstallException(2, "DB_SETTINGS_INVALID",
                    prefix + "_DB_URL must not hold the password; give it in " + prefix + "_DB_PASSWORD");
        }
        return new InstallDb(url, user, password);
    }

    Connection open() {
        try {
            return DriverManager.getConnection(url, user, password);
        } catch (SQLException e) {
            throw new InstallException(1, "DB_UNREACHABLE", "the database could not be reached (SQL state "
                    + e.getSQLState() + ")");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public String toString() {
        return "InstallDb[url=" + url + ", user=" + user + "]";
    }
}
