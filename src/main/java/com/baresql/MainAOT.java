package com.baresql;

import com.baresql.aot.PrecompiledQueries;

public class MainAOT {
    public static void main(String[] args) {
        System.out.println("=== Bare-SQL Engine: Execução AOT ===");
        
        System.out.println("Em tempo de execução (Runtime), a AST não é instanciada.");
        System.out.println("O otimizador não gasta ciclos de CPU.");
        System.out.println("Basta injetar a constante pré-compilada direto no JDBC:\n");

        // Acedemos diretamente ao campo estático gerado!
        String sqlPostgres = PrecompiledQueries.GET_USUARIOS_MAIORES_IDADE_POSTGRES;
        String sqlSqlite = PrecompiledQueries.GET_USUARIOS_MAIORES_IDADE_SQLITE;

        System.out.println("[Runtime] String Otimizada para Postgres: " + sqlPostgres);
        System.out.println("[Runtime] String Otimizada para SQLite:   " + sqlSqlite);
        
        System.out.println("\nRepare que a expressão booleana (idade > 18 AND idade > 18) foi dobrada em tempo de compilação pelo motor SSA.");
    }
}
