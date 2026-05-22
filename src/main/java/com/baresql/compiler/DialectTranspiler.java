package com.baresql.compiler;

import com.baresql.ast.Nodes.*;

public class DialectTranspiler {
    private final Dialect targetDialect;

    public DialectTranspiler(Dialect targetDialect) { this.targetDialect = targetDialect; }
    public void generate(Statement stmt, FastSqlBuffer out) { visit(stmt, out); }

    public void visit(SqlNode node, FastSqlBuffer out) {
        switch (node) {
            case Select s -> visitSelect(s, out);
            case Insert i -> new BaseCompiler().generateInsert(i, out, this);
            case Upsert u -> new UpsertCompiler().compile(u, out, targetDialect, this);
            case Delete d -> {
                out.write("DELETE FROM "); visit(d.table(), out);
                if (d.whereCondition() != null) { out.write(" WHERE "); visit(d.whereCondition(), out); }
            }
            case Update u -> {
                out.write("UPDATE "); visit(u.table(), out); out.write(" SET ");
                for (int i = 0; i < u.assignments().size(); i++) {
                    var a = u.assignments().get(i);
                    visit(a.column(), out); out.write(" = "); visit(a.expression(), out);
                    if (i < u.assignments().size() - 1) out.write(", ");
                }
                if (u.whereCondition() != null) { out.write(" WHERE "); visit(u.whereCondition(), out); }
            }
            case Truncate t -> { out.write("TRUNCATE TABLE "); visit(t.table(), out); }
            case CreateIndex ci -> {
                out.write("CREATE ");
                if (ci.unique()) out.write("UNIQUE ");
                out.write("INDEX "); out.writeIdentifier(ci.name());
                out.write(" ON "); visit(ci.table(), out); out.write(" (");
                for (int i = 0; i < ci.columns().size(); i++) {
                    visit(ci.columns().get(i), out);
                    if (i < ci.columns().size() - 1) out.write(", ");
                }
                out.write(")");
            }
            case Explain e -> { out.write("EXPLAIN "); visit(e.statement(), out); }
            case BeginTransaction _bt -> out.write(targetDialect == Dialect.SQL_SERVER ? "BEGIN TRANSACTION" : "BEGIN");
            case Commit _cm -> out.write("COMMIT");
            case Rollback _rb -> out.write("ROLLBACK");
            case Savepoint sp -> { out.write("SAVEPOINT "); out.writeIdentifier(sp.name()); }
            case Batch b -> {
                for (int i = 0; i < b.statements().size(); i++) {
                    visit(b.statements().get(i), out);
                    if (i < b.statements().size() - 1) out.write("; ");
                }
            }
            case JsonExtractText j -> {
                if (targetDialect == Dialect.POSTGRES) { visit(j.jsonExpr(), out); out.write(" ->> "); out.writeLiteral(j.key()); }
                else { out.write("json_extract("); visit(j.jsonExpr(), out); out.write(", '$."); out.write(j.key()); out.write("')"); }
            }
            case Join j -> {
                out.write(switch (j.type()) {
                    case INNER -> " INNER JOIN ";
                    case LEFT -> " LEFT JOIN ";
                    case RIGHT -> " RIGHT JOIN ";
                    case CROSS -> " CROSS JOIN ";
                });
                visit(j.table(), out);
                if (j.onCondition() != null) { out.write(" ON "); visit(j.onCondition(), out); }
            }
            case GroupByClause g -> {
                out.write(" GROUP BY ");
                for (int i = 0; i < g.columns().size(); i++) {
                    out.writeIdentifier(g.columns().get(i).name());
                    if (i < g.columns().size() - 1) out.write(", ");
                }
                if (g.havingCondition() != null) { out.write(" HAVING "); visit(g.havingCondition(), out); }
            }
            case Aggregate a -> {
                out.write(switch (a.func()) {
                    case COUNT -> "COUNT("; case SUM -> "SUM("; case AVG -> "AVG("; case MIN -> "MIN("; case MAX -> "MAX(";
                });
                if (a.distinct()) out.write("DISTINCT ");
                visit(a.expr(), out);
                out.write(")");
            }
            case Subquery sq -> {
                out.write("("); visit(sq.select(), out); out.write(")");
                sq.alias().ifPresent(a -> { out.write(" AS "); out.writeIdentifier(a); });
            }
            case InList il -> {
                visit(il.expr(), out);
                out.write(il.negated() ? " NOT IN (" : " IN (");
                for (int i = 0; i < il.values().size(); i++) {
                    visit(il.values().get(i), out);
                    if (i < il.values().size() - 1) out.write(", ");
                }
                out.write(")");
            }
            case WindowExpr w -> {
                out.write(switch (w.func()) {
                    case ROW_NUMBER -> "ROW_NUMBER("; case RANK -> "RANK("; case DENSE_RANK -> "DENSE_RANK(";
                    case LAG -> "LAG("; case LEAD -> "LEAD("; case NTILE -> "NTILE(";
                });
                for (int i = 0; i < w.args().size(); i++) { visit(w.args().get(i), out); if (i < w.args().size() - 1) out.write(", "); }
                out.write(") OVER ("); visitWindowSpec(w.window(), out); out.write(")");
            }
            case WindowAggExpr wa -> {
                out.write(switch (wa.func()) {
                    case COUNT -> "COUNT("; case SUM -> "SUM("; case AVG -> "AVG("; case MIN -> "MIN("; case MAX -> "MAX(";
                });
                if (wa.distinct()) out.write("DISTINCT ");
                visit(wa.expr(), out);
                out.write(") OVER ("); visitWindowSpec(wa.window(), out); out.write(")");
            }
            case InsertSelect ins -> {
                out.write("INSERT INTO "); visit(ins.table(), out); out.write(" (");
                for (int i = 0; i < ins.columns().size(); i++) { out.writeIdentifier(ins.columns().get(i).name()); if (i < ins.columns().size() - 1) out.write(", "); }
                out.write(") "); visit(ins.select(), out);
            }
            case Table t -> {
                out.writeIdentifier(t.name());
                t.alias().ifPresent(a -> { out.write(" AS "); out.writeIdentifier(a); });
            }
            case Column c -> {
                c.qualifier().ifPresent(q -> { out.writeIdentifier(q); out.write("."); });
                out.writeIdentifier(c.name());
                c.alias().ifPresent(a -> { out.write(" AS "); out.writeIdentifier(a); });
            }
            case Literal l -> out.writeLiteral(l.value());
            case Placeholder p -> out.write("?");
            case UnaryExpr u -> {
                out.write(opToStr(u.op()) + " "); visit(u.expr(), out);
            }
            case IsNull isn -> {
                visit(isn.expr(), out);
                out.write(isn.negated() ? " IS NOT NULL" : " IS NULL");
            }
            case Between b -> {
                visit(b.expr(), out);
                out.write(b.negated() ? " NOT BETWEEN " : " BETWEEN ");
                visit(b.low(), out); out.write(" AND "); visit(b.high(), out);
            }
            case CaseExpr ce -> {
                out.write("CASE");
                for (WhenClause wc : ce.whenClauses()) {
                    out.write(" WHEN "); visit(wc.condition(), out);
                    out.write(" THEN "); visit(wc.result(), out);
                }
                ce.elseExpr().ifPresent(e -> { out.write(" ELSE "); visit(e, out); });
                out.write(" END");
            }
            case CastExpr ct -> {
                out.write("CAST("); visit(ct.expr(), out); out.write(" AS "); out.write(ct.targetType()); out.write(")");
            }
            case CoalesceExpr co -> {
                out.write("COALESCE(");
                for (int i = 0; i < co.expressions().size(); i++) { visit(co.expressions().get(i), out); if (i < co.expressions().size() - 1) out.write(", "); }
                out.write(")");
            }
            case ConcatExpr cn -> {
                if (targetDialect == Dialect.MYSQL) {
                    out.write("CONCAT(");
                    for (int i = 0; i < cn.expressions().size(); i++) { visit(cn.expressions().get(i), out); if (i < cn.expressions().size() - 1) out.write(", "); }
                    out.write(")");
                } else {
                    for (int i = 0; i < cn.expressions().size(); i++) { visit(cn.expressions().get(i), out); if (i < cn.expressions().size() - 1) out.write(" || "); }
                }
            }
            case ExistsExpr ex -> { out.write("EXISTS ("); visit(ex.select(), out); out.write(")"); }
            case BinaryExpr b -> { visit(b.left(), out); out.write(" " + opToStr(b.op()) + " "); visit(b.right(), out); }
            case ColumnDef cd -> out.writeIdentifier(cd.name());
            case Assignment a -> { out.writeIdentifier(a.column().name()); out.write(" = "); visit(a.expression(), out); }
            case OrderBy ob -> { out.writeIdentifier(ob.column().name()); out.write(ob.asc() ? " ASC" : " DESC"); }
            case Cte c -> { out.writeIdentifier(c.name()); out.write(" AS ("); visit(c.query(), out); out.write(")"); }
            case WhenClause _wc -> { /* handled by CaseExpr */ }
            case SetOperation _so -> { /* handled by visitSelect */ }
            case WindowSpec _ws -> { /* handled by WindowExpr */ }
        }
    }

