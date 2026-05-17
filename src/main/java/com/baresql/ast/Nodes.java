package com.baresql.ast;

import com.baresql.types.SqlTypes.SqlType;
import java.util.List;
import java.util.Optional;

public interface Nodes {
    sealed interface SqlNode { }
    sealed interface Expr extends SqlNode { }
    sealed interface Statement extends SqlNode { }
    
    enum Op { EQ, NEQ, GT, LT, AND, OR }

    record Table(String name) implements SqlNode { }
    record Column(String name) implements Expr { }
    record Literal(Object value) implements Expr { }
    record Placeholder(int index) implements Expr { }
    record BinaryExpr(Expr left, Op op, Expr right) implements Expr { }
    record JsonExtractText(Expr jsonExpr, String key) implements Expr { }
    record ColumnDef(String name, SqlType type) implements SqlNode { }
    record Assignment(Column column, Expr expression) implements SqlNode { }

    record Select(List<Expr> columns, Table table, Expr whereCondition) implements Statement { }
    record Insert(Table table, List<ColumnDef> columns, List<Expr> values, Optional<List<Column>> returning) implements Statement { }
    record Upsert(Insert insert, List<Column> conflictColumns, List<Assignment> updateAssignments) implements Statement { }
}
