package com.baresql.ir;

import com.baresql.ast.Nodes.*;
import com.baresql.ir.IrTypes.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IrToAstPass {
    
    public static Expr reconstruct(List<Instruction> optimizedInstructions, IrVar rootVar) {
        Map<IrVar, Expr> astNodes = new HashMap<>();

        for (Instruction inst : optimizedInstructions) {
            Expr expr = null;
            IrOp op = inst.operation();
            
            if (op instanceof IrTypes.LoadColumn c) {
                expr = new Column(c.name());
            } else if (op instanceof IrTypes.LoadLiteral l) {
                expr = new Literal(l.value());
            } else if (op instanceof IrTypes.BinaryMath b) {
                Expr left = astNodes.get(b.left());
                Expr right = astNodes.get(b.right());
                if (left != null && right != null) {
                    expr = new BinaryExpr(left, b.op(), right);
                }
            }
            
            if (expr != null) {
                astNodes.put(inst.result(), expr);
            }
        }

        // Se o rootVar não está no mapa, procura pelo alias (último nó que foi gerado)
        Expr result = astNodes.get(rootVar);
        if (result == null && !optimizedInstructions.isEmpty()) {
            // Retorna a última instrução construída como fallback
            result = astNodes.get(optimizedInstructions.get(optimizedInstructions.size() - 1).result());
        }
        
        return result;
    }
}
