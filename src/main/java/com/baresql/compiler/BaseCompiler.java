package com.baresql.compiler;

import com.baresql.ast.Nodes.*;

public class BaseCompiler {
    public void generateInsert(Insert insert, FastSqlBuffer out, DialectTranspiler main) {
        out.write("INSERT INTO "); out.writeIdentifier(insert.table().name()); out.write(" (");
        for (int i = 0; i < insert.columns().size(); i++) {
            out.writeIdentifier(insert.columns().get(i).name());
            if (i < insert.columns().size() - 1) out.write(", ");
        }
        out.write(") VALUES (");
        for (int i = 0; i < insert.values().size(); i++) {
            main.visit(insert.values().get(i), out);
            if (i < insert.values().size() - 1) out.write(", ");
        }
        out.write(")");
        if (insert.returning().isPresent()) {
            out.write(" RETURNING ");
            var cols = insert.returning().get();
            for (int i = 0; i < cols.size(); i++) {
                out.writeIdentifier(cols.get(i).name());
                if (i < cols.size() - 1) out.write(", ");
            }
        }
    }
}
