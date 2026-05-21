package com.baresql.compiler;

import com.baresql.ast.Nodes.*;

public class DialectTranspiler {
    private final Dialect targetDialect;

    public DialectTranspiler(Dialect targetDialect) { this.targetDialect = targetDialect; }
    public void generate(Statement stmt, FastSqlBuffer out) { visit(stmt, out); }

    public void visit(SqlNode node, FastSqlBuffer out) {
        switch (node) {
            case Select s -> {
                out.write("SELECT ");
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
                if (s.limit() != null) { out.write(" LIMIT " + s.limit()); }
                if (s.offset() != null) { out.write(" OFFSET " + s.offset()); }
            }
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
                    out.writeIdentifier(a.column().name()); out.write(" = "); visit(a.expression(), out);
                    if (i < u.assignments().size() - 1) out.write(", ");
                }
                if (u.whereCondition() != null) { out.write(" WHERE "); visit(u.whereCondition(), out); }
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
                    case COUNT -> "COUNT(";
                    case SUM -> "SUM(";
                    case AVG -> "AVG(";
                    case MIN -> "MIN(";
                    case MAX -> "MAX(";
                });
                if (a.distinct()) out.write("DISTINCT ");
                visit(a.expr(), out);
                out.write(")");
            }
            case Subquery sq -> {
                out.write("(");
                visit(sq.select(), out);
                out.write(")");
            }
            case WindowExpr w -> {
                out.write(switch (w.func()) {
                    case ROW_NUMBER -> "ROW_NUMBER(";
                    case RANK -> "RANK(";
                    case DENSE_RANK -> "DENSE_RANK(";
                    case LAG -> "LAG(";
                    case LEAD -> "LEAD(";
                    case NTILE -> "NTILE(";
                });
                for (int i = 0; i < w.args().size(); i++) {
                    visit(w.args().get(i), out);
                    if (i < w.args().size() - 1) out.write(", ");
                }
                out.write(") OVER (");
                WindowSpec ws = w.window();
                if (!ws.partitionBy().isEmpty()) {
                    out.write("PARTITION BY ");
                    for (int i = 0; i < ws.partitionBy().size(); i++) {
                        out.writeIdentifier(ws.partitionBy().get(i).name());
                        if (i < ws.partitionBy().size() - 1) out.write(", ");
                    }
                }
                if (!ws.orderBy().isEmpty()) {
                    if (!ws.partitionBy().isEmpty()) out.write(" ");
                    out.write("ORDER BY ");
                    for (int i = 0; i < ws.orderBy().size(); i++) {
                        OrderBy ob = ws.orderBy().get(i);
                        out.writeIdentifier(ob.column().name());
                        out.write(ob.asc() ? " ASC" : " DESC");
                        if (i < ws.orderBy().size() - 1) out.write(", ");
                    }
                }
                out.write(")");
            }
            case InsertSelect ins -> {
                out.write("INSERT INTO "); visit(ins.table(), out);
                out.write(" (");
                for (int i = 0; i < ins.columns().size(); i++) {
                    out.writeIdentifier(ins.columns().get(i).name());
                    if (i < ins.columns().size() - 1) out.write(", ");
                }
                out.write(") ");
                visit(ins.select(), out);
            }
            case Table t -> out.writeIdentifier(t.name());
            case Column c -> out.writeIdentifier(c.name());
            case Literal l -> out.writeLiteral(l.value());
            case Placeholder p -> out.write("?");
            case BinaryExpr b -> { visit(b.left(), out); out.write(" " + opToStr(b.op()) + " "); visit(b.right(), out); }
            case ColumnDef cd -> out.writeIdentifier(cd.name());
            case Assignment a -> { out.writeIdentifier(a.column().name()); out.write(" = "); visit(a.expression(), out); }
            case OrderBy ob -> { out.writeIdentifier(ob.column().name()); out.write(ob.asc() ? " ASC" : " DESC"); }
            default -> throw new UnsupportedOperationException(
                "Nó AST não suportado pelo transpiler: " + node.getClass().getSimpleName() +
                ". Verifique se todos os tipos de nó são tratados no switch.");
        }
    }

    private String opToStr(Op op) {
        return switch (op) {
            case EQ -> "="; case NEQ -> "<>"; case GT -> ">"; case LT -> "<";
            case GTE -> ">="; case LTE -> "<=";
            case AND -> "AND"; case OR -> "OR";
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*"; case DIV -> "/";
            case IN -> "IN"; case NOT_IN -> "NOT IN";
        };
    }
}
