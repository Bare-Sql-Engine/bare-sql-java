package com.baresql.compiler;

import com.baresql.types.SqlTypes.SqlType;
import java.util.ArrayList;
import java.util.List;

public class FastSqlBuffer {
    private final StringBuilder buffer = new StringBuilder(1024);
    private final List<Object> params = new ArrayList<>();
    private final List<SqlType> paramTypes = new ArrayList<>();
    private final Dialect dialect;

    public FastSqlBuffer() { this(Dialect.SQLITE); }
    public FastSqlBuffer(Dialect dialect) { this.dialect = dialect; }

    public void write(String s) { buffer.append(s); }
    public void writeIdentifier(String s) { buffer.append(dialect.quoteIdentifier(s)); }
    public void writeLiteral(Object value) { buffer.append('?'); params.add(value); paramTypes.add(null); }
    public void writeLiteral(Object value, SqlType type) { buffer.append('?'); params.add(value); paramTypes.add(type); }
    public String getSql() { return buffer.toString(); }
    public List<Object> getParams() { return params; }
    public List<SqlType> getParamTypes() { return paramTypes; }
    public Dialect getDialect() { return dialect; }
}
