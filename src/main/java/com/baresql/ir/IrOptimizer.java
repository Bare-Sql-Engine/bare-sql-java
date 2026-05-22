package com.baresql.ir;

import com.baresql.ir.IrTypes.*;
import com.baresql.ast.Nodes.Op;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IrOptimizer {

    public record OptResult(List<Instruction> instructions, Map<IrVar, IrVar> aliases) {}

    public static OptResult optimizeWithAliases(List<Instruction> rawInstructions) {
        List<Instruction> optimized = new ArrayList<>();
        Map<IrOp, IrVar> computedExpressions = new HashMap<>();
        Map<IrVar, IrVar> varAliases = new HashMap<>();

        for (Instruction inst : rawInstructions) {
            IrOp op = inst.operation();

            // 1. Resolve Aliases
            if (op instanceof BinaryMath b) {
                IrVar realLeft = varAliases.getOrDefault(b.left(), b.left());
                IrVar realRight = varAliases.getOrDefault(b.right(), b.right());

                // Idempotência Booleana (A AND A = A)
                if (realLeft.equals(realRight) && (b.op() == Op.AND || b.op() == Op.OR)) {
                    varAliases.put(inst.result(), realLeft);
                    continue;
                }

                op = new BinaryMath(realLeft, b.op(), realRight);
            }

            // 2. CONSTANT FOLDING
            if (op instanceof BinaryMath b) {
                IrOp leftDef = getDefinition(optimized, b.left());
                IrOp rightDef = getDefinition(optimized, b.right());

                if (leftDef instanceof LoadLiteral ll && rightDef instanceof LoadLiteral lr) {
                    if (ll.value() instanceof Number numL && lr.value() instanceof Number numR) {
                        // Preserve integer types when both operands are integers
                        boolean bothInt = (numL instanceof Integer || numL instanceof Long)
                                       && (numR instanceof Integer || numR instanceof Long);
                        if (bothInt) {
                            long l = numL.longValue(), r = numR.longValue();
                            op = switch (b.op()) {
                                case GT -> new LoadLiteral(l > r);
                                case LT -> new LoadLiteral(l < r);
                                case GTE -> new LoadLiteral(l >= r);
                                case LTE -> new LoadLiteral(l <= r);
                                case EQ -> new LoadLiteral(l == r);
                                case NEQ -> new LoadLiteral(l != r);
                                case ADD -> new LoadLiteral(l + r);
                                case SUB -> new LoadLiteral(l - r);
                                case MUL -> new LoadLiteral(l * r);
                                case DIV -> r != 0 ? new LoadLiteral(l / r) : op;
                                default -> op;
                            };
                        } else {
                            double l = numL.doubleValue(), r = numR.doubleValue();
                            op = switch (b.op()) {
                                case GT -> new LoadLiteral(l > r);
                                case LT -> new LoadLiteral(l < r);
                                case GTE -> new LoadLiteral(l >= r);
                                case LTE -> new LoadLiteral(l <= r);
                                case EQ -> new LoadLiteral(l == r);
                                case NEQ -> new LoadLiteral(l != r);
                                case ADD -> new LoadLiteral(l + r);
                                case SUB -> new LoadLiteral(l - r);
                                case MUL -> new LoadLiteral(l * r);
                                case DIV -> r != 0 ? new LoadLiteral(l / r) : op;
                                default -> op;
                            };
                        }
                    } else if (ll.value() instanceof Boolean boolL && lr.value() instanceof Boolean boolR) {
                        op = switch (b.op()) {
                            case AND -> new LoadLiteral(boolL && boolR);
                            case OR -> new LoadLiteral(boolL || boolR);
                            case EQ -> new LoadLiteral(boolL == boolR);
                            case NEQ -> new LoadLiteral(boolL != boolR);
                            default -> op;
                        };
                    }
                }
            }

            // 3. CSE
            if (computedExpressions.containsKey(op)) {
                IrVar existingVar = computedExpressions.get(op);
                varAliases.put(inst.result(), existingVar);
                continue;
            }

            computedExpressions.put(op, inst.result());
            optimized.add(new Instruction(inst.result(), op));
        }

        return new OptResult(optimized, varAliases);
    }

    // Mantém compatibilidade com código existente
    public static List<Instruction> optimize(List<Instruction> rawInstructions) {
        return optimizeWithAliases(rawInstructions).instructions();
    }

    private static IrOp getDefinition(List<Instruction> instructions, IrVar var) {
        for (Instruction i : instructions) {
            if (i.result().equals(var)) return i.operation();
        }
        return null;
    }
}
