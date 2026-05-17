package com.baresql.executor;

import com.baresql.compiler.Dialect;
import java.sql.PreparedStatement;

@FunctionalInterface
public interface RecordBinder<T> {
    void bind(T item, PreparedStatement ps, Dialect dialect) throws Exception;
}
