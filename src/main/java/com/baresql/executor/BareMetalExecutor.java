package com.baresql.executor;

import com.baresql.ast.Nodes.Statement;
import com.baresql.compiler.Dialect;
import com.baresql.compiler.DialectTranspiler;
import com.baresql.compiler.FastSqlBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class BareMetalExecutor {
    private final Connection conn;
    private final Dialect targetDialect;

    public BareMetalExecutor(Connection conn, Dialect targetDialect) {
        this.conn = conn;
        this.targetDialect = targetDialect;
    }

    // --- ESCRITA (Zero-Copy Batching) ---
    public <T> void executeBatch(Statement ast, Iterator<T> dataStream, RecordBinder<T> binder, int batchSize) throws Exception {
        FastSqlBuffer buffer = new FastSqlBuffer();
        new DialectTranspiler(targetDialect).generate(ast, buffer);
        String sqlTemplate = buffer.getSql();
        
        System.out.println("[Executor] Preparando Batch: " + sqlTemplate);
        boolean originalAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        
        try (PreparedStatement ps = conn.prepareStatement(sqlTemplate)) {
            int count = 0;
            while (dataStream.hasNext()) {
                binder.bind(dataStream.next(), ps, targetDialect); 
                ps.addBatch();
                count++;
                if (count % batchSize == 0) { ps.executeBatch(); ps.clearBatch(); }
            }
            if (count % batchSize != 0) { ps.executeBatch(); }
            conn.commit();
            System.out.println("[Executor] Batch de " + count + " registros processado.");
        } catch (Exception e) {
            conn.rollback();
            throw new RuntimeException("Falha no Batch", e);
        } finally {
            conn.setAutoCommit(originalAutoCommit);
        }
    }

    // --- LEITURA (Zero-Reflection Row Mapping) ---
    public <T> List<T> query(Statement ast, RowMapper<T> mapper) throws Exception {
        FastSqlBuffer buffer = new FastSqlBuffer();
        new DialectTranspiler(targetDialect).generate(ast, buffer);
        String sql = buffer.getSql();
        
        System.out.println("[Executor] Executando Query: " + sql);
        
        List<T> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            List<Object> params = buffer.getParams();
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapper.map(rs)); // Sem Reflection! Direto na veia.
                }
            }
        }
        return results;
    }
}
