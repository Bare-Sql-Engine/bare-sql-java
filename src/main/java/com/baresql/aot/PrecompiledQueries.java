package com.baresql.aot;

/**
 * CLASSE GERADA AUTOMATICAMENTE (AHEAD-OF-TIME).
 * Não edite manualmente. Otimização SSA já aplicada.
 */
public final class PrecompiledQueries {
    private PrecompiledQueries() {}

    // Query: GET_USUARIOS_MAIORES_IDADE
    public static final String GET_USUARIOS_MAIORES_IDADE_POSTGRES = "SELECT \"id\", \"nome\", \"idade\" FROM \"usuarios\" WHERE \"idade\" > ?";
    public static final String GET_USUARIOS_MAIORES_IDADE_SQLITE = "SELECT \"id\", \"nome\", \"idade\" FROM \"usuarios\" WHERE \"idade\" > ?";
}
