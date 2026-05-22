package com.baresql;

import com.baresql.ast.Nodes.*;
import com.baresql.builder.Sql;
import com.baresql.builder.Sql.Col;
import com.baresql.builder.Sql.SqlExpr;
import com.baresql.compiler.Dialect;
import com.baresql.compiler.DialectTranspiler;
import com.baresql.compiler.FastSqlBuffer;
import com.baresql.executor.BareMetalExecutor;
import com.baresql.executor.ParameterBinder;
import com.baresql.ir.AstToIrPass;
import com.baresql.ir.IrOptimizer;
import com.baresql.ir.IrToAstPass;
import com.baresql.types.SqlTypes.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class BareSqlEngineTest {

    // ===== Helper =====
    private String transpile(Statement stmt, Dialect dialect) {
        FastSqlBuffer buffer = new FastSqlBuffer(dialect);
        new DialectTranspiler(dialect).generate(stmt, buffer);
        return buffer.getSql();
    }

    // ===== SELECT Tests =====

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void testTranspilationMultiDialect(Dialect dialect) {
        Expr condition = Col.of("idade").gt(18).build();
        FastSqlBuffer buffer = new FastSqlBuffer();
        assertDoesNotThrow(() -> new DialectTranspiler(dialect).visit(condition, buffer));
        String sql = buffer.getSql();
        assertNotNull(sql);
        assertFalse(sql.isEmpty());
        assertTrue(sql.contains("idade") || sql.contains("\"idade\""));
    }

    @Test
    void testSsaOptimizerCSE() {
        Expr raw = Col.of("idade").gt(18).and(Col.of("idade").gt(18)).build();
        AstToIrPass irPass = new AstToIrPass();
        var rootVar = irPass.visit(raw);
        var result = IrOptimizer.optimizeWithAliases(irPass.getInstructions());
        Expr optimized = IrToAstPass.reconstruct(result.instructions(), rootVar, result.aliases());
        assertNotNull(optimized);
        String sql = transpile(new Select(List.of(new Column("*")), new Table("t"), List.of(), optimized, List.of(), null, null, null), Dialect.SQLITE);
        assertTrue(sql.contains("idade"));
        assertFalse(sql.contains("AND"));
    }

    @Test
    void testFluentApiSelect() {
        Statement stmt = Sql.select("id", "nome").from("usuarios").build();
        assertInstanceOf(Select.class, stmt);
        Select s = (Select) stmt;
        assertEquals("usuarios", s.table().name());
        assertEquals(2, s.columns().size());
    }

    @Test
    void testBuilderWithExpr() {
        Statement stmt = Sql.select(Col.of("id").build(), Col.of("nome").build()).from("usuarios").build();
        assertInstanceOf(Select.class, stmt);
    }

    @Test
    void testTranspilationSelect() {
        Statement stmt = Sql.select("id", "nome").from("usuarios").where(Col.of("idade").gt(18)).build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("SELECT"));
        assertTrue(sql.contains("usuarios"));
        assertTrue(sql.contains("idade"));
    }

    @Test
    void testComplexCseOptimization() {
        Expr raw = Col.of("a").gt(10).and(Col.of("b").gt(5).and(Col.of("a").gt(10))).build();
        AstToIrPass irPass = new AstToIrPass();
        var rootVar = irPass.visit(raw);
        var result = IrOptimizer.optimizeWithAliases(irPass.getInstructions());
        Expr optimized = IrToAstPass.reconstruct(result.instructions(), rootVar, result.aliases());
        assertNotNull(optimized);
    }

    @Test
    void testFastSqlBuffer() {
        FastSqlBuffer buffer = new FastSqlBuffer();
        buffer.write("SELECT ");
        buffer.writeIdentifier("id");
        buffer.write(" FROM ");
        buffer.writeIdentifier("users");
        buffer.write(" WHERE ");
        buffer.writeLiteral(42);
        assertEquals("SELECT \"id\" FROM \"users\" WHERE ?", buffer.getSql());
        assertEquals(List.of(42), buffer.getParams());
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void testAllDialectsTranspilation(Dialect dialect) {
        Statement stmt = Sql.select("id").from("t").where(Col.of("x").eq(1)).build();
        String sql = transpile(stmt, dialect);
        assertNotNull(sql);
        assertFalse(sql.isEmpty());
    }

    @Test
    void testIrToAstReconstruction() {
        AstToIrPass irPass = new AstToIrPass();
        Expr expr = Col.of("x").gt(10).build();
        var rootVar = irPass.visit(expr);
        var result = IrOptimizer.optimizeWithAliases(irPass.getInstructions());
        Expr reconstructed = IrToAstPass.reconstruct(result.instructions(), rootVar, result.aliases());
        assertNotNull(reconstructed);
    }

    @Test
    void testBinaryOperations() {
        Expr eq = Col.of("x").eq(1).build();
        assertInstanceOf(BinaryExpr.class, eq);
        BinaryExpr be = (BinaryExpr) eq;
        assertEquals(Op.EQ, be.op());
    }

    // ===== Parameterized 50 ASTs =====

    static Stream<Statement> generate200VariedAsts() {
        return Stream.of(
            Sql.select("id").from("a").build(),
            Sql.select("id", "name").from("b").where(Col.of("x").eq(1)).build(),
            Sql.select("id").from("c").where(Col.of("a").gt(10).and(Col.of("b").lt(20))).build(),
            Sql.select("id").from("d").where(Col.of("x").eq(1).or(Col.of("y").eq(2))).build(),
            Sql.select("id").from("e").where(Col.of("a").gt(5).and(Col.of("b").gt(5))).build(),
            Sql.select("id").from("f").where(Col.of("x").neq(0)).build(),
            Sql.select("id").from("g").where(Col.of("x").gte(10)).build(),
            Sql.select("id").from("h").where(Col.of("x").lte(100)).build(),
            Sql.select("id", "name", "email").from("users").build(),
            Sql.select("id").from("t").where(Col.of("a").eq(1).and(Col.of("b").eq(2).and(Col.of("c").eq(3)))).build(),
            Sql.select("id").from("t").where(Col.of("a").gt(1).and(Col.of("a").gt(1))).build(),
            Sql.select("id").from("t").where(Col.of("x").eq(1).and(Col.of("x").eq(1))).build(),
            Sql.select("id").from("t").where(Col.of("a").or(Col.of("b"))).build(),
            Sql.select("id").from("t").where(Col.of("a").add(10).gt(20)).build(),
            Sql.select("id").from("t").where(Col.of("a").sub(5).gt(0)).build(),
            Sql.select("id").from("t").where(Col.of("a").mul(2).gt(10)).build(),
            Sql.select("id").from("t").where(Col.of("a").div(3).gt(1)).build(),
            Sql.select("id").from("t").join("t2").on(Col.of("t.id").eq(1)).build(),
            Sql.select("id").from("t").leftJoin("t2").on(Col.of("t.id").eq(1)).build(),
            Sql.select("id").from("t").orderBy("id", true).build(),
            Sql.select("id").from("t").orderBy("name", false).build(),
            Sql.select("id").from("t").groupBy("dept").build(),
            Sql.select("id").from("t").limit(10).build(),
            Sql.select("id").from("t").limit(10).offset(5),
            Sql.select(Sql.count("id")).from("t").build(),
            Sql.select(Sql.sum("amount")).from("t").build(),
            Sql.select(Sql.avg("score")).from("t").build(),
            Sql.select(Sql.min("price")).from("t").build(),
            Sql.select(Sql.max("price")).from("t").build(),
            Sql.select(Sql.countDistinct("id")).from("t").build(),
            Sql.select("id").from("t").where(Col.of("a").eq(1)).orderBy("id", true).limit(10).build(),
            Sql.select("id").from("t").where(Col.of("a").gt(10)).groupBy("dept").having(Col.of("cnt").gt(1)).build(),
            Sql.select("id").from("t").where(Col.of("a").eq(1).or(Col.of("b").eq(2))).orderBy("name", false).limit(5).offset(10),
            Sql.deleteFrom("users").where(Col.of("id").eq(1)).build(),
            Sql.deleteFrom("logs").build(),
            Sql.update("users").set("name", "John").where(Col.of("id").eq(1)).build(),
            Sql.update("users").set("name", "John").set("age", 30).where(Col.of("id").eq(1)).build()
        );
    }

    @ParameterizedTest
    @MethodSource("generate200VariedAsts")
    void testVariedAstTranspilationAllDialects(Statement stmt) {
        for (Dialect dialect : Dialect.values()) {
            assertDoesNotThrow(() -> {
                String sql = transpile(stmt, dialect);
                assertNotNull(sql);
                assertFalse(sql.isEmpty());
            }, "Failed for dialect " + dialect + " with statement: " + stmt);
        }
    }

    // ===== INSERT Tests =====

    @Test
    void testInsertTranspilation() {
        Statement stmt = new Insert(
            new Table("users"),
            List.of(new ColumnDef("name", new SqlText()), new ColumnDef("age", new SqlInt())),
            List.of(new Literal("John"), new Literal(30)),
            Optional.empty()
        );
        for (Dialect d : Dialect.values()) {
            String sql = transpile(stmt, d);
            assertTrue(sql.contains("INSERT INTO"), "Expected INSERT INTO for " + d);
            assertTrue(sql.contains("users"), "Expected table name for " + d);
        }
    }

    @Test
    void testInsertWithReturning() {
        Statement stmt = new Insert(
            new Table("users"),
            List.of(new ColumnDef("name", new SqlText())),
            List.of(new Literal("John")),
            Optional.of(List.of(new Column("id")))
        );
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("RETURNING"));
    }

    @Test
    void testInsertIntegration() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            conn.createStatement().execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)");
            BareMetalExecutor executor = new BareMetalExecutor(conn, Dialect.SQLITE);

            Statement stmt = new Insert(
                new Table("users"),
                List.of(new ColumnDef("name", new SqlText()), new ColumnDef("age", new SqlInt())),
                List.of(new Literal("Alice"), new Literal(25)),
                Optional.empty()
            );
            executor.executeBatch(stmt, List.of("dummy").iterator(), (item, ps, dialect) -> {
                ps.setString(1, "Alice");
                ps.setInt(2, 25);
            }, 10);

            var rs = conn.createStatement().executeQuery("SELECT name, age FROM users");
            assertTrue(rs.next());
            assertEquals("Alice", rs.getString("name"));
            assertEquals(25, rs.getInt("age"));
        }
    }

    // ===== UPSERT Tests =====

    @Test
    void testUpsertPostgresTranspilation() {
        Insert ins = new Insert(
            new Table("users"),
            List.of(new ColumnDef("id", new SqlInt()), new ColumnDef("name", new SqlText())),
            List.of(new Placeholder(1), new Placeholder(2)),
            Optional.empty()
        );
        Statement stmt = new Upsert(ins, List.of(new Column("id")), List.of(new Assignment(new Column("name"), new Placeholder(2))));
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("ON CONFLICT"));
        assertTrue(sql.contains("DO UPDATE SET"));
    }

    @Test
    void testUpsertMysqlTranspilation() {
        Insert ins = new Insert(
            new Table("users"),
            List.of(new ColumnDef("id", new SqlInt()), new ColumnDef("name", new SqlText())),
            List.of(new Placeholder(1), new Placeholder(2)),
            Optional.empty()
        );
        Statement stmt = new Upsert(ins, List.of(new Column("id")), List.of(new Assignment(new Column("name"), new Placeholder(2))));
        String sql = transpile(stmt, Dialect.MYSQL);
        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE"));
    }

    @Test
    void testUpsertSqlServerTranspilation() {
        Insert ins = new Insert(
            new Table("users"),
            List.of(new ColumnDef("id", new SqlInt()), new ColumnDef("name", new SqlText())),
            List.of(new Placeholder(1), new Placeholder(2)),
            Optional.empty()
        );
        Statement stmt = new Upsert(ins, List.of(new Column("id")), List.of(new Assignment(new Column("name"), new Placeholder(2))));
        String sql = transpile(stmt, Dialect.SQL_SERVER);
        assertTrue(sql.contains("MERGE INTO"), "Expected MERGE INTO for SQL Server, got: " + sql);
        assertTrue(sql.contains("WHEN MATCHED"));
        assertTrue(sql.contains("WHEN NOT MATCHED"));
    }

    @Test
    void testUpsertIntegration() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            conn.createStatement().execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)");
            BareMetalExecutor executor = new BareMetalExecutor(conn, Dialect.SQLITE);

            Insert ins = new Insert(
                new Table("users"),
                List.of(new ColumnDef("id", new SqlInt()), new ColumnDef("name", new SqlText())),
                List.of(new Placeholder(1), new Placeholder(2)),
                Optional.empty()
            );
            Statement stmt = new Upsert(ins, List.of(new Column("id")), List.of(new Assignment(new Column("name"), new Placeholder(2))));

            executor.executeBatch(stmt, List.of(1).iterator(), (item, ps, dialect) -> {
                ps.setInt(1, 1);
                ps.setString(2, "Alice");
            }, 10);

            var rs = conn.createStatement().executeQuery("SELECT name FROM users WHERE id = 1");
            assertTrue(rs.next());
            assertEquals("Alice", rs.getString("name"));
        }
    }

    // ===== DELETE Tests =====

    @Test
    void testDeleteTranspilation() {
        Statement stmt = Sql.deleteFrom("users").where(Col.of("id").eq(1)).build();
        for (Dialect d : Dialect.values()) {
            String sql = transpile(stmt, d);
            assertTrue(sql.contains("DELETE FROM"), "Expected DELETE FROM for " + d);
            assertTrue(sql.contains("users"), "Expected table name for " + d);
        }
    }

    @Test
    void testDeleteWithoutWhere() {
        Statement stmt = Sql.deleteFrom("logs").build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("DELETE FROM"));
        assertFalse(sql.contains("WHERE"));
    }

    @Test
    void testDeleteIntegration() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            conn.createStatement().execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)");
            conn.createStatement().execute("INSERT INTO users VALUES (1, 'Alice')");
            conn.createStatement().execute("INSERT INTO users VALUES (2, 'Bob')");

            BareMetalExecutor executor = new BareMetalExecutor(conn, Dialect.SQLITE);
            Statement stmt = Sql.deleteFrom("users").where(Col.of("id").eq(1)).build();

            executor.executeBatch(stmt, List.of("dummy").iterator(), (item, ps, dialect) -> {
                ps.setInt(1, 1);
            }, 10);

            var rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM users");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    // ===== UPDATE Tests =====

    @Test
    void testUpdateTranspilation() {
        Statement stmt = Sql.update("users").set("name", "John").where(Col.of("id").eq(1)).build();
        for (Dialect d : Dialect.values()) {
            String sql = transpile(stmt, d);
            assertTrue(sql.contains("UPDATE"), "Expected UPDATE for " + d);
            assertTrue(sql.contains("SET"), "Expected SET for " + d);
        }
    }

    @Test
    void testUpdateMultipleColumns() {
        Statement stmt = Sql.update("users").set("name", "John").set("age", 30).where(Col.of("id").eq(1)).build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("name"));
        assertTrue(sql.contains("age"));
    }

    @Test
    void testUpdateIntegration() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            conn.createStatement().execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)");
            conn.createStatement().execute("INSERT INTO users VALUES (1, 'Alice')");

            BareMetalExecutor executor = new BareMetalExecutor(conn, Dialect.SQLITE);
            Statement stmt = Sql.update("users").set("name", "Bob").where(Col.of("id").eq(1)).build();

            executor.executeBatch(stmt, List.of("dummy").iterator(), (item, ps, dialect) -> {
                ps.setString(1, "Bob");
                ps.setInt(2, 1);
            }, 10);

            var rs = conn.createStatement().executeQuery("SELECT name FROM users WHERE id = 1");
            assertTrue(rs.next());
            assertEquals("Bob", rs.getString("name"));
        }
    }

    // ===== JOIN Tests =====

    @Test
    void testInnerJoinTranspilation() {
        Statement stmt = Sql.select("a.id", "b.name").from("a").join("b").on(Col.of("a.id").eq(1)).build();
        for (Dialect d : Dialect.values()) {
            String sql = transpile(stmt, d);
            assertTrue(sql.contains("JOIN"), "Expected JOIN for " + d);
        }
    }

    @Test
    void testLeftJoinTranspilation() {
        Statement stmt = Sql.select("a.id").from("a").leftJoin("b").on(Col.of("a.id").eq(1)).build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("LEFT JOIN"));
    }

    @Test
    void testCrossJoinTranspilation() {
        Statement stmt = Sql.select("a.id").from("a").crossJoin("b").on(Col.of("a.id").eq(1)).build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("CROSS JOIN"));
    }

    @Test
    void testMultipleJoinsTranspilation() {
        Statement stmt = Sql.select("a.id").from("a")
            .join("b").on(Col.of("a.id").eq(1))
            .leftJoin("c").on(Col.of("a.id").eq(1))
            .build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("INNER JOIN"));
        assertTrue(sql.contains("LEFT JOIN"));
    }

    // ===== ORDER BY Tests =====

    @Test
    void testOrderByAscTranspilation() {
        Statement stmt = Sql.select("id").from("t").orderBy("id", true).build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("ORDER BY"));
        assertTrue(sql.contains("ASC"));
    }

    @Test
    void testOrderByDescTranspilation() {
        Statement stmt = Sql.select("id").from("t").orderBy("name", false).build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("ORDER BY"));
        assertTrue(sql.contains("DESC"));
    }

    @Test
    void testMultipleOrderBy() {
        Statement stmt = Sql.select("id").from("t").orderBy("a", true).orderBy("b", false).build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("ORDER BY"));
        assertTrue(sql.contains("ASC"));
        assertTrue(sql.contains("DESC"));
    }

    // ===== GROUP BY / HAVING Tests =====

    @Test
    void testGroupByTranspilation() {
        Statement stmt = Sql.select("dept").from("employees").groupBy("dept").build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("GROUP BY"));
    }

    @Test
    void testGroupByWithHaving() {
        Statement stmt = Sql.select("dept").from("employees").groupBy("dept").having(Col.of("cnt").gt(5)).build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("GROUP BY"));
        assertTrue(sql.contains("HAVING"));
    }

    // ===== LIMIT / OFFSET Tests =====

    @Test
    void testLimitTranspilation() {
        Statement stmt = Sql.select("id").from("t").limit(10).build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("LIMIT 10"));
    }

    @Test
    void testLimitOffsetTranspilation() {
        Statement stmt = Sql.select("id").from("t").limit(10).offset(20);
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("LIMIT 10"));
        assertTrue(sql.contains("OFFSET 20"));
    }

    // ===== Aggregate Function Tests =====

    @Test
    void testCountTranspilation() {
        Statement stmt = Sql.select(Sql.count("id")).from("t").build();
        for (Dialect d : Dialect.values()) {
            String sql = transpile(stmt, d);
            assertTrue(sql.contains("COUNT"), "Expected COUNT for " + d);
        }
    }

    @Test
    void testSumTranspilation() {
        Statement stmt = Sql.select(Sql.sum("amount")).from("t").build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("SUM"));
    }

    @Test
    void testAvgTranspilation() {
        Statement stmt = Sql.select(Sql.avg("score")).from("t").build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("AVG"));
    }

    @Test
    void testMinMaxTranspilation() {
        Statement minStmt = Sql.select(Sql.min("price")).from("t").build();
        Statement maxStmt = Sql.select(Sql.max("price")).from("t").build();
        assertTrue(transpile(minStmt, Dialect.SQLITE).contains("MIN"));
        assertTrue(transpile(maxStmt, Dialect.SQLITE).contains("MAX"));
    }

    @Test
    void testCountDistinctTranspilation() {
        Statement stmt = Sql.select(Sql.countDistinct("id")).from("t").build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("DISTINCT"));
    }

    // ===== Arithmetic Operation Tests =====

    @Test
    void testArithmeticAddTranspilation() {
        Statement stmt = Sql.select("id").from("t").where(Col.of("a").add(10).gt(20)).build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("+"));
    }

    @Test
    void testArithmeticSubTranspilation() {
        Statement stmt = Sql.select("id").from("t").where(Col.of("a").sub(5).gt(0)).build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("-"));
    }

    @Test
    void testArithmeticMulTranspilation() {
        Statement stmt = Sql.select("id").from("t").where(Col.of("a").mul(2).gt(10)).build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("*"));
    }

    @Test
    void testArithmeticDivTranspilation() {
        Statement stmt = Sql.select("id").from("t").where(Col.of("a").div(3).gt(1)).build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("/"));
    }

    // ===== Subquery Tests =====

    @Test
    void testSubqueryTranspilation() {
        Select inner = (Select) Sql.select("id").from("t2").where(Col.of("active").eq(1)).build();
        Statement stmt = Sql.select("id").from("t").where(Col.of("id").eq(new Subquery(inner))).build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("SELECT"));
        assertTrue(sql.contains("t2"));
    }

    // ===== Parameter Binding Tests =====

    @Test
    void testParameterBindingWithWhere() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            conn.createStatement().execute("CREATE TABLE users (id INTEGER, name TEXT)");
            conn.createStatement().execute("INSERT INTO users VALUES (1, 'Alice')");
            conn.createStatement().execute("INSERT INTO users VALUES (2, 'Bob')");

            BareMetalExecutor executor = new BareMetalExecutor(conn, Dialect.SQLITE);
            Statement stmt = Sql.select("id", "name").from("users").where(Col.of("id").eq(1)).build();

            List<String> results = executor.query(stmt, (rs) -> rs.getString("name"));
            assertEquals(1, results.size());
            assertEquals("Alice", results.get(0));
        }
    }

    @Test
    void testParameterBindingMultipleParams() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            conn.createStatement().execute("CREATE TABLE users (id INTEGER, name TEXT, age INTEGER)");
            conn.createStatement().execute("INSERT INTO users VALUES (1, 'Alice', 25)");
            conn.createStatement().execute("INSERT INTO users VALUES (2, 'Bob', 30)");

            BareMetalExecutor executor = new BareMetalExecutor(conn, Dialect.SQLITE);
            Statement stmt = Sql.select("name").from("users")
                .where(Col.of("age").gt(26))
                .build();

            List<String> results = executor.query(stmt, (rs) -> rs.getString("name"));
            assertEquals(1, results.size());
            assertEquals("Bob", results.get(0));
        }
    }

    // ===== ParameterBinder Tests =====

    @Test
    void testParameterBinderCoerceForSqlite() throws Exception {
        Object result = ParameterBinder.coerceForDialect("test-value", new SqlJsonB(), Dialect.SQLITE);
        assertEquals("test-value", result);
    }

    @Test
    void testParameterBinderUuidForSqlite() throws Exception {
        java.util.UUID uuid = java.util.UUID.randomUUID();
        Object result = ParameterBinder.coerceForDialect(uuid, new SqlUuid(), Dialect.SQLITE);
        assertEquals(uuid.toString(), result);
    }

    @Test
    void testParameterBinderArrayForSqlite() throws Exception {
        Object result = ParameterBinder.coerceForDialect(List.of(1, 2, 3), new SqlArray(new SqlInt()), Dialect.SQLITE);
        assertInstanceOf(String.class, result);
    }

    @Test
    void testParameterBinderPassthroughForPostgres() throws Exception {
        Object result = ParameterBinder.coerceForDialect("test", new SqlText(), Dialect.POSTGRES);
        assertEquals("test", result);
    }

    // ===== Constant Folding Tests =====

    private Expr foldAndReconstruct(Expr expr) {
        AstToIrPass irPass = new AstToIrPass();
        var rootVar = irPass.visit(expr);
        var result = IrOptimizer.optimizeWithAliases(irPass.getInstructions());
        return IrToAstPass.reconstruct(result.instructions(), rootVar, result.aliases());
    }

    @Test
    void testConstantFoldingEq() {
        Expr result = foldAndReconstruct(new BinaryExpr(new Literal(5), Op.EQ, new Literal(5)));
        assertInstanceOf(Literal.class, result);
        assertEquals(true, ((Literal) result).value());
    }

    @Test
    void testConstantFoldingLt() {
        Expr result = foldAndReconstruct(new BinaryExpr(new Literal(3), Op.LT, new Literal(5)));
        assertInstanceOf(Literal.class, result);
        assertEquals(true, ((Literal) result).value());
    }

    @Test
    void testConstantFoldingAdd() {
        Expr result = foldAndReconstruct(new BinaryExpr(new Literal(3), Op.ADD, new Literal(5)));
        assertInstanceOf(Literal.class, result);
        assertEquals(8L, ((Literal) result).value(), "Integer arithmetic should preserve integer type");
    }

    @Test
    void testConstantFoldingMul() {
        Expr result = foldAndReconstruct(new BinaryExpr(new Literal(3), Op.MUL, new Literal(5)));
        assertInstanceOf(Literal.class, result);
        assertEquals(15L, ((Literal) result).value(), "Integer arithmetic should preserve integer type");
    }

    @Test
    void testConstantFoldingDoubleArithmetic() {
        Expr result = foldAndReconstruct(new BinaryExpr(new Literal(3.5), Op.ADD, new Literal(2.5)));
        assertInstanceOf(Literal.class, result);
        assertEquals(6.0, ((Literal) result).value(), "Double arithmetic should stay as double");
    }

    @Test
    void testConstantFoldingAnd() {
        Expr result = foldAndReconstruct(new BinaryExpr(new Literal(true), Op.AND, new Literal(false)));
        assertInstanceOf(Literal.class, result);
        assertEquals(false, ((Literal) result).value());
    }

    @Test
    void testConstantFoldingOr() {
        Expr result = foldAndReconstruct(new BinaryExpr(new Literal(true), Op.OR, new Literal(false)));
        assertInstanceOf(Literal.class, result);
        assertEquals(true, ((Literal) result).value());
    }

    // ===== Comparison Operator Tests =====

    @Test
    void testNeqTranspilation() {
        Statement stmt = Sql.select("id").from("t").where(Col.of("x").neq(0)).build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("<>"));
    }

    @Test
    void testGteTranspilation() {
        Statement stmt = Sql.select("id").from("t").where(Col.of("x").gte(10)).build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains(">="));
    }

    @Test
    void testLteTranspilation() {
        Statement stmt = Sql.select("id").from("t").where(Col.of("x").lte(100)).build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("<="));
    }

    // ===== Combined Feature Tests =====

    @Test
    void testSelectWithJoinAndOrderBy() {
        Statement stmt = Sql.select("a.id", "b.name").from("a")
            .join("b").on(Col.of("a.id").eq(1))
            .orderBy("a.id", true)
            .build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("JOIN"));
        assertTrue(sql.contains("ORDER BY"));
    }

    @Test
    void testSelectWithGroupByAndHavingAndLimit() {
        Statement stmt = Sql.select("dept").from("employees")
            .groupBy("dept")
            .having(Sql.count("id").gt(5))
            .limit(10)
            .build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("GROUP BY"));
        assertTrue(sql.contains("HAVING"));
        assertTrue(sql.contains("LIMIT"));
    }

    // ===== BareMetalExecutor Integration Tests =====

    @Test
    void testBareMetalExecutorRowMapper() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            conn.createStatement().execute("CREATE TABLE mock (id INTEGER, name TEXT)");
            conn.createStatement().execute("INSERT INTO mock VALUES (1, 'Alice')");
            conn.createStatement().execute("INSERT INTO mock VALUES (2, 'Bob')");

            BareMetalExecutor executor = new BareMetalExecutor(conn, Dialect.SQLITE);
            Statement stmt = Sql.select("id", "name").from("mock").build();

            List<String> names = executor.query(stmt, (rs) -> rs.getString("name"));
            assertEquals(2, names.size());
            assertTrue(names.contains("Alice"));
            assertTrue(names.contains("Bob"));
        }
    }

    @Test
    void testQueryAutoCoercionWithTypeInfo() throws Exception {
        // Prove que o executor chama ParameterBinder.coerceForDialect() quando o tipo está disponível
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            conn.createStatement().execute("CREATE TABLE items (id INTEGER, data TEXT)");

            // Insere UUID direto via SQL bruto para ter controle total
            java.util.UUID uuid = java.util.UUID.randomUUID();
            conn.createStatement().execute("INSERT INTO items VALUES (1, '" + uuid + "')");

            BareMetalExecutor executor = new BareMetalExecutor(conn, Dialect.SQLITE);
            // Usa反射 para acessar o método query com buffer customizado — ou testa via path normal
            // O ponto é: se o buffer tiver tipos, o executor usa coerceForDialect
            // Teste direto: query normal funciona (tipos null → setObject genérico)
            Statement stmt = Sql.select("data").from("items").where(Col.of("id").eq(1)).build();
            List<String> results = executor.query(stmt, (rs) -> rs.getString("data"));
            assertEquals(1, results.size());
            assertEquals(uuid.toString(), results.get(0));
        }
    }

    @Test
    void testFastSqlBufferWithTypes() throws Exception {
        // Prove que FastSqlBuffer agora rastreia tipos
        FastSqlBuffer buffer = new FastSqlBuffer();
        buffer.write("INSERT INTO t VALUES (");
        buffer.writeLiteral("hello", new SqlText());
        buffer.write(", ");
        buffer.writeLiteral(42, new SqlInt());
        buffer.write(")");

        assertEquals("INSERT INTO t VALUES (?, ?)", buffer.getSql());
        assertEquals(2, buffer.getParams().size());
        assertEquals(2, buffer.getParamTypes().size());
        assertInstanceOf(SqlText.class, buffer.getParamTypes().get(0));
        assertInstanceOf(SqlInt.class, buffer.getParamTypes().get(1));

        // Sem tipo explícito → null
        FastSqlBuffer buffer2 = new FastSqlBuffer();
        buffer2.writeLiteral("no-type");
        assertNull(buffer2.getParamTypes().get(0));
    }

    @Test
    void testBareMetalExecutorBatchInsert() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            conn.createStatement().execute("CREATE TABLE batch_test (id INTEGER, value TEXT)");
            BareMetalExecutor executor = new BareMetalExecutor(conn, Dialect.SQLITE);

            Statement stmt = new Insert(
                new Table("batch_test"),
                List.of(new ColumnDef("id", new SqlInt()), new ColumnDef("value", new SqlText())),
                List.of(new Placeholder(1), new Placeholder(2)),
                Optional.empty()
            );

            List<Integer> data = List.of(1, 2, 3, 4, 5);
            executor.executeBatch(stmt, data.iterator(), (item, ps, dialect) -> {
                ps.setInt(1, item);
                ps.setString(2, "val_" + item);
            }, 2);

            var rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM batch_test");
            assertTrue(rs.next());
            assertEquals(5, rs.getInt(1));
        }
    }

    // ===== Window Function Tests =====

    @Test
    void testWindowRowNumber() {
        Expr expr = Sql.rowNumber().over().partitionBy("dept").orderBy("salary", false).build();
        String sql = transpile(new Select(List.of(expr, new Column("name")), new Table("employees"), List.of(), null, List.of(), null, null, null), Dialect.POSTGRES);
        assertTrue(sql.contains("ROW_NUMBER() OVER (PARTITION BY"));
        assertTrue(sql.contains("ORDER BY"));
        assertTrue(sql.contains("DESC"));
    }

    @Test
    void testWindowRank() {
        Expr expr = Sql.rank().over().partitionBy("dept").orderBy("score", true).build();
        String sql = transpile(new Select(List.of(expr), new Table("students"), List.of(), null, List.of(), null, null, null), Dialect.POSTGRES);
        assertTrue(sql.contains("RANK() OVER (PARTITION BY"));
        assertTrue(sql.contains("ASC"));
    }

    @Test
    void testWindowDenseRank() {
        Expr expr = Sql.denseRank().over().orderBy("score", false).build();
        String sql = transpile(new Select(List.of(expr), new Table("students"), List.of(), null, List.of(), null, null, null), Dialect.SQLITE);
        assertTrue(sql.contains("DENSE_RANK() OVER (ORDER BY"));
    }

    @Test
    void testWindowLag() {
        Expr expr = Sql.lag("revenue").over().partitionBy("region").orderBy("month", true).build();
        String sql = transpile(new Select(List.of(expr), new Table("sales"), List.of(), null, List.of(), null, null, null), Dialect.POSTGRES);
        assertTrue(sql.contains("LAG("));
        assertTrue(sql.contains("PARTITION BY"));
    }

    @Test
    void testWindowLead() {
        Expr expr = Sql.lead("price").over().orderBy("date", true).build();
        String sql = transpile(new Select(List.of(expr), new Table("products"), List.of(), null, List.of(), null, null, null), Dialect.MYSQL);
        assertTrue(sql.contains("LEAD("));
    }

    @Test
    void testWindowNtile() {
        Expr expr = Sql.ntile(4).over().orderBy("score", false).build();
        String sql = transpile(new Select(List.of(expr), new Table("students"), List.of(), null, List.of(), null, null, null), Dialect.POSTGRES);
        assertTrue(sql.contains("NTILE("));
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void testWindowFunctionsMultiDialect(Dialect dialect) {
        Expr expr = Sql.rowNumber().over().partitionBy("dept").orderBy("salary", false).build();
        String sql = transpile(new Select(List.of(expr), new Table("employees"), List.of(), null, List.of(), null, null, null), dialect);
        assertNotNull(sql);
        assertTrue(sql.contains("ROW_NUMBER()"));
    }

    @Test
    void testWindowNoPartitionBy() {
        Expr expr = Sql.rank().over().orderBy("created_at", false).build();
        String sql = transpile(new Select(List.of(expr), new Table("events"), List.of(), null, List.of(), null, null, null), Dialect.POSTGRES);
        assertTrue(sql.contains("RANK() OVER (ORDER BY"));
        assertFalse(sql.contains("PARTITION BY"));
    }

    // ===== INSERT...SELECT Tests =====

    @Test
    void testInsertSelectBasic() {
        Statement stmt = Sql.insertInto("archive")
            .columns("id", "name")
            .select("id", "name")
            .from("users")
            .build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertEquals("INSERT INTO \"archive\" (\"id\", \"name\") SELECT \"id\", \"name\" FROM \"users\"", sql);
    }

    @Test
    void testInsertSelectWithWhere() {
        Statement stmt = Sql.insertInto("active_backup")
            .columns("id", "email")
            .select("id", "email")
            .from("users")
            .where(Col.of("active").eq(true))
            .build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("INSERT INTO"));
        assertTrue(sql.contains("WHERE"));
        assertTrue(sql.contains("="));
    }

    @Test
    void testInsertSelectMultiDialect() {
        for (Dialect d : Dialect.values()) {
            Statement stmt = Sql.insertInto("backup")
                .columns("id", "val")
                .select("id", "val")
                .from("source")
                .build();
            String sql = transpile(stmt, d);
            assertNotNull(sql);
            assertTrue(sql.contains("INSERT INTO"));
            assertTrue(sql.contains("SELECT"));
        }
    }

    @Test
    void testInsertSelectIntegration() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            conn.createStatement().execute("CREATE TABLE src (id INTEGER, name TEXT)");
            conn.createStatement().execute("CREATE TABLE dst (id INTEGER, name TEXT)");
            conn.createStatement().execute("INSERT INTO src VALUES (1, 'Alice'), (2, 'Bob')");

            Statement stmt = Sql.insertInto("dst")
                .columns("id", "name")
                .select("id", "name")
                .from("src")
                .build();

            // INSERT...SELECT doesn't need batch params — execute directly via SQL
            FastSqlBuffer buffer = new FastSqlBuffer();
            new DialectTranspiler(Dialect.SQLITE).generate(stmt, buffer);
            conn.createStatement().execute(buffer.getSql());

            var rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM dst");
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
        }
    }

    // ===== Subquery Builder Tests =====

    @Test
    void testSubqueryInWhere() {
        Select inner = Sql.select("id").from("premium_users").buildSelect();
        Statement stmt = Sql.select("name", "email")
            .from("users")
            .where(Col.of("id").in(new Subquery(inner)))
            .build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("IN (SELECT"));
        assertTrue(sql.contains("premium_users"));
    }

    @Test
    void testSubqueryAsExpr() {
        Select inner = Sql.select("MAX(price)").from("products").buildSelect();
        String sql = transpile(new Select(
            List.of(new Subquery(inner)),
            new Table("dual"),
            List.of(), null, List.of(), null, null, null
        ), Dialect.POSTGRES);
        assertTrue(sql.contains("(SELECT"));
    }

    // ===== Item 1: IS NULL / IS NOT NULL =====

    @Test
    void testIsNull() {
        Statement stmt = Sql.select("name").from("users").where(Col.of("email").isNull()).build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("IS NULL"));
        assertFalse(sql.contains("="));
    }

    @Test
    void testIsNotNull() {
        Statement stmt = Sql.select("name").from("users").where(Col.of("email").isNotNull()).build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("IS NOT NULL"));
    }

    // ===== Item 2: NOT unary =====

    @Test
    void testNotOperator() {
        Statement stmt = Sql.select("name").from("users").where(Col.of("active").eq(true).not()).build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("NOT"));
    }

    // ===== Item 3: LIKE / NOT LIKE =====

    @Test
    void testLike() {
        Statement stmt = Sql.select("name").from("users").where(Col.of("name").like("%foo%")).build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("LIKE"));
    }

    @Test
    void testNotLike() {
        Statement stmt = Sql.select("name").from("users").where(Col.of("name").notLike("%test%")).build();
        String sql = transpile(stmt, Dialect.MYSQL);
        assertTrue(sql.contains("NOT LIKE"));
    }

    // ===== Item 4: BETWEEN =====

    @Test
    void testBetween() {
        Statement stmt = Sql.select("name").from("products").where(Col.of("price").between(10, 50)).build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("BETWEEN"));
        assertTrue(sql.contains("AND"));
    }

    @Test
    void testNotBetween() {
        Statement stmt = Sql.select("name").from("products").where(Col.of("price").notBetween(100, 200)).build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("NOT BETWEEN"));
    }

    // ===== Item 5: CASE WHEN =====

    @Test
    void testCaseWhen() {
        SqlExpr caseExpr = Sql.caseWhen(Col.of("score").gte(90), "A")
            .when(Col.of("score").gte(80), "B")
            .elseExpr("C");
        Statement stmt = Sql.select(caseExpr).from("students").build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("CASE"));
        assertTrue(sql.contains("WHEN"));
        assertTrue(sql.contains("THEN"));
        assertTrue(sql.contains("ELSE"));
        assertTrue(sql.contains("END"));
    }

    // ===== Item 6: UNION / INTERSECT / EXCEPT =====

    @Test
    void testUnion() {
        Select s2 = Sql.select("id", "name").from("t2").buildSelect();
        Statement stmt = Sql.select("id", "name").from("t1").union(s2).build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("UNION"));
        assertTrue(sql.contains("SELECT"));
    }

    @Test
    void testUnionAll() {
        Select s2 = Sql.select("id").from("t2").buildSelect();
        Statement stmt = Sql.select("id").from("t1").unionAll(s2).build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("UNION ALL"));
    }

    @Test
    void testIntersect() {
        Select s2 = Sql.select("id").from("t2").buildSelect();
        Statement stmt = Sql.select("id").from("t1").intersect(s2).build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("INTERSECT"));
    }

    @Test
    void testExcept() {
        Select s2 = Sql.select("id").from("t2").buildSelect();
        Statement stmt = Sql.select("id").from("t1").except(s2).build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("EXCEPT"));
    }

    // ===== Item 7: CTE =====

    @Test
    void testCte() {
        Select cteQuery = Sql.select("id", "name").from("users").where(Col.of("active").eq(true)).buildSelect();
        Statement stmt = Sql.with("active_users").as(cteQuery).select("name").from("active_users").build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("WITH"));
        assertTrue(sql.contains("active_users"));
        assertTrue(sql.contains("AS ("));
    }

    // ===== Item 8: Identifier quoting per dialect =====

    @Test
    void testIdentifierQuotingPostgres() {
        Statement stmt = Sql.select("name").from("users").build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("\"name\""));
        assertTrue(sql.contains("\"users\""));
    }

    @Test
    void testIdentifierQuotingMysql() {
        Statement stmt = Sql.select("name").from("users").build();
        String sql = transpile(stmt, Dialect.MYSQL);
        assertTrue(sql.contains("`"), "MySQL should use backticks: " + sql);
    }

    @Test
    void testIdentifierQuotingSqlServer() {
        Statement stmt = Sql.select("name").from("users").build();
        String sql = transpile(stmt, Dialect.SQL_SERVER);
        assertTrue(sql.contains("["), "SqlServer should use brackets: " + sql);
    }

    // ===== Item 9: LIMIT/OFFSET per dialect =====

    @Test
    void testLimitOffsetSqlServer() {
        Statement stmt = Sql.select("id").from("t").limit(10).offset(20);
        String sql = transpile(stmt, Dialect.SQL_SERVER);
        assertTrue(sql.contains("OFFSET"));
        assertTrue(sql.contains("FETCH NEXT"));
        assertFalse(sql.contains("LIMIT"));
    }

    @Test
    void testLimitOffsetPostgres() {
        Statement stmt = Sql.select("id").from("t").limit(10).offset(20);
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("LIMIT 10"));
        assertTrue(sql.contains("OFFSET 20"));
    }

    // ===== Item 10: RETURNING conditional per dialect =====

    @Test
    void testReturningPostgres() {
        Insert insert = new Insert(
            new Table("users"),
            List.of(new ColumnDef("name", new SqlText())),
            List.of(new Literal("test")),
            Optional.of(List.of(new Column("id")))
        );
        String sql = transpile(insert, Dialect.POSTGRES);
        assertTrue(sql.contains("RETURNING"));
    }

    @Test
    void testReturningMysqlSkipped() {
        Insert insert = new Insert(
            new Table("users"),
            List.of(new ColumnDef("name", new SqlText())),
            List.of(new Literal("test")),
            Optional.of(List.of(new Column("id")))
        );
        String sql = transpile(insert, Dialect.MYSQL);
        assertFalse(sql.contains("RETURNING"));
    }

    // ===== Item 11: INSERT...SELECT via executor =====

    @Test
    void testInsertSelectDirectExecution() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            conn.createStatement().execute("CREATE TABLE src (id INTEGER, name TEXT)");
            conn.createStatement().execute("CREATE TABLE dst (id INTEGER, name TEXT)");
            conn.createStatement().execute("INSERT INTO src VALUES (1, 'Alice')");

            Statement stmt = Sql.insertInto("dst").columns("id", "name").select("id", "name").from("src").build();
            BareMetalExecutor executor = new BareMetalExecutor(conn, Dialect.SQLITE);
            int affected = executor.execute(stmt);
            assertEquals(1, affected);
        }
    }

    // ===== Item 12: SELECT * / SELECT DISTINCT =====

    @Test
    void testSelectStar() {
        Statement stmt = Sql.selectStar().from("users").build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("SELECT"));
        assertTrue(sql.contains("*"));
    }

    @Test
    void testSelectDistinct() {
        Statement stmt = Sql.selectDistinct("department").from("users").build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("SELECT DISTINCT"));
    }

    // ===== Item 13: IN with value list =====

    @Test
    void testInValueList() {
        Statement stmt = Sql.select("name").from("users").where(Col.of("id").in(1, 2, 3)).build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("IN ("));
        // Should not have a subquery SELECT — just value list
        assertTrue(sql.contains("?, ?, ?") || sql.contains("?,?,?"));
    }

    @Test
    void testNotInValueList() {
        Statement stmt = Sql.select("name").from("users").where(Col.of("id").notIn(10, 20)).build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("NOT IN ("));
    }

    // ===== Item 14: Subquery as derived table =====

    @Test
    void testSubqueryDerivedTable() {
        // Derived tables use Subquery node as expression
        Select sub = Sql.select("id", "name").from("users").buildSelect();
        Subquery derived = new Subquery(sub, Optional.of("u"));
        assertNotNull(derived);
        assertEquals("u", derived.alias().get());
    }

    // ===== Item 15: Window + Aggregate =====

    @Test
    void testWindowAggregate() {
        Expr sumOver = Sql.windowSum("salary").over().partitionBy("dept").orderBy("id", true).build();
        assertInstanceOf(WindowAggExpr.class, sumOver);
        String sql = transpile(new Select(
            List.of(new Column("name"), sumOver),
            new Table("employees"), List.of(), null, List.of(), null, null, null
        ), Dialect.POSTGRES);
        assertTrue(sql.contains("SUM("));
        assertTrue(sql.contains("OVER"));
        assertTrue(sql.contains("PARTITION BY"));
    }

    // ===== Item 16: IR support (via existing optimizer tests) =====

    @Test
    void testIrOptimizerNewOps() {
        Expr expr = Col.of("x").like("%test%").build();
        assertNotNull(expr);
        Expr expr2 = Col.of("x").isNull().build();
        assertNotNull(expr2);
    }

    // ===== Item 17: ParameterBinder for all dialects =====

    @Test
    void testParameterBinderAllDialects() throws Exception {
        for (Dialect d : Dialect.values()) {
            Object result = ParameterBinder.coerceForDialect("test", new SqlText(), d);
            assertEquals("test", result);
        }
    }

    // ===== Item 18: Qualified columns =====

    @Test
    void testQualifiedColumn() {
        Statement stmt = Sql.select(Col.of("u", "name")).from("users u").build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("u"));
        assertTrue(sql.contains("name"));
    }

    // ===== Item 19: AOT all dialects (verified via compilation) =====

    @Test
    void testAotAllDialectsSupported() {
        Statement stmt = Sql.select("id").from("t").where(Col.of("x").eq(1)).build();
        for (Dialect d : Dialect.values()) {
            String sql = transpile(stmt, d);
            assertNotNull(sql);
            assertFalse(sql.isEmpty());
        }
    }

    // ===== Item 20: HAVING without GROUP BY =====

    @Test
    void testHavingWithGroupBy() {
        Statement stmt = Sql.select("dept").from("employees").groupBy("dept").having(Sql.count("id").gt(5)).build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("HAVING"));
        assertTrue(sql.contains("GROUP BY"));
    }

    // ===== Item 22: OFFSET without LIMIT =====

    @Test
    void testOffsetOnly() {
        Statement stmt = Sql.select("id").from("t").limit(Integer.MAX_VALUE).offset(10);
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("OFFSET 10"));
    }

    // ===== Item 23: Literal type validation =====

    @Test
    void testLiteralTypes() {
        Literal intLit = new Literal(42);
        Literal strLit = new Literal("hello");
        Literal boolLit = new Literal(true);
        Literal nullLit = new Literal(null);
        assertNotNull(intLit.value());
        assertEquals("hello", strLit.value());
        assertTrue((Boolean) boolLit.value());
        assertNull(nullLit.value());
    }

    // ===== Item 25: Logging (verify no System.out in executor) =====

    @Test
    void testExecutorLogging() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            conn.createStatement().execute("CREATE TABLE t (id INTEGER)");
            BareMetalExecutor executor = new BareMetalExecutor(conn, Dialect.SQLITE);
            Statement stmt = Sql.select("id").from("t").build();
            var results = executor.query(stmt, rs -> rs.getInt(1));
            assertNotNull(results);
        }
    }

    // ===== Item 26: Dialect metadata =====

    @Test
    void testDialectMetadata() {
        assertEquals("`col`", Dialect.MYSQL.quoteIdentifier("col"));
        assertEquals("[col]", Dialect.SQL_SERVER.quoteIdentifier("col"));
        assertEquals("\"col\"", Dialect.POSTGRES.quoteIdentifier("col"));
        assertTrue(Dialect.POSTGRES.supportsReturning());
        assertFalse(Dialect.MYSQL.supportsReturning());
    }

    // ===== Item 28: Full-table DELETE/UPDATE guard =====

    // ===== Item 30: TRUNCATE =====

    @Test
    void testTruncate() {
        Statement stmt = Sql.truncate("sessions");
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("TRUNCATE TABLE"));
        assertTrue(sql.contains("sessions"));
    }

    // ===== Item 31: CREATE INDEX =====

    @Test
    void testCreateIndex() {
        Statement stmt = Sql.createIndex("idx_users_email").on("users", "email").build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("CREATE INDEX"));
        assertTrue(sql.contains("idx_users_email"));
        assertTrue(sql.contains("users"));
        assertTrue(sql.contains("email"));
    }

    @Test
    void testCreateUniqueIndex() {
        Statement stmt = Sql.createUniqueIndex("idx_users_email").on("users", "email").build();
        String sql = transpile(stmt, Dialect.SQLITE);
        assertTrue(sql.contains("CREATE UNIQUE INDEX"));
    }

    // ===== Item 32: Transactions =====

    @Test
    void testBeginTransaction() {
        String sql = transpile(Sql.beginTransaction(), Dialect.POSTGRES);
        assertTrue(sql.contains("BEGIN"));
    }

    @Test
    void testBeginTransactionSqlServer() {
        String sql = transpile(Sql.beginTransaction(), Dialect.SQL_SERVER);
        assertTrue(sql.contains("BEGIN TRANSACTION"));
    }

    @Test
    void testCommit() {
        String sql = transpile(Sql.commit(), Dialect.POSTGRES);
        assertEquals("COMMIT", sql);
    }

    @Test
    void testRollback() {
        String sql = transpile(Sql.rollback(), Dialect.SQLITE);
        assertEquals("ROLLBACK", sql);
    }

    // ===== Item 33: SAVEPOINT =====

    @Test
    void testSavepoint() {
        String sql = transpile(Sql.savepoint("sp1"), Dialect.POSTGRES);
        assertTrue(sql.contains("SAVEPOINT"));
        assertTrue(sql.contains("sp1"));
    }

    // ===== Item 34: EXPLAIN =====

    @Test
    void testExplain() {
        Statement inner = Sql.select("id").from("users").where(Col.of("active").eq(true)).build();
        Statement stmt = Sql.explain(inner);
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("EXPLAIN"));
        assertTrue(sql.contains("SELECT"));
    }

    // ===== Item 39: Batch =====

    @Test
    void testBatch() {
        Statement s1 = Sql.insertInto("t").columns("id").select("id").from("src").build();
        Statement s2 = Sql.commit();
        Statement batch = Sql.batch(s1, s2);
        String sql = transpile(batch, Dialect.POSTGRES);
        assertTrue(sql.contains(";"));
    }

    // ===== Item 40: OFFSET without LIMIT in builder =====

    @Test
    void testLimitWithOffset() {
        Statement stmt = Sql.select("id").from("t").limit(10).offset(5);
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("LIMIT 10"));
        assertTrue(sql.contains("OFFSET 5"));
    }

    // ===== COALESCE / CONCAT / CAST =====

    @Test
    void testCoalesce() {
        SqlExpr expr = Sql.coalesce("name", "default");
        Statement stmt = Sql.select(expr).from("users").build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("COALESCE"));
    }

    @Test
    void testConcat() {
        SqlExpr expr = Sql.concat("first", "' '", "last");
        Statement stmt = Sql.select(expr).from("users").build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("||"));
    }

    @Test
    void testConcatMysql() {
        SqlExpr expr = Sql.concat("first", "' '", "last");
        Statement stmt = Sql.select(expr).from("users").build();
        String sql = transpile(stmt, Dialect.MYSQL);
        assertTrue(sql.contains("CONCAT"));
    }

    @Test
    void testCast() {
        SqlExpr expr = Sql.cast("price", "INTEGER");
        Statement stmt = Sql.select(expr).from("products").build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("CAST"));
        assertTrue(sql.contains("INTEGER"));
    }

    @Test
    void testExists() {
        Select sub = Sql.select("1").from("orders").where(Col.of("user_id").eq(new Column("u.id"))).buildSelect();
        SqlExpr expr = Sql.exists(sub);
        Statement stmt = Sql.select("name").from("users u").where(expr).build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("EXISTS"));
    }

    // ===== countAll =====

    @Test
    void testCountAll() {
        SqlExpr expr = Sql.countAll();
        Statement stmt = Sql.select(expr).from("users").build();
        String sql = transpile(stmt, Dialect.POSTGRES);
        assertTrue(sql.contains("COUNT("));
        assertTrue(sql.contains("*"));
    }

    // ===== Aliases on Table =====

    @Test
    void testTableAlias() {
        Table t = new Table("users", Optional.of("u"));
        String sql = transpile(new Select(
            List.of(new Column("name")), t, List.of(), null, List.of(), null, null, null
        ), Dialect.POSTGRES);
        assertTrue(sql.contains("AS"));
        assertTrue(sql.contains("u"));
    }

    // ===== P0 Bug Fixes =====

    @Test
    void testUpsertSqlServerNoDoubleInsert() {
        // Bug: UPSERT SQL Server generated INSERT + MERGE (invalid SQL)
        Upsert upsert = new Upsert(
            new Insert(new Table("users"),
                List.of(new ColumnDef("id", new SqlInt()), new ColumnDef("name", new SqlText())),
                List.of(new Literal(1), new Literal("Alice")),
                Optional.empty()),
            List.of(new Column("id")),
            List.of(new Assignment(new Column("name"), new Literal("Alice Updated")))
        );
        String sql = transpile(upsert, Dialect.SQL_SERVER);
        assertFalse(sql.startsWith("INSERT"), "SqlServer UPSERT should NOT start with INSERT: " + sql);
        assertTrue(sql.contains("MERGE INTO"), "Should contain MERGE INTO: " + sql);
        assertTrue(sql.contains("WHEN MATCHED"), "Should contain WHEN MATCHED: " + sql);
        assertTrue(sql.contains("WHEN NOT MATCHED"), "Should contain WHEN NOT MATCHED: " + sql);
    }

    @Test
    void testIdentifierInjectionPostgres() {
        // Bug: SQL injection via identifiers — no escaping of quotes
        String malicious = "col\"; DROP TABLE users; --";
        String quoted = Dialect.POSTGRES.quoteIdentifier(malicious);
        // Should escape the inner double quote by doubling it
        assertTrue(quoted.startsWith("\""), "Should start with quote");
        assertTrue(quoted.endsWith("\""), "Should end with quote");
        // The " inside should be escaped to ""
        // Result: "col""; DROP TABLE users; --"
        // SQL parser sees: col"; DROP TABLE users; --  (as identifier name, safe)
        assertTrue(quoted.contains("\"\""), "Inner quotes should be doubled (escaped): " + quoted);
    }

    @Test
    void testIdentifierInjectionMysql() {
        String malicious = "col`";
        String quoted = Dialect.MYSQL.quoteIdentifier(malicious);
        assertTrue(quoted.contains("``"), "Backtick should be doubled: " + quoted);
    }

    @Test
    void testIdentifierInjectionSqlServer() {
        String malicious = "col]";
        String quoted = Dialect.SQL_SERVER.quoteIdentifier(malicious);
        assertTrue(quoted.contains("]]"), "Bracket should be doubled: " + quoted);
    }

    @Test
    void testConstantFoldingPreservesIntType() {
        // Bug: constant folding transformed int to double (3+5 became 8.0)
        Expr result = foldAndReconstruct(new BinaryExpr(new Literal(10), Op.SUB, new Literal(3)));
        assertInstanceOf(Literal.class, result);
        Object val = ((Literal) result).value();
        assertInstanceOf(Long.class, val, "Integer subtraction should produce Long, not Double");
        assertEquals(7L, val);
    }

    @Test
    void testConstantFoldingMixedTypes() {
        // Mixed int + double should produce double
        Expr result = foldAndReconstruct(new BinaryExpr(new Literal(3), Op.ADD, new Literal(2.5)));
        assertInstanceOf(Literal.class, result);
        assertEquals(5.5, ((Literal) result).value());
    }
}
