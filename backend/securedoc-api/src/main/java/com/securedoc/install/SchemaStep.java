package com.securedoc.install;

import liquibase.integration.spring.SpringLiquibase;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * LexaVault's schema step (task 0037 step 3). It is the only place the database is changed, and it never runs at
 * startup. Run it as the schema owner, before setup and before starting a new release:
 *
 * <ol>
 *   <li>the app's own tables: the Liquibase master changelog ({@value #CHANGELOG}, history table
 *       {@code databasechangelog});</li>
 *   <li>platform-identity: Flyway run {@code classpath:db/platform/identity}, history table {@code idn_schema_history};</li>
 *   <li>platform-licensing: Flyway run {@code classpath:db/platform/licensing}, history table {@code lic_schema_history}
 *       (the setup command stores the licence there; LexaVault's own use of it comes with task 0038).</li>
 * </ol>
 *
 * Usage (database settings in the environment only: {@code LEXAVAULT_SCHEMA_DB_URL}, {@code _USER}, {@code _PASSWORD}):
 * <pre>
 * schema-step apply [--runtime-user NAME]   run the three steps; with --runtime-user, grant that user DML on the
 *                                           idn_ and lic_ tables (SELECT on their version tables) and nothing more
 * schema-step status                        print each step's version
 * schema-step customer-installed            exit 0 when setup has stored a customer, 4 when not (for the local start)
 * </pre>
 * Exit codes: 0 done, 1 failed, 2 refused (bad option or settings), 4 no customer (customer-installed only).
 * Output names tables, versions and SQL states, never the password.
 */
public final class SchemaStep {

    static final String CHANGELOG = "classpath:db/changelog/db.changelog-master.xml";
    static final String ENV_PREFIX = "LEXAVAULT_SCHEMA";

    /** One platform Flyway run: its location, its history table and its version table. */
    record PlatformRun(String name, String location, String historyTable, String versionTable, String tablePrefix) {
    }

    static final List<PlatformRun> PLATFORM_RUNS = List.of(
            new PlatformRun("identity", "classpath:db/platform/identity", "idn_schema_history", "idn_schema_version", "idn_"),
            new PlatformRun("licensing", "classpath:db/platform/licensing", "lic_schema_history", "lic_schema_version", "lic_"));

    private static final Pattern ROLE_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private final PrintStream out;
    private final PrintStream err;

    SchemaStep(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        QuietLibraries.apply();
        System.exit(new SchemaStep(System.out, System.err).run(args, System.getenv()));
    }

    int run(String[] args, Map<String, String> env) {
        try {
            if (args.length == 0) throw usage("give apply, status or customer-installed");
            String runtimeUser = null;
            for (int i = 1; i < args.length; i++) {
                if ("--runtime-user".equals(args[i]) && i + 1 < args.length && "apply".equals(args[0])) {
                    runtimeUser = args[++i];
                    if (!ROLE_NAME.matcher(runtimeUser).matches()) throw usage("--runtime-user must be a plain role name");
                } else {
                    throw usage("unknown option " + args[i]);
                }
            }
            InstallDb db = InstallDb.fromEnvironment(ENV_PREFIX, env);
            return switch (args[0]) {
                case "apply" -> apply(db, runtimeUser);
                case "status" -> status(db);
                case "customer-installed" -> customerInstalled(db);
                default -> throw usage("unknown command " + args[0]);
            };
        } catch (InstallException e) {
            err.println(e.code() + ": " + e.getMessage());
            return e.exitCode();
        }
    }

    private int apply(InstallDb db, String runtimeUser) {
        out.println("Schema step: app tables (Liquibase), then the platform runs (Flyway). Nothing runs at startup.");
        liquibaseUpdate(db);
        out.println("  app tables       up to date (" + CHANGELOG + ", history databasechangelog)");
        for (PlatformRun run : PLATFORM_RUNS) {
            MigrateResult result;
            try {
                result = flyway(db, run).migrate();
            } catch (RuntimeException e) {
                throw new InstallException(1, "SCHEMA_STEP_FAILED", run.name() + " (" + run.location() + "): "
                        + e.getClass().getSimpleName());
            }
            out.println("  " + pad(run.name()) + " " + result.migrationsExecuted + " migration(s) run, at version "
                    + (result.targetSchemaVersion == null ? result.initialSchemaVersion : result.targetSchemaVersion)
                    + " (history " + run.historyTable() + ")");
        }
        if (runtimeUser != null) grantRuntimeUser(db, runtimeUser);
        out.println("Schema step done.");
        return 0;
    }

    private int status(InstallDb db) {
        try (Connection c = db.open()) {
            out.println("  app tables       " + count(c, "select count(*) from databasechangelog") + " changesets");
            for (PlatformRun run : PLATFORM_RUNS) {
                Long v = tableExists(c, run.versionTable())
                        ? count(c, "select max(version) from " + run.versionTable()) : null;
                out.println("  " + pad(run.name()) + " " + (v == null ? "not installed" : "version " + v));
            }
            return 0;
        } catch (SQLException e) {
            throw new InstallException(1, "SCHEMA_STATUS_FAILED", "SQL state " + e.getSQLState());
        }
    }

    private int customerInstalled(InstallDb db) {
        try (Connection c = db.open()) {
            if (!tableExists(c, "idn_customer")) {
                throw new InstallException(2, "IDN_TABLES_MISSING", "run the schema step first (schema-step apply)");
            }
            long customers = count(c, "select count(*) from idn_customer");
            out.println(customers == 0 ? "No customer is stored yet: run setup." : "A customer is stored.");
            return customers == 0 ? 4 : 0;
        } catch (SQLException e) {
            throw new InstallException(1, "SCHEMA_STATUS_FAILED", "SQL state " + e.getSQLState());
        }
    }

    static Flyway flyway(InstallDb db, PlatformRun run) {
        return Flyway.configure(SchemaStep.class.getClassLoader())
                .dataSource(db.url(), db.user(), db.password())
                .locations(run.location())
                .table(run.historyTable())
                // The schema already holds the app's tables: start this run's own history at 0, so its V1 runs.
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .cleanDisabled(true)
                .createSchemas(false)
                .load();
    }

    /**
     * The app's changelog through Spring's own Liquibase runner, exactly as the app ran it at startup until now, so the
     * changesets are recorded under the same file names and a database the app already set up is left as it is.
     */
    private static void liquibaseUpdate(InstallDb db) {
        try {
            SpringLiquibase liquibase = new SpringLiquibase();
            liquibase.setDataSource(new DriverManagerDataSource(db.url(), db.user(), db.password()));
            liquibase.setChangeLog(CHANGELOG);
            liquibase.setResourceLoader(new DefaultResourceLoader(SchemaStep.class.getClassLoader()));
            liquibase.afterPropertiesSet();
        } catch (Exception e) {
            throw new InstallException(1, "SCHEMA_STEP_FAILED", "app tables (Liquibase): " + e.getClass().getSimpleName());
        }
    }

    /** DML on the platform tables, SELECT on their version tables; nothing on the history tables. */
    private void grantRuntimeUser(InstallDb db, String runtimeUser) {
        try (Connection c = db.open(); Statement s = c.createStatement()) {
            List<String> granted = new ArrayList<>();
            for (PlatformRun run : PLATFORM_RUNS) {
                for (String table : tables(c, run.tablePrefix())) {
                    if (table.equals(run.historyTable())) continue;
                    String rights = table.equals(run.versionTable()) ? "select" : "select, insert, update, delete";
                    s.execute("grant " + rights + " on " + table + " to \"" + runtimeUser + "\"");
                    granted.add(table);
                }
            }
            out.println("  runtime user     DML granted on " + granted.size() + " platform tables");
        } catch (SQLException e) {
            throw new InstallException(1, "SCHEMA_GRANT_FAILED", "SQL state " + e.getSQLState());
        }
    }

    private static List<String> tables(Connection c, String prefix) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select table_name from information_schema.tables "
                + "where table_schema = current_schema() and table_name like ? order by table_name")) {
            ps.setString(1, prefix.replace("_", "\\_") + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    static boolean tableExists(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select 1 from information_schema.tables "
                + "where table_schema = current_schema() and table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static Long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            long v = rs.getLong(1);
            return rs.wasNull() ? null : v;
        }
    }

    private static String pad(String name) {
        return String.format("%-16s", name);
    }

    private static InstallException usage(String why) {
        return new InstallException(2, "BAD_OPTION", why + ". Usage: schema-step apply [--runtime-user NAME] | status | "
                + "customer-installed");
    }
}
