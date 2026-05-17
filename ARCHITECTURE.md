# Documentação Arquitetural: Bare-SQL Engine

Bem-vindo à documentação arquitetural oficial do **Bare-SQL Engine**. Este documento descreve as decisões de design, os componentes principais e os fluxos de dados do motor, com o objetivo de fornecer uma visão aprofundada de como o sistema funciona sob o capô.

---

## 1. Visão Geral e Filosofia

O **Bare-SQL Engine** nasceu da necessidade de se ter um motor de banco de dados e gerador de queries que seja **rápido**, **sem reflexão (zero-reflection)**, **seguro em tempo de compilação (AOT - Ahead-Of-Time)** e **agnóstico a dialetos**.

A principal motivação é eliminar as perdas de performance comuns em ORMs tradicionais (como o Hibernate) e a complexidade de manutenção de suítes de testes pesadas (Testcontainers) ou inconsistentes (H2 Database). O Bare-SQL atinge isso através de uma abordagem baseada em **Compiladores**.

### Decisões de Design:
- **Separação de Fases (Frontend, Middle-end, Backend):** Inspirado em compiladores como LLVM, o motor divide o trabalho em:
  - **Frontend:** Builder Fluente construindo a AST (Abstract Syntax Tree).
  - **Middle-end:** Transformação para IR (Intermediate Representation) e otimizações baseadas em SSA (Static Single Assignment) como CSE (Common Subexpression Elimination).
  - **Backend:** Transpilação de AST para a sintaxe específica do banco (Dialect) e compilação AOT.
- **Zero-Reflection Row Mapping:** Em vez de usar reflexão Java (que é custosa) para mapear resultados `ResultSet` para entidades, usamos lambdas funcionais diretas.
- **Transpilação Agnóstica:** As queries são construídas em uma AST neutra e transpiladas dinamicamente para PostgreSQL, SQLite, MySQL, etc., o que viabiliza o uso de SQLite em memória para 95% dos testes unitários/integração.
- **AOT (Ahead-of-Time):** Capacidade de pré-compilar e resolver as árvores de SQL durante o build time, gerando strings literais constantes, o que zera o overhead de montagem de string em runtime.

---

## 2. Arquitetura do Sistema

O diagrama abaixo ilustra o fluxo de dados desde a chamada do desenvolvedor até a execução no banco de dados.

```mermaid
flowchart TD
    A[Desenvolvedor / Aplicação] -->|Fluent API| B(Frontend: AST Builder)
    B -->|Gera AST Bruta| C{Fase de Compilação?}
    
    C -->|Modo Runtime| D[Backend: Dialect Transpiler]
    C -->|Modo Optimizer/AOT| E[Middle-End: AstToIrPass]
    
    E -->|Gera IR| F[Otimizador IR SSA / CSE]
    F -->|IR Otimizada| G[Middle-End: IrToAstPass]
    G -->|AST Otimizada| H{AOT ou Runtime?}
    
    H -->|AOT Build Time| I[Geração de Constantes Java]
    H -->|Runtime| D
    
    D -->|Escreve em| J(FastSqlBuffer)
    J -->|SQL + Dialect| K[BareMetalExecutor]
    K -->|JDBC PreparedStatement| L[(Banco de Dados Relacional)]
    L -->|ResultSet| M[Zero-Reflection Mapper]
    M -->|Instâncias| A
```

---

## 3. Ciclo de Vida da Query

A vida de uma query dentro do Bare-SQL passa por várias transformações de estado para garantir segurança, otimização e compatibilidade.

```mermaid
stateDiagram-v2
    [*] --> RawExpression: Sql.select()
    RawExpression --> AST_Construida: .build()
    
    state AST_Construida {
        [*] --> Analise
        Analise --> Valida
    }
    
    AST_Construida --> IR_Representation: AstToIrPass.visit()
    
    state IR_Representation {
        [*] --> Deteccao_Redundancias (CSE)
        Deteccao_Redundancias --> Avaliacao_Constantes
        Avaliacao_Constantes --> Remocao_Codigo_Morto
    }
    
    IR_Representation --> AST_Otimizada: IrToAstPass.reconstruct()
    AST_Otimizada --> Transpilacao: DialectTranspiler.generate()
    
    state Transpilacao {
        [*] --> Analise_Dialeto
        Analise_Dialeto --> Resolucao_Especificidades (ex: JSON)
        Resolucao_Especificidades --> Escrita_Buffer (FastSqlBuffer)
    }
    
    Transpilacao --> SQL_Dialeto_Pronto: Buffer Completo
    SQL_Dialeto_Pronto --> Execucao_JDBC: BareMetalExecutor
    Execucao_JDBC --> [*]
```

