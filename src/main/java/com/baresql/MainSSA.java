package com.baresql;

import com.baresql.ast.Nodes.*;
import com.baresql.builder.Sql.Col;
import com.baresql.compiler.Dialect;
import com.baresql.compiler.DialectTranspiler;
import com.baresql.compiler.FastSqlBuffer;
import com.baresql.ir.*;

public class MainSSA {
    public static void main(String[] args) {
        System.out.println("=== Bare-SQL Engine: Optimizador SSA ===");

        // 1. Criamos uma expressão não otimizada:
        // (idade > 18) OR (idade > 18)  -> Típico código gerado por frameworks cegos
        Expr rawCondition = Col.of("idade").gt(18).and(Col.of("idade").gt(18)).build();
        
        System.out.println("1. Expressão Original (AST Bruta):");
        FastSqlBuffer rawBuffer = new FastSqlBuffer();
        new DialectTranspiler(Dialect.POSTGRES).visit(rawCondition, rawBuffer);
        System.out.println("   SQL Gerado: " + rawBuffer.getSql());

        // 2. Front-End: Converter AST para IR (SSA)
        AstToIrPass irPass = new AstToIrPass();
        IrTypes.IrVar rootVar = irPass.visit(rawCondition);
        
        System.out.println("\n2. Representação Intermediária (SSA) Bruta:");
        irPass.getInstructions().forEach(i -> System.out.println("   " + i));

        // 3. Middle-End: Otimizar a IR matematicamente (CSE atua aqui)
        var optResult = IrOptimizer.optimizeWithAliases(irPass.getInstructions());

        System.out.println("\n3. Representação Intermediária (SSA) Otimizada [CSE e Folding]:");
        optResult.instructions().forEach(i -> System.out.println("   " + i));

        // 4. Back-End: Reconstruir a AST e Transpilar
        Expr optimizedAst = IrToAstPass.reconstruct(optResult.instructions(), rootVar, optResult.aliases());
        FastSqlBuffer optBuffer = new FastSqlBuffer();
        new DialectTranspiler(Dialect.POSTGRES).visit(optimizedAst, optBuffer);
        
        System.out.println("\n4. Resultado Final (Transpilado):");
        System.out.println("   SQL Gerado: " + optBuffer.getSql());
        System.out.println("\n*Note como o otimizador reaproveitou os nós e eliminou o cálculo redundante antes de tocar no banco de dados.*");
    }
}
