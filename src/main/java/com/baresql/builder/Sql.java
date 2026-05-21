package com.baresql.builder;

import com.baresql.ast.Nodes.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class Sql {
    public interface SqlExpr {
        Expr build();
        default SqlExpr eq(Object value) { return () -> new BinaryExpr(this.build(), Op.EQ, toExpr(value)); }
        default SqlExpr neq(Object value) { return () -> new BinaryExpr(this.build(), Op.NEQ, toExpr(value)); }
        default SqlExpr gt(Object value) { return () -> new BinaryExpr(this.build(), Op.GT, toExpr(value)); }
        default SqlExpr lt(Object value) { return () -> new BinaryExpr(this.build(), Op.LT, toExpr(value)); }
        default SqlExpr gte(Object value) { return () -> new BinaryExpr(this.build(), Op.GTE, toExpr(value)); }
        default SqlExpr lte(Object value) { return () -> new BinaryExpr(this.build(), Op.LTE, toExpr(value)); }
        default SqlExpr and(SqlExpr other) { return () -> new BinaryExpr(this.build(), Op.AND, other.build()); }
        default SqlExpr or(SqlExpr other) { return () -> new BinaryExpr(this.build(), Op.OR, other.build()); }
        default SqlExpr add(Object value) { return () -> new BinaryExpr(this.build(), Op.ADD, toExpr(value)); }
        default SqlExpr sub(Object value) { return () -> new BinaryExpr(this.build(), Op.SUB, toExpr(value)); }
        default SqlExpr mul(Object value) { return () -> new BinaryExpr(this.build(), Op.MUL, toExpr(value)); }
        default SqlExpr div(Object value) { return () -> new BinaryExpr(this.build(), Op.DIV, toExpr(value)); }
        default SqlExpr in(Subquery subquery) { return () -> new BinaryExpr(this.build(), Op.IN, subquery); }
        default SqlExpr notIn(Subquery subquery) { return () -> new BinaryExpr(this.build(), Op.NOT_IN, subquery); }
    }

    static Expr toExpr(Object value) {
        if (value instanceof Expr e) return e;
        return new Literal(value);
    }

    public static class Col implements SqlExpr {
        private final String name;
        private Col(String name) { this.name = name; }
        public static Col of(String name) { return new Col(name); }
        @Override public Expr build() { return new Column(name); }
    }

    // --- Aggregate functions ---
    public static SqlExpr count(String column) { return () -> new Aggregate(AggFunc.COUNT, new Column(column), false); }
    public static SqlExpr countDistinct(String column) { return () -> new Aggregate(AggFunc.COUNT, new Column(column), true); }
    public static SqlExpr sum(String column) { return () -> new Aggregate(AggFunc.SUM, new Column(column), false); }
    public static SqlExpr avg(String column) { return () -> new Aggregate(AggFunc.AVG, new Column(column), false); }
    public static SqlExpr min(String column) { return () -> new Aggregate(AggFunc.MIN, new Column(column), false); }
    public static SqlExpr max(String column) { return () -> new Aggregate(AggFunc.MAX, new Column(column), false); }

    // --- Window functions ---
    public static WindowFuncStep rowNumber() { return new WindowFuncBuilder(WindowFunc.ROW_NUMBER); }
    public static WindowFuncStep rank() { return new WindowFuncBuilder(WindowFunc.RANK); }
    public static WindowFuncStep denseRank() { return new WindowFuncBuilder(WindowFunc.DENSE_RANK); }
    public static WindowFuncArgsStep lag(Object column) { return new WindowFuncArgsBuilder(WindowFunc.LAG, toExpr(column)); }
    public static WindowFuncArgsStep lead(Object column) { return new WindowFuncArgsBuilder(WindowFunc.LEAD, toExpr(column)); }
    public static WindowFuncStep ntile(int n) { return new WindowFuncBuilder(WindowFunc.NTILE, List.of(new Literal(n))); }

    // --- INSERT ... SELECT ---
    public static InsertColumnsStep insertInto(String tableName) {
        return new InsertSelectBuilder(new Table(tableName));
    }

    // --- SELECT ---
    public static SelectStep select(String... columns) {
        List<Expr> cols = Arrays.stream(columns).map(Column::new).collect(Collectors.toList());
        return new SelectBuilder(cols);
    }

    public static SelectStep select(Expr... columnExprs) {
        List<Expr> cols = Arrays.asList(columnExprs);
        return new SelectBuilder(cols);
    }

    public static SelectStep select(SqlExpr... columnExprs) {
        List<Expr> cols = new ArrayList<>();
        for (SqlExpr e : columnExprs) cols.add(e.build());
        return new SelectBuilder(cols);
    }

    // --- DELETE ---
    public static DeleteWhereStep deleteFrom(String tableName) {
        return new DeleteBuilder(new Table(tableName));
    }

    // --- UPDATE ---
    public static UpdateSetStep update(String tableName) {
        return new UpdateBuilder(new Table(tableName));
    }

    // --- Step interfaces ---
    public interface SelectStep { FromStep from(String tableName); }
    public interface FromStep {
        WhereStep where(SqlExpr condition);
        JoinStep join(String tableName);
        JoinStep leftJoin(String tableName);
        JoinStep rightJoin(String tableName);
        JoinStep crossJoin(String tableName);
        OrderByStep orderBy(String column, boolean asc);
        GroupByStep groupBy(String... columns);
        LimitStep limit(int n);
        Statement build();
        Select buildSelect();
    }
    public interface WhereStep {
        OrderByStep orderBy(String column, boolean asc);
        GroupByStep groupBy(String... columns);
        LimitStep limit(int n);
        Statement build();
        Select buildSelect();
    }
    public interface JoinStep { FromStep on(SqlExpr condition); }
    public interface OrderByStep {
        OrderByStep orderBy(String column, boolean asc);
        LimitStep limit(int n);
        Statement build();
    }
    public interface GroupByStep {
        HavingStep having(SqlExpr condition);
        LimitStep limit(int n);
        Statement build();
    }
    public interface HavingStep { LimitStep limit(int n); Statement build(); }
    public interface LimitStep { LimitStep offset(int n); Statement build(); }

    public interface DeleteWhereStep { DeleteWhereStep where(SqlExpr condition); Statement build(); }
    public interface UpdateSetStep {
        UpdateSetStep set(String column, Object value);
        UpdateWhereStep where(SqlExpr condition);
        Statement build();
    }
    public interface UpdateWhereStep { Statement build(); }

    // --- Window function step interfaces ---
    public interface WindowFuncStep {
        WindowOverStep over();
    }
    public interface WindowFuncArgsStep {
        WindowOverStep over();
    }
    public interface WindowOverStep {
        WindowPartitionStep partitionBy(String... columns);
        SqlExpr orderBy(String column, boolean asc);
        SqlExpr build();
    }
    public interface WindowPartitionStep {
        SqlExpr orderBy(String column, boolean asc);
        SqlExpr build();
    }

    // --- INSERT...SELECT step interfaces ---
    public interface InsertColumnsStep {
        InsertSelectFromStep columns(String... columns);
    }
    public interface InsertSelectFromStep {
        InsertSelectStep select(String... columns);
    }
    public interface InsertSelectStep {
        InsertSelectWhereStep from(String tableName);
    }
    public interface InsertSelectWhereStep {
        InsertSelectWhereStep where(SqlExpr condition);
        Statement build();
    }

    // --- SELECT builder ---
    private static class SelectBuilder implements SelectStep, FromStep, WhereStep, OrderByStep, GroupByStep, HavingStep, LimitStep {
        private final List<Expr> columns;
        private Table table;
        private final List<Join> joins = new ArrayList<>();
        private Expr whereCondition;
        private final List<OrderBy> orderBy = new ArrayList<>();
        private List<Column> groupByColumns;
        private Expr havingCondition;
        private Integer limit;
        private Integer offset;

        private SelectBuilder(List<Expr> columns) { this.columns = columns; }

        @Override public FromStep from(String tableName) { this.table = new Table(tableName); return this; }
        @Override public WhereStep where(SqlExpr condition) { this.whereCondition = condition.build(); return this; }

        @Override public JoinStep join(String tableName) { return joinStep(JoinType.INNER, tableName); }
        @Override public JoinStep leftJoin(String tableName) { return joinStep(JoinType.LEFT, tableName); }
        @Override public JoinStep rightJoin(String tableName) { return joinStep(JoinType.RIGHT, tableName); }
        @Override public JoinStep crossJoin(String tableName) { return joinStep(JoinType.CROSS, tableName); }

        private JoinStep joinStep(JoinType type, String tableName) {
            return condition -> {
                joins.add(new Join(type, new Table(tableName), condition.build()));
                return this;
            };
        }

        @Override public OrderByStep orderBy(String column, boolean asc) {
            this.orderBy.add(new OrderBy(new Column(column), asc));
            return this;
        }
        @Override public GroupByStep groupBy(String... columns) {
            this.groupByColumns = Arrays.stream(columns).map(Column::new).collect(Collectors.toList());
            return this;
        }
        @Override public HavingStep having(SqlExpr condition) { this.havingCondition = condition.build(); return this; }
        @Override public LimitStep limit(int n) { this.limit = n; return this; }
        @Override public LimitStep offset(int n) { this.offset = n; return this; }

        @Override public Statement build() {
            GroupByClause gbc = groupByColumns != null ? new GroupByClause(groupByColumns, havingCondition) : null;
            return new Select(columns, table, joins, whereCondition, orderBy, gbc, limit, offset);
        }
        @Override public Select buildSelect() {
            GroupByClause gbc = groupByColumns != null ? new GroupByClause(groupByColumns, havingCondition) : null;
            return new Select(columns, table, joins, whereCondition, orderBy, gbc, limit, offset);
        }
    }

    // --- DELETE builder ---
    private static class DeleteBuilder implements DeleteWhereStep {
        private final Table table;
        private Expr whereCondition;
        private DeleteBuilder(Table table) { this.table = table; }
        @Override public DeleteWhereStep where(SqlExpr condition) { this.whereCondition = condition.build(); return this; }
        @Override public Statement build() { return new Delete(table, whereCondition); }
    }

    // --- UPDATE builder ---
    private static class UpdateBuilder implements UpdateSetStep, UpdateWhereStep {
        private final Table table;
        private final List<Assignment> assignments = new ArrayList<>();
        private Expr whereCondition;
        private UpdateBuilder(Table table) { this.table = table; }
        @Override public UpdateSetStep set(String column, Object value) {
            assignments.add(new Assignment(new Column(column), toExpr(value)));
            return this;
        }
        @Override public UpdateWhereStep where(SqlExpr condition) { this.whereCondition = condition.build(); return this; }
        @Override public Statement build() { return new Update(table, assignments, whereCondition); }
    }

    // --- Window function builders ---
    private static class WindowFuncBuilder implements WindowFuncStep, WindowOverStep, WindowPartitionStep {
        private final WindowFunc func;
        private final List<Expr> args;
        private final List<Column> partitionBy = new ArrayList<>();
        private final List<OrderBy> orderBy = new ArrayList<>();

        private WindowFuncBuilder(WindowFunc func) { this.func = func; this.args = List.of(); }
        private WindowFuncBuilder(WindowFunc func, List<Expr> args) { this.func = func; this.args = args; }

        @Override public WindowOverStep over() { return this; }
        @Override public WindowPartitionStep partitionBy(String... columns) {
            for (String c : columns) this.partitionBy.add(new Column(c));
            return this;
        }
        @Override public SqlExpr orderBy(String column, boolean asc) {
            this.orderBy.add(new OrderBy(new Column(column), asc));
            return build();
        }
        @Override public SqlExpr build() {
            return () -> new WindowExpr(func, args, new WindowSpec(List.copyOf(partitionBy), List.copyOf(orderBy)));
        }
    }

    private static class WindowFuncArgsBuilder implements WindowFuncArgsStep, WindowOverStep, WindowPartitionStep {
        private final WindowFunc func;
        private final List<Expr> args;
        private final List<Column> partitionBy = new ArrayList<>();
        private final List<OrderBy> orderBy = new ArrayList<>();

        private WindowFuncArgsBuilder(WindowFunc func, Expr arg) { this.func = func; this.args = List.of(arg); }

        @Override public WindowOverStep over() { return this; }
        @Override public WindowPartitionStep partitionBy(String... columns) {
            for (String c : columns) this.partitionBy.add(new Column(c));
            return this;
        }
        @Override public SqlExpr orderBy(String column, boolean asc) {
            this.orderBy.add(new OrderBy(new Column(column), asc));
            return build();
        }
        @Override public SqlExpr build() {
            return () -> new WindowExpr(func, args, new WindowSpec(List.copyOf(partitionBy), List.copyOf(orderBy)));
        }
    }

    // --- INSERT...SELECT builder ---
    private static class InsertSelectBuilder implements InsertColumnsStep, InsertSelectFromStep {
        private final Table table;
        private List<Column> insertColumns;

        private InsertSelectBuilder(Table table) { this.table = table; }

        @Override public InsertSelectFromStep columns(String... columns) {
            this.insertColumns = Arrays.stream(columns).map(Column::new).collect(Collectors.toList());
            return this;
        }
        @Override public InsertSelectStep select(String... columns) {
            List<Expr> cols = Arrays.stream(columns).map(Column::new).collect(Collectors.toList());
            return new InsertSelectStepBuilder(this, cols);
        }

        Statement buildWith(Select selectStmt) {
            return new InsertSelect(table, insertColumns, selectStmt);
        }
    }

    private static class InsertSelectStepBuilder implements InsertSelectStep, InsertSelectWhereStep {
        private final InsertSelectBuilder parent;
        private final List<Expr> selectColumns;
        private Table fromTable;
        private Expr whereCondition;

        private InsertSelectStepBuilder(InsertSelectBuilder parent, List<Expr> selectColumns) {
            this.parent = parent;
            this.selectColumns = selectColumns;
        }

        @Override public InsertSelectWhereStep from(String tableName) {
            this.fromTable = new Table(tableName);
            return this;
        }
        @Override public InsertSelectWhereStep where(SqlExpr condition) { this.whereCondition = condition.build(); return this; }
        @Override public Statement build() {
            Select selectStmt = new Select(selectColumns, fromTable, List.of(), whereCondition, List.of(), null, null, null);
            return parent.buildWith(selectStmt);
        }
    }
}
