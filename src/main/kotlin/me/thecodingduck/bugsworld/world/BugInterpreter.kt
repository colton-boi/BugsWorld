package me.thecodingduck.bugsworld.world

import me.thecodingduck.bugsworld.Action
import me.thecodingduck.bugsworld.BugLogic
import me.thecodingduck.bugsworld.Condition
import me.thecodingduck.bugsworld.bytecode.Compiler
import me.thecodingduck.bugsworld.bytecode.Opcode
import java.util.concurrent.ThreadLocalRandom

/**
 * Stack-based AST interpreter. Each call to [executeTurn] runs until one action
 * is performed, then returns. The execution stack persists across turns so the
 * program counter is maintained.
 */
class BugInterpreter(val bug: Bug, var logic: BugLogic, private val world: World) {

    // Cache the ordinals directly as static integers
    companion object {
        private val COND_TRUE = Condition.TRUE.ordinal
        private val COND_RANDOM = Condition.RANDOM.ordinal
        private val COND_NEXT_IS_EMPTY = Condition.NEXT_IS_EMPTY.ordinal
        private val COND_NEXT_IS_WALL = Condition.NEXT_IS_WALL.ordinal
        private val COND_NEXT_IS_ENEMY = Condition.NEXT_IS_ENEMY.ordinal
        private val COND_NEXT_IS_FRIEND = Condition.NEXT_IS_FRIEND.ordinal
        private val COND_NEXT_IS_NOT_EMPTY = Condition.NEXT_IS_NOT_EMPTY.ordinal
        private val COND_NEXT_IS_NOT_WALL = Condition.NEXT_IS_NOT_WALL.ordinal
        private val COND_NEXT_IS_NOT_ENEMY = Condition.NEXT_IS_NOT_ENEMY.ordinal
        private val COND_NEXT_IS_NOT_FRIEND = Condition.NEXT_IS_NOT_FRIEND.ordinal
    }

    private var i = 0 // counter

    private var species: Cell = Cell.EMPTY
    private var enemy: Cell = Cell.EMPTY

    private var bytecode: IntArray = Compiler.compile(logic.statement)

    init { restartProgram() }

    fun restartProgram() {
        i = 0
        updateFactions()
    }

    private fun updateFactions() {
        if (bug.speciesId == 1) {
            species = Cell.SPECIES1
            enemy = Cell.SPECIES2
        } else {
            species = Cell.SPECIES2
            enemy = Cell.SPECIES1
        }
    }

    /**
     * Executes the bug's logic until an action is performed.
     */
    fun executeTurn() {
        val code = bytecode
        val size = code.size
        if (size == 0) return // No logic, do nothing

        var iterations = 0
        while (iterations++ < 100) {
            if (i >= size) i = 0 // wrap around to the beginning of the program

            when (val opcode = code[i++]) {
                Opcode.MOVE -> { performMove(); return }
                Opcode.TURN_LEFT -> { performTurnLeft(); return }
                Opcode.TURN_RIGHT -> { performTurnRight(); return }
                Opcode.INFECT -> { performInfect(); return }

                Opcode.JUMP -> i = code[i] // jump to the specified instruction index
                Opcode.JUMP_IF_FALSE -> {
                    val conditionId = code[i++]
                    val jumpTarget = code[i++]
                    if (!evaluateCondition(conditionId)) {
                        i = jumpTarget
                    }
                }

                else -> throw IllegalStateException("Invalid opcode: $opcode")
            }
        }
    }

    private fun performMove() {
        val nx = bug.xPos + bug.direction.dx
        val ny = bug.yPos + bug.direction.dy
        if (world.grid[ny * world.sideLength + nx] == Cell.EMPTY.ordinal.toByte()) {
            world.grid[bug.yPos * world.sideLength + bug.xPos] = Cell.EMPTY.ordinal.toByte()
            world.bugGrid[bug.xPos][bug.yPos] = null

            bug.xPos = nx
            bug.yPos = ny

            world.grid[bug.yPos * world.sideLength + bug.xPos] = species.ordinal.toByte()
            world.bugGrid[nx][ny] = bug
        }
    }

    private fun performTurnLeft() {
        bug.direction = bug.direction.left
    }

    private fun performTurnRight() {
        bug.direction = bug.direction.right
    }

    private fun performInfect() {
        val nx = bug.xPos + bug.direction.dx
        val ny = bug.yPos + bug.direction.dy
        if (world.grid[ny * world.sideLength + nx] == enemy.ordinal.toByte()) {
            world.grid[ny * world.sideLength + nx] = species.ordinal.toByte() // change the cell to the bug's species
            val targetBug = world.bugGrid[nx][ny] ?: return

            if (targetBug.speciesId == bug.speciesId) throw IllegalStateException("Infected a bug of the same species??")
            if (targetBug.speciesId == 1) {
                world.species1Count--
                world.species2Count++ // Add this!
            } else {
                world.species2Count--
                world.species1Count++ // Add this!
            }

            targetBug.speciesId = bug.speciesId
            val interpreter = world.interpreters[targetBug.id]
            if (interpreter != null) {
                interpreter.logic = this.logic
                interpreter.bytecode = this.bytecode
                interpreter.restartProgram()
            }
        }
    }

    private fun evaluateCondition(condition: Int): Boolean {

        if (condition == COND_TRUE) return true
        if (condition == COND_RANDOM) return ThreadLocalRandom.current().nextBoolean()

        val nx = bug.xPos + bug.direction.dx
        val ny = bug.yPos + bug.direction.dy
        val cell = world.grid[ny * world.sideLength + nx]
        return when (condition) {
            COND_NEXT_IS_EMPTY -> cell == Cell.EMPTY.ordinal.toByte()
            COND_NEXT_IS_WALL -> cell == Cell.WALL.ordinal.toByte()
            COND_NEXT_IS_ENEMY -> cell == enemy.ordinal.toByte()
            COND_NEXT_IS_FRIEND -> cell == species.ordinal.toByte()
            COND_NEXT_IS_NOT_EMPTY -> cell != Cell.EMPTY.ordinal.toByte()
            COND_NEXT_IS_NOT_WALL -> cell != Cell.WALL.ordinal.toByte()
            COND_NEXT_IS_NOT_ENEMY -> cell != enemy.ordinal.toByte()
            COND_NEXT_IS_NOT_FRIEND -> cell != species.ordinal.toByte()
            else -> throw IllegalStateException("Invalid condition id: $condition")
        }
    }
}