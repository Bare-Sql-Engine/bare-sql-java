package com.baresql.meta;

import com.baresql.builder.Sql.Col;

// Classe gerada estaticamente. Custo zero de memória em runtime.
public final class UsuarioMeta {
    public static final String TABLE = "usuarios";
    
    // Instâncias estáticas na Heap prontas para compor a AST instantaneamente
    public static final Col ID = Col.of("id");
    public static final Col NOME = Col.of("nome");
    public static final Col IDADE = Col.of("idade");
    
    private UsuarioMeta() {} // Impede instanciação
}
