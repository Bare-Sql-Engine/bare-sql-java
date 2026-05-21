package com.baresql.aot;

import com.baresql.ast.Nodes.Expr;
import com.baresql.ast.Nodes.Select;
import com.baresql.ast.Nodes.Statement;
import com.baresql.compiler.Dialect;
import com.baresql.compiler.DialectTranspiler;
import com.baresql.compiler.FastSqlBuffer;
import com.baresql.ir.AstToIrPass;
import com.baresql.ir.IrOptimizer;
import com.baresql.ir.IrToAstPass;
import com.baresql.ir.IrTypes.IrVar;
import com.baresql.registry.QueryRegistry;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Map;

public class AotGenerator {
    
    public static void main(String[] args) throws Exception {
        System.out.println("⚙️  [AOT Compiler] A iniciar transpilação estática...");
        
        String outputDir = args.length > 0 ? args[0] : "src/main/java/com/baresql/aot";
        File dir = new File(outputDir);
        if (!dir.exists()) dir.mkdirs();

        File targetFile = new File(dir, "PrecompiledQueries.java");
        try (PrintWriter writer = new PrintWriter(new FileWriter(targetFile))) {
            
            writer.println("package com.baresql.aot;");
            writer.println("");
            writer.println("/**");
            writer.println(" * CLASSE GERADA AUTOMATICAMENTE (AHEAD-OF-TIME).");
            writer.println(" * Não edite manualmente. Otimização SSA já aplicada.");
            writer.println(" */");
            writer.println("public final class PrecompiledQueries {");
            writer.println("    private PrecompiledQueries() {}");
            
            for (Map.Entry<String, Statement> entry : QueryRegistry.getQueries().entrySet()) {
                String queryName = entry.getKey();
                Statement rawAst = entry.getValue();
                
                // Aplica o otimizador SSA (IR) antes de transpilar
                Statement optimizedAst = optimizeAst(rawAst);

                // Transpila para os dialetos suportados
                String sqlPostgres = transpile(optimizedAst, Dialect.POSTGRES);
                String sqlSqlite = transpile(optimizedAst, Dialect.SQLITE);
                
                writer.println();
                writer.println("    // Query: " + queryName);
                writer.println("    public static final String " + queryName + "_POSTGRES = \"" + escape(sqlPostgres) + "\";");
                writer.println("    public static final String " + queryName + "_SQLITE = \"" + escape(sqlSqlite) + "\";");
            }
            
            writer.println("}");
        }
        
        System.out.println("✅ [AOT Compiler] Ficheiro PrecompiledQueries.java gerado com sucesso em: " + targetFile.getAbsolutePath());
    }

    private static Statement optimizeAst(Statement stmt) {
        if (stmt instanceof Select s && s.whereCondition() != null) {
            AstToIrPass irPass = new AstToIrPass();
            IrVar rootVar = irPass.visit(s.whereCondition());
            var optResult = IrOptimizer.optimizeWithAliases(irPass.getInstructions());
            Expr optimizedWhere = IrToAstPass.reconstruct(optResult.instructions(), rootVar, optResult.aliases());
            return new Select(s.columns(), s.table(), s.joins(), optimizedWhere, s.orderBy(), s.groupBy(), s.limit(), s.offset());
        }
        return stmt;
    }

    private static String transpile(Statement ast, Dialect dialect) {
        FastSqlBuffer buffer = new FastSqlBuffer();
        new DialectTranspiler(dialect).generate(ast, buffer);
        return buffer.getSql();
    }
    
    private static String escape(String sql) {
        return sql.replace("\"", "\\\"");
    }
}
