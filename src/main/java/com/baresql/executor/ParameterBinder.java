package com.baresql.executor;

import com.baresql.compiler.Dialect;
import com.baresql.types.SqlTypes;
import com.baresql.types.SqlTypes.*;

public class ParameterBinder {

    public static Object coerceForDialect(Object value, SqlType logicalType, Dialect targetDialect) throws Exception {
        if (value == null) return null;

        return switch (targetDialect) {
            case SQLITE -> coerceForSqlite(value, logicalType);
            case POSTGRES -> coerceForPostgres(value, logicalType);
            case MYSQL -> coerceForMysql(value, logicalType);
            case SQL_SERVER -> coerceForSqlServer(value, logicalType);
        };
    }

    private static Object coerceForSqlite(Object value, SqlType logicalType) throws Exception {
        return switch (logicalType) {
            case SqlUuid u -> value.toString();
            case SqlJsonB j -> value.toString();
            case SqlArray a -> value.toString();
            default -> value;
        };
    }

    private static Object coerceForPostgres(Object value, SqlType logicalType) throws Exception {
        return switch (logicalType) {
            case SqlUuid u -> value instanceof java.util.UUID ? value : java.util.UUID.fromString(value.toString());
            case SqlJsonB j -> {
                var pgObj = new org.postgresql.util.PGobject();
                pgObj.setType("jsonb");
                pgObj.setValue(value.toString());
                yield pgObj;
            }
            case SqlArray a -> {
                if (value instanceof Object[] arr) {
                    yield java.sql.Array.class.getMethod("createArray").invoke(null);
                }
                yield value;
            }
            default -> value;
        };
    }

    private static Object coerceForMysql(Object value, SqlType logicalType) throws Exception {
        return switch (logicalType) {
            case SqlUuid u -> value.toString();
            case SqlJsonB j -> value.toString();
            default -> value;
        };
    }

    private static Object coerceForSqlServer(Object value, SqlType logicalType) throws Exception {
        return switch (logicalType) {
            case SqlUuid u -> value instanceof java.util.UUID ? value.toString() : value;
            case SqlJsonB j -> value.toString();
            default -> value;
        };
    }
}
