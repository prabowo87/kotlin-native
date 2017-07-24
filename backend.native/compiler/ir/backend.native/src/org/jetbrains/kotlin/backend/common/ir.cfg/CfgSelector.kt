package org.jetbrains.kotlin.backend.common.ir.cfg

import com.sun.jna.platform.win32.WinDef
import org.jetbrains.kotlin.backend.common.descriptors.isSuspend
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.ValueType
import org.jetbrains.kotlin.backend.konan.correspondingValueType
import org.jetbrains.kotlin.backend.konan.isValueType
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ValueDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.IrVariableSymbol
import org.jetbrains.kotlin.ir.util.getArguments
import org.jetbrains.kotlin.ir.util.type
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.types.KotlinType

//-----------------------------------------------------------------------------//

internal class CfgSelector(val context: Context): IrElementVisitorVoid {

    private val ir = Ir()
    private var currentBlock        = Block("Entry")
    private var currentFunction     = Function("Outer")
    private var currentLandingBlock = currentFunction.defaultLanding

    private val variableMap = mutableMapOf<ValueDescriptor, Operand>()
    private val typeMap     = mutableMapOf<ClassDescriptor, Class>()
    private val loopStack   = mutableListOf<LoopLabels>()

    private data class LoopLabels(val loop: IrLoop, val check: Block, val exit: Block)

    //-------------------------------------------------------------------------//

    fun select() {
        context.irModule!!.accept(this, null)
        context.log { ir.log(); "" }
    }

    //-------------------------------------------------------------------------//

    override fun visitFunction(declaration: IrFunction) {
        selectFunction(declaration)
        super.visitFunction(declaration)
    }

    //-------------------------------------------------------------------------//

    private fun selectFunction(irFunction: IrFunction) {
        currentFunction = Function(irFunction.descriptor.name.asString())
        ir.newFunction(currentFunction)

        irFunction.valueParameters
            .map {
                val variable = Variable(it.type.toCfgType(), it.descriptor.name.asString())
                variableMap[it.descriptor] = variable
                variable
            }.let(currentFunction::addValueParameters)

        irFunction.body?.let {
            currentBlock = currentFunction.enter
            currentLandingBlock = currentFunction.defaultLanding
            when (it) {
                is IrExpressionBody -> selectStatement(it.expression)
                is IrBlockBody -> it.statements.forEach {
                    selectStatement(it)
                }
                else -> throw TODO("unsupported function body type: $it")
            }
        }
    }

    //-------------------------------------------------------------------------//

    private fun selectStatement(statement: IrStatement): Operand =
        when (statement) {
            is IrTypeOperatorCall    -> selectTypeOperatorCall   (statement)
            is IrCall                -> selectCall               (statement)
            is IrContainerExpression -> selectContainerExpression(statement)
            is IrConst<*>            -> selectConst              (statement)
            is IrWhileLoop           -> selectWhileLoop          (statement)
            is IrBreak               -> selectBreak              (statement)
            is IrContinue            -> selectContinue           (statement)
            is IrReturn              -> selectReturn             (statement)
            is IrWhen                -> selectWhen               (statement)
            is IrSetVariable         -> selectSetVariable        (statement)
            is IrVariable            -> selectVariable           (statement)
            is IrVariableSymbol      -> selectVariableSymbol     (statement)
            is IrValueSymbol         -> selectValueSymbol        (statement)
            is IrGetValue            -> selectGetValue           (statement)
            is IrVararg              -> selectVararg             (statement)
            is IrThrow               -> selectThrow              (statement)
            is IrTry                 -> selectTry                (statement)
            else -> {
                println("Not implemented yet: $statement")
                CfgNull
            }
        }

    //-------------------------------------------------------------------------//

    private fun selectTypeOperatorCall(statement: IrTypeOperatorCall) =
        when (statement.operator) {
            IrTypeOperator.CAST                      -> selectCast           (statement)
            IrTypeOperator.IMPLICIT_INTEGER_COERCION -> selectIntegerCoercion(statement)
            IrTypeOperator.IMPLICIT_CAST             -> selectImplicitCast   (statement)
            IrTypeOperator.IMPLICIT_NOTNULL          -> selectImplicitNotNull(statement)
            IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> selectCoercionToUnit (statement)
            IrTypeOperator.SAFE_CAST                 -> selectSafeCast       (statement)
            IrTypeOperator.INSTANCEOF                -> selectInstanceOf     (statement)
            IrTypeOperator.NOT_INSTANCEOF            -> selectNotInstanceOf  (statement)
        }

    //-------------------------------------------------------------------------//

