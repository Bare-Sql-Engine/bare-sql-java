package com.baresql.migration;

import org.flywaydb.core.Flyway;

/**
 * Lightweight wrapper around Flyway for schema migrations.
 *
 * Usage:
 *   MigrationRunner runner = new MigrationRunner("jdbc:sqlite:mydb.db");
 *   runner.migrate();
 *
 * Migrations are loaded from classpath:db/migration/{dialect}/
 * Falls back to classpath:db/migration/ if dialect-specific folder doesn't exist.
 */
public class MigrationRunner {
    private final Flyway flyway;

    public MigrationRunner(String jdbcUrl) {
        this(jdbcUrl, null, null);
    }

    public MigrationRunner(String jdbcUrl, String user, String password) {
        this(jdbcUrl, user, password, detectLocation(jdbcUrl));
    }

    public MigrationRunner(String jdbcUrl, String user, String password, String... locations) {
        this.flyway = Flyway.configure()
            .dataSource(jdbcUrl, user, password)
            .locations(locations)
            .cleanDisabled(false)
            .load();
    }

    /**
     * Run all pending migrations.
     * @return number of migrations applied
     */
    public int migrate() {
        return flyway.migrate().migrationsExecuted;
    }

    /**
     * Validate applied migrations against available ones.
     * @throws org.flywaydb.core.api.FlywayException if validation fails
     */
    public void validate() {
        flyway.validate();
    }

    /**
     * Clean all objects in the configured schemas.
     * WARNING: destructive — use only in tests.
     */
    public void clean() {
        flyway.clean();
    }

    /**
     * Get current schema version, or null if no migrations applied.
     */
    public String currentVersion() {
        var current = flyway.info().current();
        return current != null ? current.getVersion().getVersion() : null;
    }

    private static String detectLocation(String jdbcUrl) {
        if (jdbcUrl.contains(":sqlite:")) return "classpath:db/migration/sqlite";
        if (jdbcUrl.contains(":postgresql:")) return "classpath:db/migration/postgresql";
        if (jdbcUrl.contains(":mysql:")) return "classpath:db/migration/mysql";
        if (jdbcUrl.contains(":h2:")) return "classpath:db/migration/h2";
        return "classpath:db/migration";
    }
}
