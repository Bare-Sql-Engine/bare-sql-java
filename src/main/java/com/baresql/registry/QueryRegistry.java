package com.baresql.registry;

import com.baresql.ast.Nodes.Statement;
import com.baresql.builder.Sql;
import com.baresql.builder.Sql.Col;

import java.util.HashMap;
import java.util.Map;

// Aqui a equipa define as queries com a nossa Fluent API.
// Isto é lido APENAS em tempo de compilação.
public class QueryRegistry {
    
    public static Map<String, Statement> getQueries() {
        Map<String, Statement> queries = new HashMap<>();
        
        queries.put("GET_USUARIOS_MAIORES_IDADE", Sql.select("id", "nome", "idade")
            .from("usuarios")
            .where(Col.of("idade").gt(18).and(Col.of("idade").gt(18))) // Expressão com redundância para testar o Otimizador AOT
            .build()
        );
        
        return queries;
    }
}
