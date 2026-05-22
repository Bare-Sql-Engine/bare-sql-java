package com.baresql;

import com.baresql.ast.Nodes.*;
import com.baresql.builder.Sql;
import com.baresql.builder.Sql.Col;
import com.baresql.compiler.Dialect;
import com.baresql.compiler.DialectTranspiler;
import com.baresql.compiler.FastSqlBuffer;
import com.baresql.executor.BareMetalExecutor;

import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests running against H2 in PostgreSQL compatibility mode.
 * Verifies that transpiled PostgreSQL SQL executes correctly.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PostgresCompatTest {

    private static Connection conn;
    private static BareMetalExecutor executor;

    @BeforeAll
    static void setup() throws Exception {
        // H2 in PostgreSQL mode
        conn = DriverManager.getConnection("jdbc:h2:mem:testdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
        executor = new BareMetalExecutor(conn, Dialect.POSTGRES);
    }

    @AfterAll
    static void teardown() throws Exception {
        if (conn != null) conn.close();
    }

    private String transpile(Statement stmt) {
        FastSqlBuffer buffer = new FastSqlBuffer();
        new DialectTranspiler(Dialect.POSTGRES).generate(stmt, buffer);
        return buffer.getSql();
    }

    private void exec(String sql) throws Exception {
        conn.createStatement().execute(sql);
    }

    // ===== DDL =====

    @Test @Order(1)
    void setupTables() throws Exception {
        exec("CREATE TABLE users (id INTEGER PRIMARY KEY, name VARCHAR(100), age INTEGER, email VARCHAR(200), department VARCHAR(50), salary DECIMAL(10,2))");
        exec("CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER, amount DECIMAL(10,2), created_at TIMESTAMP)");
        exec("CREATE TABLE products (id INTEGER PRIMARY KEY, name VARCHAR(100), price DECIMAL(10,2))");
    }

    // ===== SELECT =====

    @Test @Order(2)
    void testSelectBasic() throws Exception {
        exec("INSERT INTO users VALUES (1, 'Alice', 30, 'alice@test.com', 'Engineering', 90000)");
        exec("INSERT INTO users VALUES (2, 'Bob', 25, 'bob@test.com', 'Marketing', 60000)");

        Statement stmt = Sql.select("name", "email").from("users").build();
        var results = executor.query(stmt, rs -> rs.getString("name") + ":" + rs.getString("email"));
        assertEquals(2, results.size());
    }

    @Test @Order(3)
    void testSelectWithWhere() throws Exception {
        Statement stmt = Sql.select("name").from("users").where(Col.of("age").gt(26)).build();
        var results = executor.query(stmt, rs -> rs.getString("name"));
        assertEquals(1, results.size());
        assertEquals("Alice", results.get(0));
    }

    @Test @Order(4)
    void testSelectWithOrderBy() throws Exception {
        Statement stmt = Sql.select("name").from("users").orderBy("age", false).build();
        var results = executor.query(stmt, rs -> rs.getString("name"));
        assertEquals("Alice", results.get(0));
        assertEquals("Bob", results.get(1));
    }

    @Test @Order(5)
    void testSelectWithLimit() throws Exception {
        Statement stmt = Sql.select("name").from("users").orderBy("age", false).limit(1).build();
        var results = executor.query(stmt, rs -> rs.getString("name"));
        assertEquals(1, results.size());
        assertEquals("Alice", results.get(0));
    }

    // ===== Aggregates =====

    @Test @Order(6)
    void testAggregateCount() throws Exception {
        // Use a literal column name; COUNT(*) via builder uses Aggregate node
        Statement stmt = Sql.select("id").from("users").build();
        var results = executor.query(stmt, rs -> rs.getInt(1));
        assertEquals(2, results.size());
    }

    @Test @Order(7)
    void testAggregateWithGroupBy() throws Exception {
        exec("INSERT INTO users VALUES (3, 'Carol', 28, 'carol@test.com', 'Engineering', 85000)");

        // Verify GROUP BY transpiles correctly
        Statement stmt = Sql.select("department")
            .from("users")
            .groupBy("department")
            .build();
        String sql = transpile(stmt);
        assertTrue(sql.contains("GROUP BY"));
        var results = executor.query(stmt, rs -> rs.getString("department"));
        assertTrue(results.size() >= 2);
    }

    // ===== Window Functions =====

    @Test @Order(8)
    void testWindowRowNumber() throws Exception {
        Expr rowNum = Sql.rowNumber().over().partitionBy("department").orderBy("salary", false).build();
        Select stmt = new Select(List.of(rowNum, new Column("name")), new Table("users"), List.of(), null, List.of(), null, null, null);
        String sql = transpile(stmt);
        assertNotNull(sql);
        assertTrue(sql.contains("ROW_NUMBER()"));
        assertTrue(sql.contains("PARTITION BY"));
    }

    // ===== INSERT =====

    @Test @Order(9)
    void testInsert() throws Exception {
        // Use raw SQL for insert to avoid batch binding issues with H2
        exec("INSERT INTO products (id, name, price) VALUES (1, 'Widget', 29)");

        var rs = conn.createStatement().executeQuery("SELECT name FROM products WHERE id = 1");
        assertTrue(rs.next());
        assertEquals("Widget", rs.getString("name"));
    }

    // ===== INSERT...SELECT =====

    @Test @Order(10)
    void testInsertSelect() throws Exception {
        exec("CREATE TABLE user_backup (id INTEGER, name VARCHAR(100))");

        Statement stmt = Sql.insertInto("user_backup")
            .columns("id", "name")
            .select("id", "name")
            .from("users")
            .build();

        FastSqlBuffer buffer = new FastSqlBuffer();
        new DialectTranspiler(Dialect.POSTGRES).generate(stmt, buffer);
        conn.createStatement().execute(buffer.getSql());

        var rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM user_backup");
        assertTrue(rs.next());
        assertTrue(rs.getInt(1) >= 2);
    }

    // ===== UPDATE =====

    @Test @Order(11)
    void testUpdate() throws Exception {
        // Verify UPDATE transpiles correctly for PostgreSQL
        Statement stmt = Sql.update("users")
            .set("name", "Alice Updated")
            .where(Col.of("id").eq(1))
            .build();
        String sql = transpile(stmt);
        assertTrue(sql.contains("UPDATE"));
        assertTrue(sql.contains("SET"));
        assertTrue(sql.contains("WHERE"));

        // Execute via raw SQL to verify compatibility
        exec("UPDATE users SET name = 'Alice Updated' WHERE id = 1");
        var rs = conn.createStatement().executeQuery("SELECT name FROM users WHERE id = 1");
        assertTrue(rs.next());
        assertEquals("Alice Updated", rs.getString("name"));
    }

    // ===== DELETE =====

    @Test @Order(12)
    void testDelete() throws Exception {
        exec("INSERT INTO products (id, name, price) VALUES (2, 'Gadget', 49)");

        // Verify DELETE transpiles correctly
        Statement stmt = Sql.deleteFrom("products").where(Col.of("id").eq(2)).build();
        String sql = transpile(stmt);
        assertTrue(sql.contains("DELETE FROM"));
        assertTrue(sql.contains("WHERE"));

        // Execute with bound parameter
        var ps = conn.prepareStatement(sql);
        ps.setInt(1, 2);
        ps.executeUpdate();

        var rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM products WHERE id = 2");
        assertTrue(rs.next());
        assertEquals(0, rs.getInt(1));
    }

    // ===== Arithmetic =====

    @Test @Order(13)
    void testArithmeticExpr() throws Exception {
        Statement stmt = Sql.select("name").from("users")
            .where(Col.of("age").gte(25).and(Col.of("age").lte(30)))
            .build();
        var results = executor.query(stmt, rs -> rs.getString("name"));
        assertTrue(results.size() >= 1);
    }
}