### Detalhamento das Transições:
1. **Frontend (AST):** Os nós da árvore (`Nodes.Select`, `Nodes.BinaryExpr`, etc.) representam a intenção pura, sem amarras com a sintaxe do banco de dados.
2. **Otimização (IR & SSA):** A passagem para IR (Intermediate Representation) mapeia variáveis virtuais e remove duplicações através da eliminação de subexpressões comuns (CSE). Exemplo: `idade > 18 AND idade > 18` vira apenas `idade > 18`.
3. **Backend (Transpiler):** O `DialectTranspiler` pega a AST otimizada e decide regras granulares. Se for SQLite e houver um campo JSON, ele gera `json_extract()`; se for Postgres, ele gera o operador `->>`.

---

## 4. O Fluxo de Execução (Executor)

O `BareMetalExecutor` foi projetado para extrair cada gota de performance do JDBC. Ele gerencia as conexões e os fluxos de dados em lote.

```mermaid
sequenceDiagram
    participant App as Aplicação
    participant Builder as Sql Builder
    participant Transpiler as DialectTranspiler
    participant Executor as BareMetalExecutor
    participant JDBC as Driver JDBC (Conn/PS)
    participant DB as Banco de Dados
    
    App->>Builder: Sql.select("id", "nome").from("usuarios").build()
    Builder-->>App: AST Statement
    
    App->>Executor: query(AST, rowMapper)
    Executor->>Transpiler: generate(AST, buffer)
    Transpiler-->>Executor: SQL String Pronta (ex: Postgres)
    
    Executor->>JDBC: prepareStatement(SQL)
    JDBC-->>Executor: PreparedStatement
    
    Executor->>JDBC: executeQuery()
    JDBC->>DB: Executa Consulta
    DB-->>JDBC: Dados
    JDBC-->>Executor: ResultSet
    
    loop Para cada linha
        Executor->>App: rowMapper.map(ResultSet)
        Note right of App: A conversão é feita sem <br/>Reflection, usando lambdas.
        App-->>Executor: Entidade (ex: Usuario)
    end
    
    Executor-->>App: List<Usuario>
```

---

## 5. Destaques das Decisões Técnicas

### Por que não usar Hibernate?
O Hibernate é flexível, mas o custo dessa flexibilidade é a utilização massiva de *Java Reflection*, proxies, e um "L1 Cache" complexo que resulta em consumo imprevisível de memória e latência de CPU. O **Bare-SQL** resolve o RowMapping injetando explicitamente uma função lambda funcional (Zero-Reflection), tornando o fluxo de dados em um *pipeline* previsível.

### Estratégia de Testes (SQLite Mocks)
Com o Dialect Transpiler, os testes de CI/CD podem trocar o alvo de compilação de `POSTGRES` para `SQLITE` na hora da injeção de dependência. Isso substitui em ~95% dos casos o H2 Database ou Testcontainers, executando na memória nativa em frações de milissegundos sem perder precisão nas conversões estruturais.

### Ahead-of-Time Compilation (AOT)
Para caminhos críticos ("Hot Paths") na aplicação, a geração do SQL e otimização da AST é feita por um gerador (Mojo Maven/Gradle) que cospe uma classe Java com `public static final String`. Durante o runtime, o motor pula toda a etapa de alocação de buffer e transpilação, batendo direto no JDBC com a string otimizada e imutável.

---

## 6. Conclusão

A arquitetura orientada a compiladores do `bare-sql-engine` o coloca como uma ferramenta moderna, combinando a fluidez das APIs orientadas a objetos com o rigor matemático das otimizações de IR e a performance bruta das execuções estáticas (Bare-Metal e AOT).
