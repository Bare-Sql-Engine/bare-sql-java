package com.baresql.executor;

import java.sql.ResultSet;

@FunctionalInterface
public interface RowMapper<T> {
    // Mapeamento puramente funcional. O JIT Compiler faz o inline disso.
    T map(ResultSet rs) throws Exception;
}