    private void visitSelect(Select s, FastSqlBuffer out) {
        // CTEs
        if (!s.ctes().isEmpty()) {
            out.write("WITH ");
            for (int i = 0; i < s.ctes().size(); i++) { visit(s.ctes().get(i), out); if (i < s.ctes().size() - 1) out.write(", "); }
            out.write(" ");
        }
        // SELECT
        if (s.distinct()) out.write("SELECT DISTINCT ");
        else out.write("SELECT ");
        for (int i = 0; i < s.columns().size(); i++) { visit(s.columns().get(i), out); if (i < s.columns().size() - 1) out.write(", "); }
        out.write(" FROM "); visit(s.table(), out);
        for (Join j : s.joins()) { visit(j, out); }
        if (s.whereCondition() != null) { out.write(" WHERE "); visit(s.whereCondition(), out); }
        if (s.groupBy() != null) { visit(s.groupBy(), out); }
        if (!s.orderBy().isEmpty()) {
            out.write(" ORDER BY ");
            for (int i = 0; i < s.orderBy().size(); i++) {
                OrderBy ob = s.orderBy().get(i);
                out.writeIdentifier(ob.column().name());
                out.write(ob.asc() ? " ASC" : " DESC");
                if (i < s.orderBy().size() - 1) out.write(", ");
            }
        }
        // LIMIT/OFFSET per dialect
        if (s.limit() != null || s.offset() != null) {
            if (targetDialect == Dialect.SQL_SERVER) {
                // SQL Server: OFFSET ... ROWS FETCH NEXT ... ROWS ONLY
                if (s.offset() != null) {
                    out.write(" OFFSET " + s.offset() + " ROWS");
                    if (s.limit() != null) out.write(" FETCH NEXT " + s.limit() + " ROWS ONLY");
                } else if (s.limit() != null) {
                    out.write(" OFFSET 0 ROWS FETCH NEXT " + s.limit() + " ROWS ONLY");
                }
            } else {
                if (s.limit() != null) out.write(" LIMIT " + s.limit());
                if (s.offset() != null) out.write(" OFFSET " + s.offset());
            }
        }
        // Set operations (UNION, INTERSECT, EXCEPT)
        for (SetOperation so : s.setOperations()) {
            out.write(" " + setOpToStr(so.op()) + " ");
            visit(so.select(), out);
        }
    }

