package com.baresql.ast;

import com.baresql.types.SqlTypes.SqlType;
import java.util.List;
import java.util.Optional;

public interface Nodes {
    sealed interface SqlNode { }
    sealed interface Expr extends SqlNode { }
    sealed interface Statement extends SqlNode { }

    enum Op { EQ, NEQ, GT, LT, GTE, LTE, AND, OR, NOT, ADD, SUB, MUL, DIV, IN, NOT_IN, LIKE, NOT_LIKE }
    enum JoinType { INNER, LEFT, RIGHT, CROSS }
    enum AggFunc { COUNT, SUM, AVG, MIN, MAX }
    enum WindowFunc { ROW_NUMBER, RANK, DENSE_RANK, LAG, LEAD, NTILE }
    enum SetOp { UNION, UNION_ALL, INTERSECT, EXCEPT }

    record Table(String name, Optional<String> alias) implements SqlNode {
        public Table(String name) { this(name, Optional.empty()); }
    }
    record Column(String name, Optional<String> qualifier, Optional<String> alias) implements Expr {
        public Column(String name) { this(name, Optional.empty(), Optional.empty()); }
        public Column(String name, String qualifier) { this(name, Optional.of(qualifier), Optional.empty()); }
    }
    record Literal(Object value) implements Expr { }
    record Placeholder(int index) implements Expr { }
    record BinaryExpr(Expr left, Op op, Expr right) implements Expr { }
    record UnaryExpr(Op op, Expr expr) implements Expr { }
    record IsNull(Expr expr, boolean negated) implements Expr { }
    record Between(Expr expr, Expr low, Expr high, boolean negated) implements Expr { }
    record CaseExpr(List<WhenClause> whenClauses, Optional<Expr> elseExpr) implements Expr { }
    record WhenClause(Expr condition, Expr result) implements SqlNode { }
    record JsonExtractText(Expr jsonExpr, String key) implements Expr { }
    record ColumnDef(String name, SqlType type) implements SqlNode { }
    record Assignment(Column column, Expr expression) implements SqlNode { }
    record Aggregate(AggFunc func, Expr expr, boolean distinct) implements Expr { }
    record Subquery(Select select, Optional<String> alias) implements Expr {
        public Subquery(Select select) { this(select, Optional.empty()); }
    }
    record InList(Expr expr, List<Expr> values, boolean negated) implements Expr { }
    record WindowSpec(List<Column> partitionBy, List<OrderBy> orderBy) implements SqlNode { }
    record WindowExpr(WindowFunc func, List<Expr> args, WindowSpec window) implements Expr { }
    // Window function with aggregate: SUM(x) OVER (...)
    record WindowAggExpr(AggFunc func, Expr expr, boolean distinct, WindowSpec window) implements Expr { }
    record CastExpr(Expr expr, String targetType) implements Expr { }
    record CoalesceExpr(List<Expr> expressions) implements Expr { }
    record ConcatExpr(List<Expr> expressions) implements Expr { }
    record ExistsExpr(Select select) implements Expr { }

    record Join(JoinType type, Table table, Expr onCondition) implements SqlNode { }
    record OrderBy(Column column, boolean asc) implements SqlNode { }
    record GroupByClause(List<Column> columns, Expr havingCondition) implements SqlNode { }
    record Cte(String name, Select query) implements SqlNode { }

    record Select(
        List<Expr> columns, Table table, List<Join> joins,
        Expr whereCondition, List<OrderBy> orderBy,
        GroupByClause groupBy, Integer limit, Integer offset,
        boolean distinct, List<Cte> ctes, List<SetOperation> setOperations
    ) implements Statement {
        public Select(List<Expr> columns, Table table, List<Join> joins,
                      Expr whereCondition, List<OrderBy> orderBy,
                      GroupByClause groupBy, Integer limit, Integer offset) {
            this(columns, table, joins, whereCondition, orderBy, groupBy, limit, offset, false, List.of(), List.of());
        }
    }

    record SetOperation(SetOp op, Select select) implements SqlNode { }

    record Insert(Table table, List<ColumnDef> columns, List<Expr> values, Optional<List<Column>> returning) implements Statement { }
    record InsertSelect(Table table, List<Column> columns, Select select) implements Statement { }
    record Upsert(Insert insert, List<Column> conflictColumns, List<Assignment> updateAssignments) implements Statement { }
    record Delete(Table table, Expr whereCondition) implements Statement { }
    record Update(Table table, List<Assignment> assignments, Expr whereCondition) implements Statement { }
    record Truncate(Table table) implements Statement { }
    record CreateIndex(String name, Table table, List<Column> columns, boolean unique) implements Statement { }
    record Explain(Statement statement) implements Statement { }
    record BeginTransaction() implements Statement { }
    record Commit() implements Statement { }
    record Rollback() implements Statement { }
    record Savepoint(String name) implements Statement { }
    record Batch(List<Statement> statements) implements Statement { }
}
