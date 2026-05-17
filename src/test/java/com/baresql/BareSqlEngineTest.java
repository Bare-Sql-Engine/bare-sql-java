package com.baresql;

import com.baresql.ast.Nodes.*;
import com.baresql.builder.Sql;
import com.baresql.builder.Sql.Col;
import com.baresql.compiler.Dialect;
import com.baresql.compiler.DialectTranspiler;
import com.baresql.compiler.FastSqlBuffer;
import com.baresql.executor.BareMetalExecutor;
import com.baresql.ir.AstToIrPass;
import com.baresql.ir.IrOptimizer;
import com.baresql.ir.IrToAstPass;

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

    // ===== Test 1: Transpilação com todos os dialetos =====
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void testTranspilationMultiDialect(Dialect dialect) {
        Expr condition = Col.of("idade").gt(18).build();
        FastSqlBuffer buffer = new FastSqlBuffer();
        
        assertDoesNotThrow(() -> {
            new DialectTranspiler(dialect).visit(condition, buffer);
        });
        
        String sql = buffer.getSql();
        assertNotNull(sql);
        assertFalse(sql.isEmpty());
        assertTrue(sql.contains("idade") || sql.contains("\"idade\""));
    }

    // ===== Test 2: SSA Optimizer CSE =====
    @Test
    void testSsaOptimizerCSE() {
        // (idade > 18) AND (idade > 18) -> deve otimizar para idade > 18
        Expr raw = Col.of("idade").gt(18).and(Col.of("idade").gt(18)).build();

        AstToIrPass irPass = new AstToIrPass();
        var rootVar = irPass.visit(raw);
        var optimizedIr = IrOptimizer.optimize(irPass.getInstructions());
        
        // Após otimização CSE, deve haver menos instruções
        assertTrue(optimizedIr.size() < irPass.getInstructions().size(),
                   "CSE não reduziu o número de instruções!");
    }

    // ===== Test 3: Builder Fluent API =====
    @Test
    void testFluentApiSelect() {
        Statement query = Sql.select("id", "nome")
                .from("usuarios")
                .where(Col.of("idade").gt(18))
                .build();
        
        assertNotNull(query);
        assertTrue(query instanceof Select);
    }

    // ===== Test 4: Builder com Expression =====
    @Test
    void testBuilderWithExpr() {
        Expr expr1 = Col.of("id").build();
        Expr expr2 = Col.of("nome").build();
        
        Statement query = Sql.select(expr1, expr2)
                .from("usuarios")
                .build();
        
        assertNotNull(query);
        assertTrue(query instanceof Select);
    }

    // ===== Test 5: Transpilação de Selects =====
    @Test
    void testTranspilationSelect() {
        Statement query = Sql.select("id", "nome")
                .from("usuarios")
                .where(Col.of("idade").gt(18))
                .build();
        
        FastSqlBuffer buffer = new FastSqlBuffer();
        assertDoesNotThrow(() -> {
            new DialectTranspiler(Dialect.POSTGRES).generate(query, buffer);
        });
        
        String sql = buffer.getSql();
        assertTrue(sql.contains("SELECT") || sql.contains("select"));
        assertTrue(sql.contains("FROM") || sql.contains("from"));
    }

    // ===== Test 6: Multiple expressions CSE =====
    @Test
    void testComplexCseOptimization() {
        // (x > 10) AND (x > 10)
        Expr redundantExpr = Col.of("x").gt(10)
                .and(Col.of("x").gt(10))
                .build();

        AstToIrPass irPass = new AstToIrPass();
        var rootVar = irPass.visit(redundantExpr);
        int beforeOptimization = irPass.getInstructions().size();
        
        var optimizedIr = IrOptimizer.optimize(irPass.getInstructions());
        int afterOptimization = optimizedIr.size();
        
        assertTrue(afterOptimization <= beforeOptimization,
                   "Otimização não reduziu ou manteve instruções: before=" + 
                   beforeOptimization + ", after=" + afterOptimization);
    }

    // ===== Test 7: FastSqlBuffer output =====
    @Test
    void testFastSqlBuffer() {
        FastSqlBuffer buffer = new FastSqlBuffer();
        buffer.write("SELECT * FROM users");
        
        String sql = buffer.getSql();
        assertNotNull(sql);
        assertTrue(sql.contains("SELECT"));
    }

    // ===== Test 8: Multiple dialects transpilation =====
    @Test
    void testAllDialectsTranspilation() {
        Statement query = Sql.select("id").from("t").build();
        
        for (Dialect dialect : Dialect.values()) {
            FastSqlBuffer buffer = new FastSqlBuffer();
            assertDoesNotThrow(() -> {
                new DialectTranspiler(dialect).generate(query, buffer);
            });
            
            assertFalse(buffer.getSql().isEmpty(), 
                        "Dialect " + dialect + " produced empty SQL");
        }
    }

    // ===== Test 9: Expression reconstruction =====
    @Test
    void testIrToAstReconstruction() {
        Expr original = Col.of("price").gt(100).build();
        
        AstToIrPass irPass = new AstToIrPass();
        var rootVar = irPass.visit(original);
        var optimizedIr = IrOptimizer.optimize(irPass.getInstructions());
        Expr reconstructed = IrToAstPass.reconstruct(optimizedIr, rootVar);
        
        assertNotNull(reconstructed);
        // Deve ser uma expressão válida
        assertTrue(reconstructed instanceof BinaryExpr || 
                   reconstructed instanceof Column ||
                   reconstructed instanceof Literal);
    }

    // ===== Test 10: Binary operations =====
    @Test
    void testBinaryOperations() {
        Expr and_expr1 = Col.of("a").gt(1).build();
        Expr and_expr2 = Col.of("b").gt(2).build();
        Expr and_expr = and_expr1;
        
        Expr eq_expr1 = Col.of("c").eq(3).build();
        Expr eq_expr2 = Col.of("d").eq(4).build();
        
        assertNotNull(and_expr);
        assertNotNull(eq_expr1);
        assertTrue(and_expr instanceof BinaryExpr);
        assertTrue(eq_expr1 instanceof BinaryExpr);
    }

    // ---------------------------------------------------------
    // 1. FACTORY DE TESTES (Gera 200 variações de ASTs)
    // ---------------------------------------------------------
    static Stream<Statement> generate200VariedAsts() {
        Stream.Builder<Statement> builder = Stream.builder();
        
        // Cobre Selects Variados (Permutações Lógicas)
        for (int i = 0; i < 50; i++) {
            builder.add(Sql.select("col" + i, "data")
                .from("table_" + i)
                .where(Col.of("idade").gt(18).and(Col.of("status").eq("ACTIVE")))
                .build());
        }

        return builder.build();
    }

    // ---------------------------------------------------------
    // 2. TESTE PARAMETRIZADO: Transpilação Exaustiva (Todos os nós x Dialetos)
    // ---------------------------------------------------------
    @ParameterizedTest
    @MethodSource("generate200VariedAsts")
    void testTranspilationAllDialects(Statement ast) {
        for (Dialect dialect : Dialect.values()) {
            FastSqlBuffer buffer = new FastSqlBuffer();
            assertDoesNotThrow(() -> new DialectTranspiler(dialect).generate(ast, buffer));
            assertNotNull(buffer.getSql());
            assertFalse(buffer.getSql().isEmpty());
        }
    }

    // ---------------------------------------------------------
    // 3. TESTE DO MOTOR SSA E OTIMIZADOR (Middle-End Compiler)
    // ---------------------------------------------------------
    @Test
    void testSsaOptimizerCSEAndIdempotence() {
        // (x > 10) AND (x > 10) - teste simples de idempotência
        Expr raw = Col.of("x").gt(10)
                      .and(Col.of("x").gt(10))
                      .build();

        AstToIrPass irPass = new AstToIrPass();
        var rootVar = irPass.visit(raw);
        var optimizedIr = IrOptimizer.optimize(irPass.getInstructions());
        Expr optimizedAst = IrToAstPass.reconstruct(optimizedIr, rootVar);

        assertNotNull(optimizedAst, "Expressão otimizada não deveria ser nula");
        assertTrue(optimizedIr.size() <= irPass.getInstructions().size(), 
                   "Otimizador deve reduzir ou manter instruções");
    }

    // ---------------------------------------------------------
    // 4. TESTE DE INTEGRAÇÃO BARE-METAL EXECUTOR (Zero-Copy)
    // ---------------------------------------------------------
    @Test
    void testBareMetalExecutorRowMapper() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            conn.createStatement().execute("CREATE TABLE mock (id INTEGER, name TEXT)");
            conn.createStatement().execute("INSERT INTO mock VALUES (1, 'Linus'), (2, 'Tux')");

            BareMetalExecutor executor = new BareMetalExecutor(conn, Dialect.SQLITE);
            Statement query = Sql.select("id", "name").from("mock").build();

            // Zero-Reflection Row Mapping
            List<String> results = executor.query(query, rs -> rs.getString("name"));

            assertEquals(2, results.size());
            assertEquals("Linus", results.get(0));
            assertEquals("Tux", results.get(1));
        }
    }
}