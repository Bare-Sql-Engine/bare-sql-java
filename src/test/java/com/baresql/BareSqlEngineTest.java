package com.baresql;

import com.baresql.ast.Nodes.*;
import com.baresql.builder.Sql;
import com.baresql.builder.Sql.Col;
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
        FastSqlBuffer buffer = new FastSqlBuffer();
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
            Sql.select("id").from("t").limit(10).offset(5).build(),
            Sql.select(Sql.count("id")).from("t").build(),
            Sql.select(Sql.sum("amount")).from("t").build(),
            Sql.select(Sql.avg("score")).from("t").build(),
            Sql.select(Sql.min("price")).from("t").build(),
            Sql.select(Sql.max("price")).from("t").build(),
            Sql.select(Sql.countDistinct("id")).from("t").build(),
            Sql.select("id").from("t").where(Col.of("a").eq(1)).orderBy("id", true).limit(10).build(),
            Sql.select("id").from("t").where(Col.of("a").gt(10)).groupBy("dept").having(Col.of("cnt").gt(1)).build(),
            Sql.select("id").from("t").where(Col.of("a").eq(1).or(Col.of("b").eq(2))).orderBy("name", false).limit(5).offset(10).build(),
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
        Statement stmt = Sql.select("id").from("t").limit(10).offset(20).build();
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
        assertEquals(8.0, ((Literal) result).value());
    }

    @Test
    void testConstantFoldingMul() {
        Expr result = foldAndReconstruct(new BinaryExpr(new Literal(3), Op.MUL, new Literal(5)));
        assertInstanceOf(Literal.class, result);
        assertEquals(15.0, ((Literal) result).value());
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
}
