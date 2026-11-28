package com.securedoc.install;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Task 0118: the data dictionary, read from the live schema so it never goes stale. Run it against a database the schema
 * step has just set up (all migrations, nothing else): it reads every table's and column's comment, type, default,
 * keys and indexes, and writes {@value #DICTIONARY} and {@value #RELATIONSHIPS}. {@code check} fails when a table or
 * column has no comment, a dropped table has no note in {@value #RETIRED_NOTES}, or the committed files differ from what
 * it would write.
 *
 * <pre>
 * data-dictionary write DOCS_DATA_DIR    write the two files
 * data-dictionary check DOCS_DATA_DIR    exit 0 when every comment is there and the files are up to date, else 1
 * </pre>
 * Database settings as for the schema step ({@code LEXAVAULT_SCHEMA_DB_URL}, {@code _USER}, {@code _PASSWORD}). The
 * script {@value #SCRIPT} does it all on a throwaway PostgreSQL.
 */
public final class DataDictionary {

    static final String DICTIONARY = "DATA-DICTIONARY.md";
    static final String RELATIONSHIPS = "RELATIONSHIPS.md";
    static final String SCRIPT = "scripts/Update-LexaVault-DataDictionary.ps1 (or .sh)";
    static final String MASTER = "db/changelog/db.changelog-master.xml";
    static final String RETIRED_NOTES = "db/comments/retired-tables.properties";
    static final String USEFUL_QUERIES = "db/comments/useful-queries.md";

    /** The tables owned by platform-core libraries, by name prefix. */
    static final List<String> PLATFORM_PREFIXES = List.of("idn_", "lic_", "notif_", "erl_", "fld_");

    /** The main tables of the relationship diagram. */
    static final List<String> MAIN_TABLES = List.of("tenants", "document_packages", "documents", "findings", "review_tasks",
            "artifacts", "vault_entries", "pipeline_jobs", "audit_records", "package_lifecycles", "package_export_records",
            "document_export_records", "derived_deliveries", "delivery_submissions", "integration_connections",
            "external_intakes", "fld_record", "fld_value", "fld_field_definition");

    /** Links the code joins on that have no foreign key: from table.column to table.column, with how it is matched. */
    record LogicalLink(String fromTable, String fromColumn, String toTable, String toColumn, String how) {
    }

    static final List<LogicalLink> LOGICAL_LINKS = List.of(
            new LogicalLink("document_packages", "business_record_id", "fld_record", "record_id",
                    "record_id = business_record_id as text, record_type business-record"),
            new LogicalLink("fld_record", "entity_uuid", "tenants", "id", "entity_uuid = tenants.id"),
            new LogicalLink("fld_field_definition", "entity_uuid", "tenants", "id", "entity_uuid = tenants.id"),
            new LogicalLink("audit_records", "case_id", "document_packages", "id",
                    "coalesce(case_id, resource_id) = package id as text"),
            new LogicalLink("pipeline_jobs", "document_id", "documents", "id", "document_id = documents.id"));

    record Column(String name, String type, boolean required, String defaultValue, String comment) {
    }

    record ForeignKey(String name, List<String> columns, String refTable, List<String> refColumns, String onDelete) {
    }

    record Index(String name, boolean unique, boolean primary, String definition) {
    }

    record Table(String name, String comment, List<Column> columns, List<String> primaryKey, List<ForeignKey> foreignKeys,
                 List<Index> indexes) {
    }

    private DataDictionary() {
    }

    public static void main(String[] args) {
        QuietLibraries.apply();
        System.exit(run(args, System.getenv(), System.out, System.err));
    }

    static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("check"))) {
            err.println("BAD_OPTION: usage: data-dictionary write|check DOCS_DATA_DIR");
            return 2;
        }
        try {
            InstallDb db = InstallDb.fromEnvironment(SchemaStep.ENV_PREFIX, env);
            List<Table> tables;
            try (Connection c = db.open()) {
                tables = read(c);
            }
            Path dir = Path.of(args[1]);
            Map<String, String> files = render(tables);
            if (args[0].equals("write")) {
                Files.createDirectories(dir);
                for (var file : files.entrySet()) Files.writeString(dir.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
                out.println("Wrote " + String.join(" and ", files.keySet()) + " (" + tables.size() + " tables).");
            }
            List<String> problems = new ArrayList<>(problems(tables));
            if (args[0].equals("check")) problems.addAll(stale(dir, files));
            if (problems.isEmpty()) {
                out.println("Data dictionary: every table and column has a comment" + (args[0].equals("check") ? "; the files are up to date." : "."));
                return 0;
            }
            problems.forEach(p -> err.println("DATA_DICTIONARY: " + p));
            err.println("Fix the comments in the changelog (or db/comments), then run " + SCRIPT + " and commit docs/data.");
            return 1;
        } catch (InstallException e) {
            err.println(e.code() + ": " + e.getMessage());
            return e.exitCode();
        } catch (SQLException e) {
            err.println("DATA_DICTIONARY_FAILED: SQL state " + e.getSQLState());
            return 1;
        } catch (IOException e) {
            err.println("DATA_DICTIONARY_FAILED: " + e.getClass().getSimpleName() + " " + e.getMessage());
            return 1;
        }
    }

    // ---------------------------------------------------------------- reading the schema

    static List<Table> read(Connection c) throws SQLException {
        Map<String, String> tableComments = new TreeMap<>();
        query(c, """
                select cl.relname, obj_description(cl.oid, 'pg_class')
                  from pg_class cl join pg_namespace n on n.oid = cl.relnamespace
                 where n.nspname = current_schema() and cl.relkind in ('r', 'p')
                """, rs -> tableComments.put(rs.getString(1), rs.getString(2)));
        Map<String, List<Column>> columns = new LinkedHashMap<>();
        query(c, """
                select cl.relname, a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull,
                       pg_get_expr(d.adbin, d.adrelid), col_description(cl.oid, a.attnum)
                  from pg_class cl join pg_namespace n on n.oid = cl.relnamespace
                  join pg_attribute a on a.attrelid = cl.oid and a.attnum > 0 and not a.attisdropped
                  left join pg_attrdef d on d.adrelid = cl.oid and d.adnum = a.attnum
                 where n.nspname = current_schema() and cl.relkind in ('r', 'p')
                 order by cl.relname, a.attnum
                """, rs -> columns.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                .add(new Column(rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getString(5), rs.getString(6))));
        Map<String, List<String>> primaryKeys = new TreeMap<>();
        Map<String, List<ForeignKey>> foreignKeys = new TreeMap<>();
        query(c, """
                select cl.relname, co.contype, co.conname, ref.relname, co.confdeltype,
                       (select string_agg(a.attname, ',' order by k.ord) from unnest(co.conkey) with ordinality k(num, ord)
                          join pg_attribute a on a.attrelid = co.conrelid and a.attnum = k.num),
                       (select string_agg(a.attname, ',' order by k.ord) from unnest(co.confkey) with ordinality k(num, ord)
                          join pg_attribute a on a.attrelid = co.confrelid and a.attnum = k.num)
                  from pg_constraint co join pg_class cl on cl.oid = co.conrelid
                  join pg_namespace n on n.oid = cl.relnamespace
                  left join pg_class ref on ref.oid = co.confrelid
                 where n.nspname = current_schema() and co.contype in ('p', 'f')
                 order by cl.relname, co.conname
                """, rs -> {
            String table = rs.getString(1);
            if ("p".equals(rs.getString(2))) {
                primaryKeys.put(table, List.of(rs.getString(6).split(",")));
            } else {
                foreignKeys.computeIfAbsent(table, k -> new ArrayList<>()).add(new ForeignKey(rs.getString(3),
                        List.of(rs.getString(6).split(",")), rs.getString(4), List.of(rs.getString(7).split(",")),
                        onDelete(rs.getString(5))));
            }
        });
        Map<String, List<Index>> indexes = new TreeMap<>();
        query(c, """
                select t.relname, i.relname, x.indisunique, x.indisprimary, pg_get_indexdef(x.indexrelid)
                  from pg_index x join pg_class t on t.oid = x.indrelid join pg_class i on i.oid = x.indexrelid
                  join pg_namespace n on n.oid = t.relnamespace
                 where n.nspname = current_schema()
                 order by t.relname, i.relname
                """, rs -> indexes.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                .add(new Index(rs.getString(2), rs.getBoolean(3), rs.getBoolean(4), indexBody(rs.getString(5)))));
        List<Table> tables = new ArrayList<>();
        for (var entry : tableComments.entrySet()) {
            String name = entry.getKey();
            tables.add(new Table(name, entry.getValue(), columns.getOrDefault(name, List.of()),
                    primaryKeys.getOrDefault(name, List.of()), foreignKeys.getOrDefault(name, List.of()),
                    indexes.getOrDefault(name, List.of())));
        }
        return tables;
    }

    private interface Row {
        void accept(ResultSet rs) throws SQLException;
    }

    private static void query(Connection c, String sql, Row row) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) row.accept(rs);
        }
    }

    private static String onDelete(String code) {
        return switch (code) {
            case "c" -> "cascade";
            case "n" -> "set null";
            case "d" -> "set default";
            case "r" -> "restrict";
            default -> "";  // 'a': no action, the default
        };
    }

    /** "CREATE UNIQUE INDEX x ON public.t USING btree (a, b) WHERE ..." becomes "btree (a, b) WHERE ...". */
    static String indexBody(String definition) {
        int using = definition.indexOf(" USING ");
        return using < 0 ? definition : definition.substring(using + " USING ".length());
    }

    // ---------------------------------------------------------------- checks

    static List<String> problems(List<Table> tables) {
        List<String> problems = new ArrayList<>();
        for (Table t : tables) {
            if (blank(t.comment())) problems.add("table " + t.name() + " has no comment");
            for (Column col : t.columns()) {
                if (blank(col.comment())) problems.add("column " + t.name() + "." + col.name() + " has no comment");
            }
        }
        Set<String> live = tables.stream().map(Table::name).collect(Collectors.toSet());
        Map<String, String> notes = retiredNotes();
        Set<String> retired = retiredTables(live);
        for (String table : retired) {
            if (blank(notes.get(table))) problems.add("retired table " + table + " has no note in " + RETIRED_NOTES);
        }
        for (String table : notes.keySet()) {
            if (!retired.contains(table)) problems.add(RETIRED_NOTES + " names " + table + ", which is not a retired table");
        }
        for (LogicalLink link : LOGICAL_LINKS) {
            if (!hasColumn(tables, link.fromTable(), link.fromColumn()) || !hasColumn(tables, link.toTable(), link.toColumn())) {
                problems.add("the diagram's link " + link.fromTable() + "." + link.fromColumn() + " -> " + link.toTable() + "."
                        + link.toColumn() + " names a column that does not exist");
            }
        }
        for (String table : MAIN_TABLES) {
            if (!live.contains(table)) problems.add("the diagram's main table " + table + " does not exist");
        }
        return problems;
    }

    private static List<String> stale(Path dir, Map<String, String> files) throws IOException {
        List<String> problems = new ArrayList<>();
        for (var file : files.entrySet()) {
            Path path = dir.resolve(file.getKey());
            String committed = Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n") : null;
            if (committed == null) problems.add(path + " is missing");
            else if (!committed.equals(file.getValue())) problems.add(path + " differs from the live schema" + firstDifference(committed, file.getValue()));
        }
        return problems;
    }

    private static String firstDifference(String committed, String generated) {
        String[] a = committed.split("\n", -1);
        String[] b = generated.split("\n", -1);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String x = i < a.length ? a[i] : "<end>";
            String y = i < b.length ? b[i] : "<end>";
            if (!x.equals(y)) return " (line " + (i + 1) + ": committed [" + shorten(x) + "], live [" + shorten(y) + "])";
        }
        return "";
    }

    private static String shorten(String s) {
        return s.length() > 120 ? s.substring(0, 117) + "..." : s;
    }

    private static boolean hasColumn(List<Table> tables, String table, String column) {
        return tables.stream().anyMatch(t -> t.name().equals(table) && t.columns().stream().anyMatch(c -> c.name().equals(column)));
    }

    // ---------------------------------------------------------------- retired tables

    private static final Pattern INCLUDE = Pattern.compile("<include\\s+file=\"([^\"]+)\"");
    private static final Pattern CREATED = Pattern.compile(
            "<createTable[^>]*\\btableName=\"([A-Za-z0-9_]+)\"|(?i)\\bcreate\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([a-z0-9_]+)");

    /** Every table a changelog ever created that the live schema no longer has. */
    static Set<String> retiredTables(Set<String> live) {
        Set<String> created = new TreeSet<>();
        Matcher includes = INCLUDE.matcher(resource(MASTER));
        while (includes.find()) {
            Matcher m = CREATED.matcher(resource(includes.group(1)));
            while (m.find()) created.add((m.group(1) != null ? m.group(1) : m.group(2)).toLowerCase(Locale.ROOT));
        }
        created.removeAll(live);
        return created;
    }

    static Map<String, String> retiredNotes() {
        Properties p = new Properties();
        try (InputStream in = stream(RETIRED_NOTES)) {
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> notes = new TreeMap<>();
        p.forEach((k, v) -> notes.put(k.toString().trim(), v.toString().trim()));
        return notes;
    }

    static String resource(String name) {
        try (InputStream in = stream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream stream(String name) {
        InputStream in = DataDictionary.class.getClassLoader().getResourceAsStream(name);
        if (in == null) throw new InstallException(1, "RESOURCE_MISSING", name);
        return in;
    }

    // ---------------------------------------------------------------- rendering

    enum Owner {
        APP("LexaVault tables"), PLATFORM("platform-core tables"), HISTORY("Migration history tables");

        final String heading;

        Owner(String heading) {
            this.heading = heading;
        }
    }

    static Owner owner(String table) {
        if (table.startsWith("databasechangelog") || table.endsWith("_schema_history")) return Owner.HISTORY;
        return PLATFORM_PREFIXES.stream().anyMatch(table::startsWith) ? Owner.PLATFORM : Owner.APP;
    }

    static Map<String, String> render(List<Table> tables) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(DICTIONARY, dictionary(tables));
        files.put(RELATIONSHIPS, relationships(tables));
        return files;
    }

    static String dictionary(List<Table> tables) {
        StringBuilder md = new StringBuilder();
        long columnCount = tables.stream().mapToLong(t -> t.columns().size()).sum();
        md.append("# LexaVault data dictionary\n\n")
                .append("Generated from the live schema by `").append(SCRIPT).append("`: every migration runs on a throwaway ")
                .append("PostgreSQL, then the comments, types, keys and indexes are read back. **Do not edit this file by hand.** ")
                .append("Change a meaning in the changelog that owns the table (LexaVault: `remarks` in ")
                .append("`backend/securedoc-infrastructure/src/main/resources/db/changelog/`, platform-core: ")
                .append("`backend/securedoc-api/src/main/resources/db/comments/platform-tables.sql`), run the script and commit. ")
                .append("CI fails when a table or column has no comment or this file is out of date.\n\n")
                .append("Meanings ending in \"(to confirm)\" are drafted from the code and wait for the owner's word.\n\n")
                .append("The links between the main tables are drawn in [RELATIONSHIPS.md](RELATIONSHIPS.md).\n\n")
                .append(tables.size()).append(" tables, ").append(columnCount).append(" columns.\n\n");
        for (Owner owner : Owner.values()) {
            List<Table> group = tables.stream().filter(t -> owner(t.name()) == owner).toList();
            md.append("## ").append(owner.heading).append("\n\n| Table | Purpose |\n|---|---|\n");
            for (Table t : group) md.append("| [`").append(t.name()).append("`](#").append(anchor(t.name())).append(") | ")
                    .append(cell(t.comment())).append(" |\n");
            md.append('\n');
        }
        Map<String, List<String>> referencedBy = new TreeMap<>();
        for (Table t : tables) {
            for (ForeignKey fk : t.foreignKeys()) {
                referencedBy.computeIfAbsent(fk.refTable(), k -> new ArrayList<>()).add("`" + t.name() + "`");
            }
        }
        for (Owner owner : Owner.values()) {
            md.append("## ").append(owner.heading).append(", in detail\n\n");
            for (Table t : tables.stream().filter(x -> owner(x.name()) == owner).toList()) {
                table(md, t, referencedBy.getOrDefault(t.name(), List.of()));
            }
        }
        md.append("## Retired tables\n\nTables an earlier migration created and a later one dropped. Never query them; ")
                .append("use what replaced them.\n\n| Table | Dropped, and replaced by |\n|---|---|\n");
        Set<String> live = tables.stream().map(Table::name).collect(Collectors.toSet());
        Map<String, String> notes = retiredNotes();
        for (String table : retiredTables(live)) {
            md.append("| `").append(table).append("` | ").append(cell(notes.getOrDefault(table, ""))).append(" |\n");
        }
        md.append('\n').append(resource(USEFUL_QUERIES).strip()).append('\n');
        return md.toString();
    }

    private static void table(StringBuilder md, Table t, List<String> referencedBy) {
        md.append("### ").append(t.name()).append("\n\n").append(t.comment() == null ? "" : t.comment()).append("\n\n")
                .append("| Column | Type | Required | Default | Meaning |\n|---|---|---|---|---|\n");
        for (Column c : t.columns()) {
            md.append("| `").append(c.name()).append("` | ").append(c.type()).append(" | ").append(c.required() ? "yes" : "")
                    .append(" | ").append(c.defaultValue() == null ? "" : "`" + cell(c.defaultValue()) + "`").append(" | ")
                    .append(cell(c.comment())).append(" |\n");
        }
        md.append('\n');
        md.append("- Primary key: ").append(t.primaryKey().isEmpty() ? "none" : code(t.primaryKey())).append('\n');
        if (!t.foreignKeys().isEmpty()) {
            md.append("- Foreign keys:\n");
            for (ForeignKey fk : t.foreignKeys()) {
                md.append("  - ").append(code(fk.columns())).append(" → `").append(fk.refTable()).append("` (")
                        .append(String.join(", ", fk.refColumns())).append(")")
                        .append(fk.onDelete().isEmpty() ? "" : ", on delete " + fk.onDelete()).append('\n');
            }
        }
        List<Index> other = t.indexes().stream().filter(i -> !i.primary()).toList();
        if (!other.isEmpty()) {
            md.append("- Indexes:\n");
            for (Index i : other) {
                md.append("  - `").append(i.name()).append("`").append(i.unique() ? " (unique)" : "").append(": `")
                        .append(cell(i.definition())).append("`\n");
            }
        }
        if (!referencedBy.isEmpty()) {
            md.append("- Referenced by: ").append(String.join(", ", new TreeSet<>(referencedBy))).append('\n');
        }
        md.append('\n');
    }

    static String relationships(List<Table> tables) {
        Map<String, Table> byName = tables.stream().collect(Collectors.toMap(Table::name, t -> t));
        Set<String> main = new TreeSet<>(MAIN_TABLES);
        StringBuilder md = new StringBuilder();
        md.append("# LexaVault: how the main tables link\n\n")
                .append("Generated with [DATA-DICTIONARY.md](DATA-DICTIONARY.md) by `").append(SCRIPT).append("`. Do not edit by hand.\n\n")
                .append("Solid lines are foreign keys. Dotted lines are links the code joins on without a foreign key ")
                .append("(listed under the diagram). Each box shows its primary key and the columns that link it. ")
                .append("Nearly every table also has `tenant_id` → `tenants`: only the case's is drawn, and every query ")
                .append("must filter on it.\n\n")
                .append("```mermaid\nerDiagram\n");
        for (String name : main) {
            Table t = byName.get(name);
            if (t == null) continue;
            Set<String> keys = new TreeSet<>(t.primaryKey());
            for (ForeignKey fk : t.foreignKeys()) if (drawn(main, name, fk)) keys.addAll(fk.columns());
            for (LogicalLink l : LOGICAL_LINKS) {
                if (l.fromTable().equals(name)) keys.add(l.fromColumn());
                if (l.toTable().equals(name)) keys.add(l.toColumn());
            }
            md.append("    ").append(name).append(" {\n");
            for (Column c : t.columns()) {
                if (!keys.contains(c.name())) continue;
                String mark = t.primaryKey().contains(c.name()) ? " PK" : "";
                boolean isFk = t.foreignKeys().stream().anyMatch(fk -> drawn(main, name, fk) && fk.columns().contains(c.name()));
                if (isFk) mark = mark.isEmpty() ? " FK" : mark + ", FK";
                md.append("        ").append(mermaidType(c.type())).append(' ').append(c.name()).append(mark).append('\n');
            }
            md.append("    }\n");
        }
        for (String name : main) {
            Table t = byName.get(name);
            if (t == null) continue;
            for (ForeignKey fk : t.foreignKeys()) {
                if (!drawn(main, name, fk)) continue;
                boolean required = fk.columns().stream().allMatch(col -> t.columns().stream()
                        .anyMatch(c -> c.name().equals(col) && c.required()));
                md.append("    ").append(fk.refTable()).append(required ? " ||--o{ " : " |o--o{ ").append(name)
                        .append(" : \"").append(String.join(", ", fk.columns())).append("\"\n");
            }
        }
        for (LogicalLink l : LOGICAL_LINKS) {
            md.append("    ").append(l.toTable()).append(" |o..o{ ").append(l.fromTable()).append(" : \"")
                    .append(l.fromColumn()).append("\"\n");
        }
        md.append("```\n\n## Links without a foreign key\n\n| From | To | How the code matches them |\n|---|---|---|\n");
        for (LogicalLink l : LOGICAL_LINKS) {
            md.append("| `").append(l.fromTable()).append('.').append(l.fromColumn()).append("` | `").append(l.toTable())
                    .append('.').append(l.toColumn()).append("` | ").append(cell(l.how())).append(" |\n");
        }
        md.append("\nEvery other table and link is in [DATA-DICTIONARY.md](DATA-DICTIONARY.md) (its foreign keys and ")
                .append("\"Referenced by\" lines).\n");
        return md.toString();
    }

    /** A foreign key the diagram draws: between main tables, and to tenants only from the case (every table has one). */
    private static boolean drawn(Set<String> main, String table, ForeignKey fk) {
        return main.contains(fk.refTable()) && (!fk.refTable().equals("tenants") || table.equals("document_packages"));
    }

    static String mermaidType(String type) {
        String base = type.replaceAll("\\(.*\\)", "").replace("[]", "_array").trim();
        return switch (base) {
            case "character varying" -> "varchar";
            case "timestamp with time zone" -> "timestamptz";
            case "timestamp without time zone" -> "timestamp";
            case "double precision" -> "double";
            default -> base.replace(' ', '_');
        };
    }

    private static String anchor(String table) {
        return table.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_ -]", "").replace(' ', '-');
    }

    private static String code(List<String> columns) {
        return columns.stream().map(c -> "`" + c + "`").collect(Collectors.joining(", "));
    }

    private static String cell(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