    private void visitWindowSpec(WindowSpec ws, FastSqlBuffer out) {
        if (!ws.partitionBy().isEmpty()) {
            out.write("PARTITION BY ");
            for (int i = 0; i < ws.partitionBy().size(); i++) { out.writeIdentifier(ws.partitionBy().get(i).name()); if (i < ws.partitionBy().size() - 1) out.write(", "); }
        }
        if (!ws.orderBy().isEmpty()) {
            if (!ws.partitionBy().isEmpty()) out.write(" ");
            out.write("ORDER BY ");
            for (int i = 0; i < ws.orderBy().size(); i++) {
                OrderBy ob = ws.orderBy().get(i);
                out.writeIdentifier(ob.column().name()); out.write(ob.asc() ? " ASC" : " DESC");
                if (i < ws.orderBy().size() - 1) out.write(", ");
            }
        }
    }

    private String opToStr(Op op) {
        return switch (op) {
            case EQ -> "="; case NEQ -> "<>"; case GT -> ">"; case LT -> "<";
            case GTE -> ">="; case LTE -> "<=";
            case AND -> "AND"; case OR -> "OR"; case NOT -> "NOT";
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*"; case DIV -> "/";
            case IN -> "IN"; case NOT_IN -> "NOT IN";
            case LIKE -> "LIKE"; case NOT_LIKE -> "NOT LIKE";
        };
    }

    private String setOpToStr(SetOp op) {
        return switch (op) {
            case UNION -> "UNION"; case UNION_ALL -> "UNION ALL";
            case INTERSECT -> "INTERSECT"; case EXCEPT -> "EXCEPT";
        };
    }
}
