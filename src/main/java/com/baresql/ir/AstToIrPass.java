package com.baresql.ir;

import com.baresql.ast.Nodes.*;
import com.baresql.ir.IrTypes.*;
import java.util.ArrayList;
import java.util.List;

public class AstToIrPass {
    private int varCounter = 0;
    private final List<Instruction> instructions = new ArrayList<>();

    private IrVar nextVar() { return new IrVar(++varCounter); }

    public IrVar visit(Expr expr) {
        IrVar result = nextVar();
        IrOp op = switch (expr) {
            case Column c -> new LoadColumn(c.name());
            case Literal l -> new LoadLiteral(l.value());
            case BinaryExpr b -> {
                IrVar leftVar = visit(b.left());
                IrVar rightVar = visit(b.right());
                yield new BinaryMath(leftVar, b.op(), rightVar);
            }
            default -> throw new IllegalStateException("AST Node não suportado na IR: " + expr);
        };
        instructions.add(new Instruction(result, op));
        return result;
    }

    public List<Instruction> getInstructions() { return instructions; }
}
