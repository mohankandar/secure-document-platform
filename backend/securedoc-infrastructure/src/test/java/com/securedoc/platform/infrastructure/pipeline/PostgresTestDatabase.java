package com.securedoc.platform.infrastructure.pipeline;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;

/**
 * Task 0038: every PostgreSQL test class gets its own database, so no class sees another's rows and the full suite
 * never depends on the order the classes run in. {@code LEXAVAULT_TEST_PG_URL}, {@code LEXAVAULT_TEST_PG_USER} and
 * {@code LEXAVAULT_TEST_PG_PASSWORD} name a throwaway server (the user needs CREATEDB); this creates
 * {@code lv_t_<label>_<random>} next to the named database, runs the master changelog into it and drops it again in
 * {@link #close()} ({@code WITH (FORCE)}, so a test's open connection does not keep it). The named database itself is
 * only used to create and drop.
 *
 * <p>Why its own database, not its own schema or clean tables: the changelog creates objects without a schema name and
 * one test (migration 045) rolls changes back, so a schema per class would need a search path on every connection;
 * cleaning tables would still let one class's leftovers (or a forgotten table) reach another. A database costs one
 * changelog run per class, which every class did already.
 */
final class PostgresTestDatabase implements AutoCloseable {
    private final String adminUrl;
    private final String user;
    private final String password;
    private final String name;
    private final String url;
    private final NamedParameterJdbcTemplate jdbc;

    private PostgresTestDatabase(String adminUrl, String user, String password, String name, String url) {
        this.adminUrl = adminUrl;
        this.user = user;
        this.password = password;
        this.name = name;
        this.url = url;
        this.jdbc = new NamedParameterJdbcTemplate(new DriverManagerDataSource(url, user, password));
    }

    /** A new database with the master changelog applied. */
    static PostgresTestDatabase migrated(String label) throws Exception {
        PostgresTestDatabase db = empty(label);
        try {
            try (Connection connection = db.connection()) {
                liquibase(connection).update(new Contexts());
            }
            return db;
        } catch (Exception | Error e) {
            db.close();
            throw e;
        }
    }

    /** A new, empty database (a test that runs the changelog itself). */
    static PostgresTestDatabase empty(String label) throws Exception {
        String adminUrl = System.getenv("LEXAVAULT_TEST_PG_URL");
        String user = System.getenv("LEXAVAULT_TEST_PG_USER");
        String password = System.getenv("LEXAVAULT_TEST_PG_PASSWORD");
        String safe = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (safe.length() > 30) safe = safe.substring(0, 30);
        String name = "lv_t_" + safe + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password);
             Statement s = admin.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        }
        String url = adminUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        return new PostgresTestDatabase(adminUrl, user, password, name, url);
    }

    /**
     * Rolls back the last change sets in the order they ran. Liquibase orders them by time, and the test database's
     * clock can step back during a migration (task 0038); then it would undo another change set. The times are set to
     * follow the recorded execution order first.
     */
    static void rollback(Liquibase liquibase, Connection connection, int changeSets) throws Exception {
        try (var s = connection.createStatement()) {
            s.execute("UPDATE databasechangelog SET dateexecuted = TIMESTAMP '2026-01-01' + orderexecuted * INTERVAL '1 second'");
        }
        if (!connection.getAutoCommit()) connection.commit();
        liquibase.rollback(changeSets, (String) null);
    }

    /** Back to 050, the storage before the redaction catalog (070, task 0062): undoes every change set after it. */
    void backTo050() throws Exception {
        try (Connection connection = connection()) {
            Integer later = jdbc.getJdbcOperations().queryForObject("SELECT count(*) FROM databasechangelog WHERE orderexecuted >"
                    + " (SELECT orderexecuted FROM databasechangelog WHERE id = '050-01-page-batches')", Integer.class);
            rollback(liquibase(connection), connection, later);
        }
    }

    static Liquibase liquibase(Connection connection) throws Exception {
        Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
        return new Liquibase("db/changelog/db.changelog-master.xml", new ClassLoaderResourceAccessor(), database);
    }

    NamedParameterJdbcTemplate jdbc() {
        return jdbc;
    }

    String name() {
        return name;
    }

    Connection connection() throws Exception {
        return DriverManager.getConnection(url, user, password);
    }

    @Override
    public void close() throws Exception {
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password);
             Statement s = admin.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        }
    }
}
