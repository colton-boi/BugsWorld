package me.thecodingduck.bugsworld.bytecode

import me.thecodingduck.bugsworld.Action
import me.thecodingduck.bugsworld.Block
import me.thecodingduck.bugsworld.IfStatement
import me.thecodingduck.bugsworld.Statement
import me.thecodingduck.bugsworld.WhileLoop

object Opcode {
    const val MOVE = 0
    const val TURN_LEFT = 1
    const val TURN_RIGHT = 2
    const val INFECT = 3
    const val JUMP = 4
    const val JUMP_IF_FALSE = 5
}

object Compiler {
    fun compile(statement: Statement): IntArray {
        val bytecode = mutableListOf<Int>()

        fun emit(vararg codes: Int) {
            bytecode.addAll(codes.toList())
        }

        fun emitPlaceholder(): Int {
            bytecode.add(-1) // placeholder
            return bytecode.size - 1
        }

        fun patch(pos: Int, value: Int) {
            bytecode[pos] = value
        }

        fun compileNode(node: Statement) {
            when (node) {
                is Action -> {
                    when (node) {
                        Action.MOVE -> emit(Opcode.MOVE)
                        Action.TURN_LEFT -> emit(Opcode.TURN_LEFT)
                        Action.TURN_RIGHT -> emit(Opcode.TURN_RIGHT)
                        Action.INFECT -> emit(Opcode.INFECT)
                    }
                }
                is Block -> {
                    node.statements.forEach { compileNode(it) }
                }
                is WhileLoop -> {
                    val loopStart = bytecode.size
                    emit(Opcode.JUMP_IF_FALSE, node.condition.ordinal)
                    val jumpEnd = emitPlaceholder()

                    compileNode(node.body)

                    emit(Opcode.JUMP, loopStart)

                    patch(jumpEnd, bytecode.size) // patch the jump to end of loop
                }
                is IfStatement -> {
                    emit(Opcode.JUMP_IF_FALSE, node.condition.ordinal)
                    val elseJump = emitPlaceholder()

                    compileNode(node.thenBlock)

                    if (node.elseBlock != null) {
                        emit(Opcode.JUMP)
                        val endJump = emitPlaceholder()

                        patch(elseJump, bytecode.size) // patch to else block

                        compileNode(node.elseBlock)

                        patch(endJump, bytecode.size) // patch to end of if
                    } else {
                        patch(elseJump, bytecode.size) // patch to end of if
                    }
                }
            }
        }

        compileNode(statement)
        return bytecode.toIntArray()
    }
}