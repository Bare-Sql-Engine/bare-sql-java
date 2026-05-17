package com.baresql.executor;

import com.baresql.compiler.Dialect;
import com.baresql.types.SqlTypes.*;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ParameterBinder {
    private static final ObjectMapper jsonMapper = new ObjectMapper();

    public static Object coerceForDialect(Object value, SqlType logicalType, Dialect targetDialect) throws Exception {
        if (targetDialect == Dialect.SQLITE) {
            return switch (logicalType) {
                case SqlJsonB j -> {
                    if (value instanceof String s) yield s;
                    yield jsonMapper.writeValueAsString(value);
                }
                case SqlUuid u -> value.toString();
                case SqlArray a -> jsonMapper.writeValueAsString(value); // Serializa array no SQLite
                default -> value;
            };
        }
        return value; 
    }
}
