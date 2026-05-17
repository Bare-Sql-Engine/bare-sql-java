package com.baresql.compiler;

import java.util.ArrayList;
import java.util.List;

public class FastSqlBuffer {
    private final StringBuilder buffer = new StringBuilder(1024);
    private final List<Object> params = new ArrayList<>();
    public void write(String s) { buffer.append(s); }
    public void writeIdentifier(String s) { buffer.append('"').append(s).append('"'); }
    public void writeLiteral(Object value) { buffer.append('?'); params.add(value); }
    public String getSql() { return buffer.toString(); }
    public List<Object> getParams() { return params; }
}
