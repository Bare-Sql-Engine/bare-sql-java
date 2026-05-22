package com.baresql;

import com.baresql.migration.MigrationRunner;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

public class MigrationRunnerTest {

    private String uniqueUrl() {
        return "jdbc:h2:mem:test_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    @Test
    void testMigrateH2() throws Exception {
        String url = uniqueUrl();
        MigrationRunner runner = new MigrationRunner(url, "sa", "");

        int applied = runner.migrate();
        assertTrue(applied >= 2, "Should apply at least 2 migrations, got: " + applied);

        // Verify tables exist
        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            var rs = conn.createStatement().executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = 'PUBLIC'");
            int count = 0;
            boolean hasUsers = false, hasOrders = false;
            while (rs.next()) {
                String name = rs.getString("table_name");
                if ("USERS".equals(name)) hasUsers = true;
                if ("ORDERS".equals(name)) hasOrders = true;
                count++;
            }
            assertTrue(hasUsers, "USERS table should exist");
            assertTrue(hasOrders, "ORDERS table should exist");
        }
    }

    @Test
    void testMigrateIdempotent() throws Exception {
        String url = uniqueUrl();
        MigrationRunner runner = new MigrationRunner(url, "sa", "");

        int first = runner.migrate();
        int second = runner.migrate();

        assertEquals(2, first);
        assertEquals(0, second, "Second migrate should apply 0 migrations");
    }

    @Test
    void testCurrentVersion() throws Exception {
        String url = uniqueUrl();
        MigrationRunner runner = new MigrationRunner(url, "sa", "");

        assertNull(runner.currentVersion(), "No migrations applied yet");

        runner.migrate();
        assertEquals("2", runner.currentVersion());
    }

    @Test
    void testValidate() throws Exception {
        String url = uniqueUrl();
        MigrationRunner runner = new MigrationRunner(url, "sa", "");

        runner.migrate();
        assertDoesNotThrow(runner::validate);
    }

    @Test
    void testClean() throws Exception {
        String url = uniqueUrl();
        MigrationRunner runner = new MigrationRunner(url, "sa", "");

        runner.migrate();
        runner.clean();

        // After clean, user tables should not exist (flyway_schema_history may remain)
        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            var rs = conn.createStatement().executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = 'PUBLIC' AND table_name IN ('USERS', 'ORDERS')");
            int count = 0;
            while (rs.next()) count++;
            assertEquals(0, count, "User tables should be cleaned");
        }
    }

    @Test
    void testAutoDetectLocation() {
        var runner = new MigrationRunner("jdbc:h2:mem:test");
        assertNotNull(runner);
    }
}
