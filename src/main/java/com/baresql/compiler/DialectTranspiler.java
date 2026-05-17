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
                if (s.whereCondition() != null) { out.write(" WHERE "); visit(s.whereCondition(), out); }
            }
            case Insert i -> new BaseCompiler().generateInsert(i, out, this);
            case Upsert u -> new UpsertCompiler().compile(u, out, targetDialect, this);
            case JsonExtractText j -> {
                if (targetDialect == Dialect.POSTGRES) { visit(j.jsonExpr(), out); out.write(" ->> "); out.writeLiteral(j.key()); } 
                else { out.write("json_extract("); visit(j.jsonExpr(), out); out.write(", '$."); out.write(j.key()); out.write("')"); }
            }
            case Table t -> out.writeIdentifier(t.name());
            case Column c -> out.writeIdentifier(c.name());
            case Literal l -> out.writeLiteral(l.value());
            case Placeholder p -> out.write("?");
            case BinaryExpr b -> { visit(b.left(), out); out.write(" " + opToStr(b.op()) + " "); visit(b.right(), out); }
            default -> throw new UnsupportedOperationException("Nó não implementado: " + node.getClass());
        }
    }

    private String opToStr(Op op) {
        return switch (op) { case EQ -> "="; case NEQ -> "<>"; case GT -> ">"; case LT -> "<"; case AND -> "AND"; case OR -> "OR"; };
    }
}
