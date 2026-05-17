package com.baresql.builder;

import com.baresql.ast.Nodes.*;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class Sql {
    public interface SqlExpr {
        Expr build();
        default SqlExpr eq(Object value) { return () -> new BinaryExpr(this.build(), Op.EQ, new Literal(value)); }
        default SqlExpr gt(Object value) { return () -> new BinaryExpr(this.build(), Op.GT, new Literal(value)); }
        default SqlExpr and(SqlExpr other) { return () -> new BinaryExpr(this.build(), Op.AND, other.build()); }
    }

    public static class Col implements SqlExpr {
        private final String name;
        private Col(String name) { this.name = name; }
        public static Col of(String name) { return new Col(name); }
        @Override public Expr build() { return new Column(name); }
    }

    public static SelectStep select(String... columns) {
        List<Expr> cols = Arrays.stream(columns).map(Column::new).collect(Collectors.toList());
        return new BuilderImpl(cols);
    }

    // Sobrecarga para aceitar Expr diretamente (para usar com Metamodelos)
    public static SelectStep select(Expr... columnExprs) {
        List<Expr> cols = Arrays.asList(columnExprs);
        return new BuilderImpl(cols);
    }

    public interface SelectStep { FromStep from(String tableName); }
    public interface FromStep { WhereStep where(SqlExpr condition); Statement build(); }
    public interface WhereStep { Statement build(); }

    private static class BuilderImpl implements SelectStep, FromStep, WhereStep {
        private final List<Expr> columns;
        private Table table;
        private Expr whereCondition;

        private BuilderImpl(List<Expr> columns) { this.columns = columns; }
        @Override public FromStep from(String tableName) { this.table = new Table(tableName); return this; }
        @Override public WhereStep where(SqlExpr condition) { this.whereCondition = condition.build(); return this; }
        @Override public Statement build() { return new Select(columns, table, whereCondition); }
    }
}
