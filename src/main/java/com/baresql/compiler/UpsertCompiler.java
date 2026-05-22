package com.baresql.compiler;

import com.baresql.ast.Nodes.*;

public class UpsertCompiler {
    public void compile(Upsert upsert, FastSqlBuffer out, Dialect dialect, DialectTranspiler main) {
        if (dialect == Dialect.SQL_SERVER) {
            // SQL Server: MERGE INTO — do NOT generate INSERT first
            compileSqlServerStyle(upsert, out, main);
        } else {
            // Postgres/SQLite/MySQL: INSERT first, then ON CONFLICT/ON DUPLICATE KEY
            new BaseCompiler().generateInsert(upsert.insert(), out, main);
            switch (dialect) {
                case POSTGRES, SQLITE -> compilePostgresStyle(upsert, out, main);
                case MYSQL -> compileMysqlStyle(upsert, out, main);
                default -> throw new UnsupportedOperationException("Dialecto nao suportado: " + dialect);
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
        // MERGE INTO target AS target
        // USING (VALUES (...)) AS source (col1, col2)
        // ON target.conflict_col = source.conflict_col
        // WHEN MATCHED THEN UPDATE SET ...
        // WHEN NOT MATCHED THEN INSERT (...);
        out.write("MERGE INTO "); out.writeIdentifier(u.insert().table().name()); out.write(" AS target");

        // USING (VALUES (...)) AS source (columns)
        out.write(" USING (VALUES (");
        for (int i = 0; i < u.insert().values().size(); i++) {
            main.visit(u.insert().values().get(i), out);
            if (i < u.insert().values().size() - 1) out.write(", ");
        }
        out.write(")) AS source (");
        for (int i = 0; i < u.insert().columns().size(); i++) {
            out.writeIdentifier(u.insert().columns().get(i).name());
            if (i < u.insert().columns().size() - 1) out.write(", ");
        }
        out.write(")");

        // ON target.conflict_col = source.conflict_col
        out.write(" ON ");
        for (int i = 0; i < u.conflictColumns().size(); i++) {
            if (i > 0) out.write(" AND ");
            out.write("target."); out.writeIdentifier(u.conflictColumns().get(i).name());
            out.write(" = source."); out.writeIdentifier(u.conflictColumns().get(i).name());
        }

        // WHEN MATCHED THEN UPDATE SET ...
        out.write(" WHEN MATCHED THEN UPDATE SET ");
        generateAssignments(u, out, "source.", main);

        // WHEN NOT MATCHED THEN INSERT (...)
        out.write(" WHEN NOT MATCHED THEN INSERT (");
        for (int i = 0; i < u.insert().columns().size(); i++) {
            out.writeIdentifier(u.insert().columns().get(i).name());
            if (i < u.insert().columns().size() - 1) out.write(", ");
        }
        out.write(") VALUES (");
        for (int i = 0; i < u.insert().columns().size(); i++) {
            out.write("source."); out.writeIdentifier(u.insert().columns().get(i).name());
            if (i < u.insert().columns().size() - 1) out.write(", ");
        }
        out.write(")");
    }

    private void generateAssignments(Upsert u, FastSqlBuffer out, String excludedPrefix, DialectTranspiler main) {
        for (int i = 0; i < u.updateAssignments().size(); i++) {
            var assign = u.updateAssignments().get(i);
            out.writeIdentifier(assign.column().name()); out.write(" = ");
            out.write(excludedPrefix); out.writeIdentifier(assign.column().name());
            if (i < u.updateAssignments().size() - 1) out.write(", ");
        }
    }
}
