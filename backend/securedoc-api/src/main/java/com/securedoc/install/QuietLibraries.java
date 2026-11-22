package com.securedoc.install;

import java.util.logging.Level;

/**
 * The install commands print their own short lines; the libraries they use (Liquibase, Flyway, Hibernate, Spring) log
 * only warnings and errors, so a person reads the result and not the libraries' progress.
 */
final class QuietLibraries {

    private QuietLibraries() {
    }

    static void apply() {
        java.util.logging.Logger.getLogger("liquibase").setLevel(Level.WARNING);
        org.slf4j.Logger root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root instanceof ch.qos.logback.classic.Logger logback) {
            logback.setLevel(ch.qos.logback.classic.Level.WARN);
        }
    }
}
