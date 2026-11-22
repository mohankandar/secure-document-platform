package com.securedoc.install;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The demo entity's name and slug (task 0037 fix 1). A database from before task 0037 holds the fixed demo tenant
 * {@code 00000000-0000-0000-0000-000000000001} that {@code LocalDemoBootstrap} made, with slug {@code local-demo}
 * (unique in {@code tenants}) and name "Local Demo Tenant". That row and its data are never changed.
 *
 * <ul>
 *   <li>When the demo entity's own row exists, its name and slug are kept as they are (a Business Admin may have
 *       changed the name).</li>
 *   <li>Otherwise the requested slug is used when no other entity holds it, else {@code <slug>-<slot in lower case>}
 *       (for the demo: {@code local-demo-ent-demo-0001}). The slot ID is in it, so it is stable, readable and tells the
 *       two entities apart. If that is held too, the step is refused rather than guessing.</li>
 *   <li>The same for the name, compared without case: else {@code <name> (<slot>)}, "Local Demo Tenant (ENT-DEMO-0001)".
 *       Names are not unique in the table, but two entities with one name could not be told apart in the entity lists.</li>
 * </ul>
 * A fresh database therefore keeps "Local Demo Tenant" and {@code local-demo}.
 */
final class DemoNaming {

    record Choice(String name, String slug, boolean entityRowExists, List<String> notes) {
    }

    private DemoNaming() {
    }

    static Choice choose(DataSource ds, UUID entity, String slot, String name, String slug) {
        try (Connection c = ds.getConnection()) {
            if (!SchemaStep.tableExists(c, "tenants")) {
                throw new InstallException(2, "TABLES_NOT_READY", "run the schema step first");
            }
            Optional<String[]> own = own(c, entity);
            if (own.isPresent()) return new Choice(own.get()[0], own.get()[1], true, List.of());

            List<String> notes = new ArrayList<>();
            String chosenSlug = slug;
            Optional<UUID> slugHolder = holder(c, "slug = ?", slug, entity);
            if (slugHolder.isPresent()) {
                chosenSlug = slug + "-" + slot.toLowerCase(Locale.ROOT);
                if (holder(c, "slug = ?", chosenSlug, entity).isPresent()) {
                    throw new InstallException(2, "DEMO_SLUG_TAKEN", "the slugs " + slug + " and " + chosenSlug
                            + " are both held by other entities; pass --slug");
                }
                notes.add("slug " + slug + " is held by entity " + slugHolder.get() + " (left as it is); this entity uses "
                        + chosenSlug);
            }
            String chosenName = name;
            Optional<UUID> nameHolder = holder(c, "lower(name) = lower(?)", name, entity);
            if (nameHolder.isPresent()) {
                chosenName = name + " (" + slot + ")";
                if (holder(c, "lower(name) = lower(?)", chosenName, entity).isPresent()) {
                    throw new InstallException(2, "DEMO_NAME_TAKEN", "the names \"" + name + "\" and \"" + chosenName
                            + "\" are both held by other entities; pass --name");
                }
                notes.add("name \"" + name + "\" is held by entity " + nameHolder.get() + " (left as it is); this entity is "
                        + "named \"" + chosenName + "\"");
            }
            return new Choice(chosenName, chosenSlug, false, List.copyOf(notes));
        } catch (SQLException e) {
            throw new InstallException(1, "DB_UNREACHABLE", "the database could not be read (SQL state " + e.getSQLState() + ")");
        }
    }

    private static Optional<String[]> own(Connection c, UUID entity) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select name, slug from tenants where id = ?")) {
            ps.setObject(1, entity);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new String[] {rs.getString(1), rs.getString(2)}) : Optional.empty();
            }
        }
    }

    private static Optional<UUID> holder(Connection c, String where, String value, UUID entity) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select id from tenants where " + where + " and id <> ? order by id limit 1")) {
            ps.setString(1, value);
            ps.setObject(2, entity);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getObject(1, UUID.class)) : Optional.empty();
            }
        }
    }
}
