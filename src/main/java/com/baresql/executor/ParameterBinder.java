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

    private static Object coerceForSqlite(Object value, SqlType logicalType) {
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
                // Use reflection-safe approach: check if PGobject is available
                try {
                    Class<?> pgObjClass = Class.forName("org.postgresql.util.PGobject");
                    var pgObj = pgObjClass.getDeclaredConstructor().newInstance();
                    pgObjClass.getMethod("setType", String.class).invoke(pgObj, "jsonb");
                    pgObjClass.getMethod("setValue", String.class).invoke(pgObj, value.toString());
                    yield pgObj;
                } catch (ClassNotFoundException e) {
                    // Postgres driver not on classpath — fall back to String
                    yield value.toString();
                }
            }
            case SqlArray a -> {
                // Postgres arrays: convert to java.sql.Array via Connection.createArray()
                // Since we don't have Connection here, store as Object[] for the executor to handle
                if (value instanceof Object[] arr) {
                    yield arr; // Executor should call conn.createArray() with this
                }
                yield value.toString();
            }
            default -> value;
        };
    }

    private static Object coerceForMysql(Object value, SqlType logicalType) {
        return switch (logicalType) {
            case SqlUuid u -> value.toString();
            case SqlJsonB j -> value.toString();
            default -> value;
        };
    }

    private static Object coerceForSqlServer(Object value, SqlType logicalType) {
        return switch (logicalType) {
            case SqlUuid u -> value instanceof java.util.UUID ? value.toString() : value;
            case SqlJsonB j -> value.toString();
            default -> value;
        };
    }
}
