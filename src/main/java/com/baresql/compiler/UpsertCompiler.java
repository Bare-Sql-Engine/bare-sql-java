package com.baresql.compiler;

import com.baresql.ast.Nodes.*;

public class UpsertCompiler {
    public void compile(Upsert upsert, FastSqlBuffer out, Dialect dialect, DialectTranspiler main) {
        if (dialect == Dialect.SQL_SERVER) {
            compileSqlServerStyle(upsert, out, main);
        } else {
            new BaseCompiler().generateInsert(upsert.insert(), out, main);
            switch (dialect) {
                case POSTGRES, SQLITE -> compilePostgresStyle(upsert, out, main);
                case MYSQL -> compileMysqlStyle(upsert, out, main);
                default -> throw new UnsupportedOperationException("Dialecto não suportado: " + dialect);
            }
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

    private void compileSqlServerStyle(Upsert u, FastSqlBuffer out, DialectTranspiler main) {
        // SQL Server MERGE INTO requires a different approach: we need to undo the INSERT
        // that was already written and rewrite as MERGE INTO ... USING ... WHEN MATCHED ...
        // For simplicity, we rebuild from scratch using the insert data.
        Insert ins = u.insert();
        out.write("MERGE INTO "); out.writeIdentifier(ins.table().name()); out.write(" AS target USING (VALUES (");
        for (int i = 0; i < ins.values().size(); i++) {
            main.visit(ins.values().get(i), out);
            if (i < ins.values().size() - 1) out.write(", ");
        }
        out.write(")) AS source (");
        for (int i = 0; i < ins.columns().size(); i++) {
            out.writeIdentifier(ins.columns().get(i).name());
            if (i < ins.columns().size() - 1) out.write(", ");
        }
        out.write(") ON ");
        for (int i = 0; i < u.conflictColumns().size(); i++) {
            out.write("target."); out.writeIdentifier(u.conflictColumns().get(i).name());
            out.write(" = source."); out.writeIdentifier(u.conflictColumns().get(i).name());
            if (i < u.conflictColumns().size() - 1) out.write(" AND ");
        }
        out.write(" WHEN MATCHED THEN UPDATE SET ");
        for (int i = 0; i < u.updateAssignments().size(); i++) {
            var assign = u.updateAssignments().get(i);
            out.write("target."); out.writeIdentifier(assign.column().name());
            out.write(" = source."); out.writeIdentifier(assign.column().name());
            if (i < u.updateAssignments().size() - 1) out.write(", ");
        }
        out.write(" WHEN NOT MATCHED THEN INSERT (");
        for (int i = 0; i < ins.columns().size(); i++) {
            out.writeIdentifier(ins.columns().get(i).name());
            if (i < ins.columns().size() - 1) out.write(", ");
        }
        out.write(") VALUES (");
        for (int i = 0; i < ins.columns().size(); i++) {
            out.write("source."); out.writeIdentifier(ins.columns().get(i).name());
            if (i < ins.columns().size() - 1) out.write(", ");
        }
        out.write(");");
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
