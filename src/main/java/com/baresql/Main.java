package com.baresql;

import com.baresql.ast.Nodes.Statement;
import com.baresql.builder.Sql;
import com.baresql.compiler.Dialect;
import com.baresql.executor.BareMetalExecutor;
import com.baresql.meta.UsuarioMeta;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

public class Main {
    // Nosso DTO Imutável (Java 14+)
    public record Usuario(String id, String nome) {}

    public static void main(String[] args) throws Exception {
        System.out.println("=== Bare-SQL Engine: Metamodelo & Zero-Reflection ===");

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            // Setup do banco
            conn.createStatement().execute("CREATE TABLE usuarios (id TEXT, nome TEXT, idade INTEGER)");
            conn.createStatement().execute("INSERT INTO usuarios VALUES ('u-1', 'Linus Torvalds', 54)");
            conn.createStatement().execute("INSERT INTO usuarios VALUES ('u-2', 'Tux', 30)");

            BareMetalExecutor executor = new BareMetalExecutor(conn, Dialect.SQLITE);

            // 1. A QUERY USANDO O METAMODELO (Segurança de Compilação Total)
            // Se "UsuarioMeta.IDADE" for alterado no modelo, o código nem compila!
            Statement query = Sql.select(UsuarioMeta.ID.build(), UsuarioMeta.NOME.build())
                .from(UsuarioMeta.TABLE)
                .where(UsuarioMeta.IDADE.gt(18))
                .build();

            // 2. A EXECUÇÃO USANDO O ROW MAPPER (Zero-Reflection)
            // Lemos o ResultSet criando os Records diretamente. 
            // O Garbage Collector agradece.
            List<Usuario> resultados = executor.query(query, rs -> new Usuario(
                rs.getString("id"),
                rs.getString("nome")
            ));

            System.out.println("\n[Resultados da Query via Record Puros]:");
            resultados.forEach(System.out::println);
        }
    }
}