    private fun selectCast(statement: IrTypeOperatorCall): Operand {
        val value = selectStatement(statement.argument)                                     // Evaluate object to compare.
        val klass = typeMap[statement.typeOperand.constructor.declarationDescriptor]        // Class to compare.
//        val newType = RefType(klass!!)                                                    // TODO to be removed
        val newType = CfgPointer

        return newInstruction(Opcode.cast, newType, value)
    }

    //-------------------------------------------------------------------------//

    private fun selectIntegerCoercion(statement: IrTypeOperatorCall): Operand {
        println("Not implemented yet: selectIntegerCoercion")
        return Variable(CfgInt, "invalid")
    }

    //-------------------------------------------------------------------------//

    private fun selectImplicitCast(statement: IrTypeOperatorCall): Operand {
        println("Not implemented yet: selectImplicitCast")
        return Variable(CfgInt, "invalid")
    }

    //-------------------------------------------------------------------------//

    private fun selectImplicitNotNull(statement: IrTypeOperatorCall): Operand {
        println("Not implemented yet: selectImplicitNotNull")
        return Variable(CfgInt, "invalid")
    }

    //-------------------------------------------------------------------------//

    private fun selectCoercionToUnit(statement: IrTypeOperatorCall): Operand {
        println("Not implemented yet: selectCoercionToUnit")
        return Variable(CfgInt, "invalid")
    }

    //-------------------------------------------------------------------------//

    private fun selectSafeCast(statement: IrTypeOperatorCall): Operand {
        println("Not implemented yet: selectSafeCast")
        return Variable(CfgInt, "invalid")
    }

    //-------------------------------------------------------------------------//

    private fun selectInstanceOf(statement: IrTypeOperatorCall): Operand {
        val value = selectStatement(statement.argument)                                     // Evaluate object to compare.
        val klass = typeMap[statement.typeOperand.constructor.declarationDescriptor]        // Class to compare.
        val type  = Constant(CfgPointer, klass)                                           // Operand representing the Class.
        return newInstruction(Opcode.instance_of, CfgBoolean, value, type)
    }

    //-------------------------------------------------------------------------//

    private fun selectNotInstanceOf(statement: IrTypeOperatorCall): Operand {
        val value = selectStatement(statement.argument)                                     // Evaluate object to compare.
        val klass = typeMap[statement.typeOperand.constructor.declarationDescriptor]        // Class to compare.
        val type  = Constant(CfgPointer, klass)                                           // Operand representing the Class.
        return newInstruction(Opcode.not_instance_of, CfgBoolean, value, type)
    }

    //-------------------------------------------------------------------------//

    private fun selectOperator(irCall: IrCall): Operand {
        val def  = newVariable(irCall.type.toCfgType())
        val uses = irCall.getArguments().map { selectStatement(it.second) }
        val opcode = when(irCall.descriptor.name.toString()) {
            "plus"  -> Opcode.add
            "minus" -> Opcode.sub
            "times" -> Opcode.mul
            "div"   -> Opcode.sdiv
            "srem"  -> Opcode.srem
            else -> {
                println("ERROR: unsupported operator type \"${irCall.descriptor.name}\"")
                Opcode.invalid
            }
        }

        currentBlock.instruction(opcode, def, *uses.toTypedArray())
        return def
    }

    //-------------------------------------------------------------------------//

    /**
     * pass [catchBlock] if call is inside try-catch
     * function's default landing will be used otherwise
     */
    private fun selectCall(irCall: IrCall): Operand {
        if (irCall.descriptor.isOperator) return selectOperator(irCall)

        val callee = Variable(CfgPointer, irCall.descriptor.name.asString())
        val uses = listOf(callee) + irCall.getArguments().map { (_, expr) -> selectStatement(expr) }
        val def = newVariable(irCall.type.toCfgType())
        val successTarget = currentFunction.newBlock("success")
        currentBlock.invoke(successTarget, currentLandingBlock, def, *uses.toTypedArray())
        currentBlock = successTarget
        return def


//        if (irCall.type.isUnit()) {
//            currentBlock.instruction(Opcode.call, *uses.toTypedArray())
//            return CfgUnit
//        } else {
//            val def = Variable(KtType(irCall.type), currentFunction.genVariableName())
//            currentBlock.instruction(Opcode.call, def, *uses.toTypedArray())
//            return def
//        }
    }

    //-------------------------------------------------------------------------//

    private fun selectContainerExpression(expression: IrContainerExpression): Operand {
        expression.statements.dropLast(1).forEach {
            selectStatement(it)
        }
        return expression.statements.lastOrNull()
            ?.let { selectStatement(it) } ?: CfgUnit
    }

    //-------------------------------------------------------------------------//

