package com.baresql.ir;

import com.baresql.ast.Nodes.*;
import com.baresql.ir.IrTypes.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IrToAstPass {

    public static Expr reconstruct(List<Instruction> optimizedInstructions, IrVar rootVar) {
        return reconstruct(optimizedInstructions, rootVar, Map.of());
    }

    public static Expr reconstruct(List<Instruction> optimizedInstructions, IrVar rootVar, Map<IrVar, IrVar> aliases) {
        Map<IrVar, Expr> astNodes = new HashMap<>();

        // Resolve o rootVar se for um alias
        IrVar resolvedRoot = rootVar;
        while (aliases.containsKey(resolvedRoot)) {
            resolvedRoot = aliases.get(resolvedRoot);
        }

        for (Instruction inst : optimizedInstructions) {
            Expr expr = null;
            IrOp op = inst.operation();

            if (op instanceof IrTypes.LoadColumn c) {
                expr = new Column(c.name());
            } else if (op instanceof IrTypes.LoadLiteral l) {
                expr = new Literal(l.value());
            } else if (op instanceof IrTypes.BinaryMath b) {
                IrVar leftVar = resolveAlias(b.left(), aliases);
                IrVar rightVar = resolveAlias(b.right(), aliases);
                Expr left = astNodes.get(leftVar);
                Expr right = astNodes.get(rightVar);
                if (left != null && right != null) {
                    expr = new BinaryExpr(left, b.op(), right);
                }
            }

            if (expr != null) {
                astNodes.put(inst.result(), expr);
            }
        }

        Expr result = astNodes.get(resolvedRoot);
        if (result == null && !optimizedInstructions.isEmpty()) {
            result = astNodes.get(optimizedInstructions.get(optimizedInstructions.size() - 1).result());
        }

        return result;
    }

    private static IrVar resolveAlias(IrVar var, Map<IrVar, IrVar> aliases) {
        IrVar resolved = var;
        while (aliases.containsKey(resolved)) {
            resolved = aliases.get(resolved);
        }
        return resolved;
    }
}
