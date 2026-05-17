package com.baresql.ir;

import com.baresql.ir.IrTypes.*;
import com.baresql.ast.Nodes.Op;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IrOptimizer {
    
    public static List<Instruction> optimize(List<Instruction> rawInstructions) {
        List<Instruction> optimized = new ArrayList<>();
        
        // Tabela Hash para mapear Valores já calculados e garantir o SSA estrito
        Map<IrOp, IrVar> computedExpressions = new HashMap<>();
        
        // Tabela para remapeamento de variáveis (quando eliminamos instruções)
        Map<IrVar, IrVar> varAliases = new HashMap<>();

        for (Instruction inst : rawInstructions) {
            IrOp op = inst.operation();

           // 1. Resolve Aliases
            if (op instanceof BinaryMath b) {
                IrVar realLeft = varAliases.getOrDefault(b.left(), b.left());
                IrVar realRight = varAliases.getOrDefault(b.right(), b.right());
                
                // NOVA REGRA: Idempotência Booleana (A AND A = A)
                if (realLeft.equals(realRight) && (b.op() == Op.AND || b.op() == Op.OR)) {
                    // O resultado desta instrução inteira é simplesmente a variável da esquerda
                    varAliases.put(inst.result(), realLeft);
                    continue; // Descarta o nó AND/OR completamente!
                }
                
                op = new BinaryMath(realLeft, b.op(), realRight);
            }

            // 2. CONSTANT FOLDING (Dobramento Matemático)
            // Se for uma operação entre duas constantes, resolvemos em tempo de compilação
            if (op instanceof BinaryMath b) {
                IrOp leftDef = getDefinition(optimized, b.left());
                IrOp rightDef = getDefinition(optimized, b.right());
                
                if (leftDef instanceof LoadLiteral ll && rightDef instanceof LoadLiteral lr) {
                    if (ll.value() instanceof Number numL && lr.value() instanceof Number numR) {
                        if (b.op() == Op.GT) {
                            // Substitui a operação matemática complexa por um booleano simples literal (1 ou 0 para SQL)
                            op = new LoadLiteral(numL.doubleValue() > numR.doubleValue());
                        }
                        // *Nota: Expandir para ADD, MUL, etc...*
                    }
                }
            }

            // 3. COMMON SUBEXPRESSION ELIMINATION (CSE)
            // Se essa operação matemática EXATA já foi feita, não gere código, reutilize a variável virtual!
            if (computedExpressions.containsKey(op)) {
                IrVar existingVar = computedExpressions.get(op);
                varAliases.put(inst.result(), existingVar); // Mapeia o resultado novo pro antigo
                continue; // Pula a adição dessa instrução (Eliminou o nó!)
            }

            // Registra a nova instrução válida e seu resultado
            computedExpressions.put(op, inst.result());
            optimized.add(new Instruction(inst.result(), op));
        }

        return optimized;
    }

    private static IrOp getDefinition(List<Instruction> instructions, IrVar var) {
        for (Instruction i : instructions) {
            if (i.result().equals(var)) return i.operation();
        }
        return null;
    }
}
