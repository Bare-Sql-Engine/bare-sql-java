package com.baresql.aot;

/**
 * CLASSE GERADA AUTOMATICAMENTE (AHEAD-OF-TIME).
 * Nao edite manualmente. Otimizacao SSA ja aplicada.
 */
public final class PrecompiledQueries {
    private PrecompiledQueries() {}

    // Query: GET_USUARIOS_MAIORES_IDADE [POSTGRES]
    public static final String GET_USUARIOS_MAIORES_IDADE_POSTGRES = "SELECT \"id\", \"nome\", \"idade\" FROM \"usuarios\" WHERE \"idade\" > ?";

    // Query: GET_USUARIOS_MAIORES_IDADE [SQLITE]
    public static final String GET_USUARIOS_MAIORES_IDADE_SQLITE = "SELECT \"id\", \"nome\", \"idade\" FROM \"usuarios\" WHERE \"idade\" > ?";

    // Query: GET_USUARIOS_MAIORES_IDADE [MYSQL]
    public static final String GET_USUARIOS_MAIORES_IDADE_MYSQL = "SELECT `id`, `nome`, `idade` FROM `usuarios` WHERE `idade` > ?";

    // Query: GET_USUARIOS_MAIORES_IDADE [SQL_SERVER]
    public static final String GET_USUARIOS_MAIORES_IDADE_SQL_SERVER = "SELECT [id], [nome], [idade] FROM [usuarios] WHERE [idade] > ?";
}
