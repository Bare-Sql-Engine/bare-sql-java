package com.baresql.executor;

import com.baresql.ast.Nodes.Statement;
import com.baresql.compiler.Dialect;
import com.baresql.compiler.DialectTranspiler;
import com.baresql.compiler.FastSqlBuffer;
import com.baresql.types.SqlTypes.SqlType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BareMetalExecutor {
    private static final Logger LOG = Logger.getLogger(BareMetalExecutor.class.getName());
    private final Connection conn;
    private final Dialect targetDialect;

    public BareMetalExecutor(Connection conn, Dialect targetDialect) {
        this.conn = conn;
        this.targetDialect = targetDialect;
    }

    // --- ESCRITA (Zero-Copy Batching) ---
    public <T> void executeBatch(Statement ast, Iterator<T> dataStream, RecordBinder<T> binder, int batchSize) throws Exception {
        FastSqlBuffer buffer = transpile(ast);
        String sqlTemplate = buffer.getSql();

        LOG.log(Level.FINE, "Preparando Batch: {0}", sqlTemplate);
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
            LOG.log(Level.FINE, "Batch de {0} registros processado.", count);
        } catch (Exception e) {
            conn.rollback();
            throw new RuntimeException("Falha no Batch", e);
        } finally {
            conn.setAutoCommit(originalAutoCommit);
        }
    }

    // --- LEITURA (Zero-Reflection Row Mapping) ---
    public <T> List<T> query(Statement ast, RowMapper<T> mapper) throws Exception {
        FastSqlBuffer buffer = transpile(ast);
        String sql = buffer.getSql();

        LOG.log(Level.FINE, "Executando Query: {0}", sql);

        List<T> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindParams(ps, buffer);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapper.map(rs));
                }
            }
        }
        return results;
    }

    // --- EXECUÇÃO DIRETA (INSERT...SELECT, DDL, etc.) ---
    public int execute(Statement ast) throws Exception {
        FastSqlBuffer buffer = transpile(ast);
        String sql = buffer.getSql();

        LOG.log(Level.FINE, "Executando: {0}", sql);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindParams(ps, buffer);
            return ps.executeUpdate();
        }
    }

    // --- EXECUÇÃO SEM PARÂMETROS (DDL, transactions) ---
    public void executeDirect(Statement ast) throws Exception {
        FastSqlBuffer buffer = transpile(ast);
        String sql = buffer.getSql();

        LOG.log(Level.FINE, "Executando direto: {0}", sql);

        try (var stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private FastSqlBuffer transpile(Statement ast) {
        FastSqlBuffer buffer = new FastSqlBuffer(targetDialect);
        new DialectTranspiler(targetDialect).generate(ast, buffer);
        return buffer;
    }

    private void bindParams(PreparedStatement ps, FastSqlBuffer buffer) throws Exception {
        List<Object> params = buffer.getParams();
        List<SqlType> paramTypes = buffer.getParamTypes();
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            SqlType type = paramTypes.get(i);
            if (type != null) {
                value = ParameterBinder.coerceForDialect(value, type, targetDialect);
            }
            ps.setObject(i + 1, value);
        }
    }
}