    private fun selectConst(const: IrConst<*>): Constant = when(const.kind) {
        IrConstKind.Null    -> CfgNull
        IrConstKind.Boolean -> Constant(CfgBoolean, const.value as Boolean)
        IrConstKind.Char    -> Constant(CfgChar,    const.value as Char)
        IrConstKind.Byte    -> Constant(CfgByte,    const.value as Byte)
        IrConstKind.Short   -> Constant(CfgShort,   const.value as Short)
        IrConstKind.Int     -> Constant(CfgInt,     const.value as Int)
        IrConstKind.Long    -> Constant(CfgLong,    const.value as Long)
        IrConstKind.Float   -> Constant(CfgFloat,   const.value as Float)
        IrConstKind.String  -> Constant(CfgPointer, const.value as String)
        IrConstKind.Double  -> Constant(CfgDouble,  const.value as Double)
    }

    //-------------------------------------------------------------------------//

    private fun selectWhileLoop(irWhileLoop: IrWhileLoop): Operand {
        val loopCheck = currentFunction.newBlock("loop_check")
        val loopBody = currentFunction.newBlock("loop_body")
        val loopExit = currentFunction.newBlock("loop_exit")

        loopStack.push(LoopLabels(irWhileLoop, loopCheck, loopExit))

        currentBlock.addSuccessor(loopCheck)
        currentBlock = loopCheck
        currentBlock.condBr(selectStatement(irWhileLoop.condition), loopBody, loopExit)

        currentBlock = loopBody
        irWhileLoop.body?.let { selectStatement(it) }
        if (!currentBlock.isLastInstructionTerminal())
            currentBlock.br(loopCheck)

        loopStack.pop()
        currentBlock = loopExit
        return CfgUnit
    }

    //-------------------------------------------------------------------------//

    private fun selectBreak(expression: IrBreak): Operand {
        loopStack.reversed().first { (loop, _, _) -> loop == expression.loop }
            .let { (_, _, exit) -> currentBlock.br(exit) }
        return CfgUnit
    }

    //-------------------------------------------------------------------------//

    private fun selectContinue(expression: IrContinue): Operand {
        loopStack.reversed().first { (loop, _, _) -> loop == expression.loop }
            .let { (_, check, _) -> currentBlock.br(check) }
        return CfgUnit
    }

    //-------------------------------------------------------------------------//

    private fun selectReturn(irReturn: IrReturn): Operand {
        val target = irReturn.returnTarget
        val evaluated = selectStatement(irReturn.value)
        currentBlock.ret(evaluated)
        return if (target.returnsUnit()) {
            CfgUnit
        } else {
            evaluated
        }
    }

    //-------------------------------------------------------------------------//

    private fun selectWhen(expression: IrWhen): Operand {
        val resultVar = if (expression.type == context.builtIns.unitType) {
            null
        } else {
            newVariable(expression.type.toCfgType())
        }
        val exitBlock = currentFunction.newBlock()

        expression.branches.forEach {
            val nextBlock = if (it == expression.branches.last()) exitBlock else currentFunction.newBlock()
            selectWhenClause(it, nextBlock, exitBlock, resultVar)
        }

        currentBlock = exitBlock
        return resultVar ?: CfgUnit
    }

    //-------------------------------------------------------------------------//

    private fun selectWhenClause(irBranch: IrBranch, nextBlock: Block, exitBlock: Block, variable: Variable?) {
        currentBlock = if (isUnconditional(irBranch)) {
            currentBlock
        } else {
            currentFunction.newBlock().also {
                currentBlock.condBr(selectStatement(irBranch.condition), it, nextBlock)
            }
        }

        val clauseExpr = selectStatement(irBranch.result)
        with(currentBlock) {
            if (!isLastInstructionTerminal()) {
                variable?.let { mov(it, clauseExpr) }
                br(exitBlock)
            }
        }
        currentBlock = nextBlock
    }

    //-------------------------------------------------------------------------//

    private fun selectSetVariable(irSetVariable: IrSetVariable): Operand {
        val operand = selectStatement(irSetVariable.value)
        variableMap[irSetVariable.descriptor] = operand
        val variable = Variable(irSetVariable.value.type.toCfgType(), irSetVariable.descriptor.name.asString())
        currentBlock.mov(variable, operand)
        return CfgUnit
    }

    //-------------------------------------------------------------------------//

    private fun selectVariable(irVariable: IrVariable): Operand {
        val operand = irVariable.initializer?.let { selectStatement(it) } ?: CfgNull
        variableMap[irVariable.descriptor] = operand
        return operand
    }

    //-------------------------------------------------------------------------//

    private fun selectVariableSymbol(irVariableSymbol: IrVariableSymbol): Operand
        = variableMap[irVariableSymbol.descriptor] ?: CfgNull

    //-------------------------------------------------------------------------//

    private fun selectValueSymbol(irValueSymbol: IrValueSymbol): Operand
        = variableMap[irValueSymbol.descriptor] ?: CfgNull

