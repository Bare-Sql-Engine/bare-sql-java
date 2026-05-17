package com.baresql.compiler;

import com.baresql.ast.Nodes.*;

public class UpsertCompiler {
    public void compile(Upsert upsert, FastSqlBuffer out, Dialect dialect, DialectTranspiler main) {
        new BaseCompiler().generateInsert(upsert.insert(), out, main);
        switch (dialect) {
            case POSTGRES, SQLITE -> compilePostgresStyle(upsert, out, main);
            case MYSQL -> compileMysqlStyle(upsert, out, main);
            case SQL_SERVER -> out.write("\n/* SQL Server requer refatoração MERGE INTO */");
        }
    }

    private void compilePostgresStyle(Upsert u, FastSqlBuffer out, DialectTranspiler main) {
        out.write(" ON CONFLICT (");
        for (int i = 0; i < u.conflictColumns().size(); i++) {
            out.writeIdentifier(u.conflictColumns().get(i).name());
            if (i < u.conflictColumns().size() - 1) out.write(", ");
        }
        out.write(") DO UPDATE SET "); generateAssignments(u, out, "EXCLUDED.", main);
    }

    private void compileMysqlStyle(Upsert u, FastSqlBuffer out, DialectTranspiler main) {
        out.write(" ON DUPLICATE KEY UPDATE ");
        for (int i = 0; i < u.updateAssignments().size(); i++) {
            var assign = u.updateAssignments().get(i);
            out.writeIdentifier(assign.column().name()); out.write(" = VALUES("); out.writeIdentifier(assign.column().name()); out.write(")");
            if (i < u.updateAssignments().size() - 1) out.write(", ");
        }
    }

    private void generateAssignments(Upsert u, FastSqlBuffer out, String prefix, DialectTranspiler main) {
        for (int i = 0; i < u.updateAssignments().size(); i++) {
            var assign = u.updateAssignments().get(i);
            out.writeIdentifier(assign.column().name()); out.write(" = ");
            if (prefix != null && assign.expression() instanceof Column c) {
                out.write(prefix); out.writeIdentifier(c.name());
            } else { main.visit(assign.expression(), out); }
            if (i < u.updateAssignments().size() - 1) out.write(", ");
        }
    }
}
