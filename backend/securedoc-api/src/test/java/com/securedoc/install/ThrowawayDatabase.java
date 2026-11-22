package com.securedoc.install;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A database of its own on the throwaway PostgreSQL server named by {@code LEXAVAULT_TEST_PG_URL} (task 0037): created
 * for one test class and dropped afterwards. The server's user must be allowed to create databases (the throwaway
 * container's own user is). Never point these variables at securedoc-postgres or any other real database.
 */
final class ThrowawayDatabase implements AutoCloseable {

    static final String URL_VARIABLE = "LEXAVAULT_TEST_PG_URL";

    private final String serverUrl;
    final String user;
    final String password;
    final String name;
    final String url;

    private ThrowawayDatabase(String serverUrl, String user, String password, String name) {
        this.serverUrl = serverUrl;
        this.user = user;
        this.password = password;
        this.name = name;
        int slash = serverUrl.lastIndexOf('/');
        int query = serverUrl.indexOf('?', slash);
        this.url = serverUrl.substring(0, slash + 1) + name + (query < 0 ? "" : serverUrl.substring(query));
    }

    static ThrowawayDatabase create(String purpose) throws SQLException {
        String serverUrl = System.getenv(URL_VARIABLE);
        String user = System.getenv("LEXAVAULT_TEST_PG_USER");
        String password = System.getenv("LEXAVAULT_TEST_PG_PASSWORD");
        String name = "lv_" + purpose + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ThrowawayDatabase db = new ThrowawayDatabase(serverUrl, user, password, name);
        db.onServer("create database " + name);
        return db;
    }

    /** The settings of one install command, under its prefix, for this database and the server's user. */
    Map<String, String> env(String prefix) {
        return env(prefix, user, password);
    }

    Map<String, String> env(String prefix, String asUser, String asPassword) {
        Map<String, String> env = new HashMap<>();
        env.put(prefix + "_DB_URL", url);
        env.put(prefix + "_DB_USER", asUser);
        env.put(prefix + "_DB_PASSWORD", asPassword);
        return env;
    }

    Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    Connection connectAs(String asUser, String asPassword) throws SQLException {
        return DriverManager.getConnection(url, asUser, asPassword);
    }

    void onServer(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(serverUrl, user, password); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @Override
    public void close() throws SQLException {
        onServer("drop database if exists " + name + " with (force)");
    }
}
