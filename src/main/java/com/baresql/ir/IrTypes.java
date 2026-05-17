package com.baresql.ir;

import com.baresql.ast.Nodes.Op;

public interface IrTypes {
    // Variável Virtual do SSA (ex: %1, %2)
    record IrVar(int id) {
        @Override public String toString() { return "%" + id; }
    }

    // Operações base planas (Achatadas da AST)
    sealed interface IrOp { }
    record LoadColumn(String name) implements IrOp { }
    record LoadLiteral(Object value) implements IrOp { }
    record BinaryMath(IrVar left, Op op, IrVar right) implements IrOp { }

    // Uma instrução SSA: %var = Operação
    record Instruction(IrVar result, IrOp operation) {
        @Override public String toString() { return result + " = " + operation; }
    }
}