    //-------------------------------------------------------------------------//

    private fun selectGetValue(getValue: IrGetValue): Operand
        = variableMap[getValue.descriptor] ?: CfgNull

    //-------------------------------------------------------------------------//

    private fun selectVararg(irVararg: IrVararg): Operand {
        val elements = irVararg.elements.map {
            if (it is IrExpression) {
                return@map selectStatement(it)
            }
            throw IllegalStateException("IrVararg neither was lowered nor can be statically evaluated")
        }
        // TODO: replace with a correct array type
//        return Constant(KtType(irVararg.type), elements)
        return Constant(CfgPointer, elements)
    }

    //-------------------------------------------------------------------------//
    // Returns first catch block
    private fun selectCatches(irCatches: List<IrCatch>, tryExit: Block): Block {
        val prevBlock = currentBlock

        val header = currentFunction.newBlock("catch_header")
        val exception = Variable(CfgPointer, "exception")
        val isInstanceFunc = Variable(CfgPointer, "IsInstance")

        // TODO: should expand to real exception object extraction
        header.instruction(Opcode.landingpad, exception)
        currentBlock = header
        irCatches.forEach {
            val catchBody = currentFunction.newBlock()
            val nextCatch = if (it == irCatches.last()) {
                currentFunction.defaultLanding
            } else {
                currentFunction.newBlock("check_for_${it.parameter.name.asString()}")
            }

            val isInstance = Variable(CfgBoolean, "is_instance")
            currentBlock.instruction(Opcode.call, isInstance, isInstanceFunc, exception)
            currentBlock.condBr(isInstance, catchBody, nextCatch)

            currentBlock = catchBody
            selectStatement(it.result)
            currentBlock.br(tryExit)
            currentBlock = nextCatch
        }
        currentBlock = prevBlock
        return header
    }

    //-------------------------------------------------------------------------//

    private fun selectThrow(irThrow: IrThrow): Operand {
        val evaluated = selectStatement(irThrow.value)
        // TODO: call ThrowException
//        currentBlock.invoke(currentFunction.newBlock(), )
        return CfgNull // TODO: replace with Nothing type
    }

    //-------------------------------------------------------------------------//

    // TODO: add return value
    private fun selectTry(irTry: IrTry): Operand {
        // TODO: what if catches is empty
        val tryExit = currentFunction.newBlock("try_exit")
        currentLandingBlock = selectCatches(irTry.catches, tryExit)
        val operand = selectStatement(irTry.tryResult)
        currentBlock.br(tryExit)
        currentLandingBlock = currentFunction.defaultLanding
        currentBlock = tryExit
        return operand
    }

    //-------------------------------------------------------------------------//

    private fun CallableDescriptor.returnsUnit()
            = returnType == context.builtIns.unitType && !isSuspend

    //-------------------------------------------------------------------------//

    private fun isUnconditional(irBranch: IrBranch): Boolean =
            irBranch.condition is IrConst<*>                            // If branch condition is constant.
                    && (irBranch.condition as IrConst<*>).value as Boolean  // If condition is "true"

    //-------------------------------------------------------------------------//

    private fun newVariable(type: Type) = Variable(type, currentFunction.genVariableName())

    //-------------------------------------------------------------------------//

    fun KotlinType.toCfgType(): Type {
        if (!isValueType()) {
            val klass = typeMap[constructor.declarationDescriptor]
            if (klass == null) return CfgPointer // TODO to be removed after class structure selection
            return RefType(klass)
        }

        return when (correspondingValueType) {
            ValueType.BOOLEAN        -> CfgBoolean
            ValueType.CHAR           -> CfgChar
            ValueType.BYTE           -> CfgByte
            ValueType.SHORT          -> CfgShort
            ValueType.INT            -> CfgInt
            ValueType.LONG           -> CfgLong
            ValueType.FLOAT          -> CfgFloat
            ValueType.DOUBLE         -> CfgDouble
            ValueType.NATIVE_PTR     -> CfgPointer
            ValueType.NATIVE_POINTED -> CfgPointer
            ValueType.C_POINTER      -> CfgPointer
            null                     -> throw TODO("Null ValueType")
        }
    }

    //-------------------------------------------------------------------------//

    private fun newInstruction(opcode: Opcode, type: Type, use: Operand): Operand {
        val def = newVariable(type)
        currentBlock.instruction(opcode, def, use)
        return def
    }

    //-------------------------------------------------------------------------//

    private fun newInstruction(opcode: Opcode, type: Type, use1: Operand, use2: Operand): Operand {
        val def = newVariable(type)
        currentBlock.instruction(opcode, def, use1, use2)
        return def
    }

    //-------------------------------------------------------------------------//

    override fun visitElement(element: IrElement)
            = element.acceptChildren(this, null)
}
