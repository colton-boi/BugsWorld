package me.thecodingduck.bugsworld.world

import me.thecodingduck.bugsworld.Condition
import me.thecodingduck.bugsworld.bytecode.Opcode
import kotlin.random.Random
import sun.misc.Unsafe

// OPTIMIZATION FOR ACCESSING code[] WITH UNCHECKED ARRAY READS

val unsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe")
    .apply { isAccessible = true }.get(null) as Unsafe

val INT_ARRAY_BASE = unsafe.arrayBaseOffset(IntArray::class.java).toLong()
val INT_ARRAY_SCALE = unsafe.arrayIndexScale(IntArray::class.java).toLong()

@Suppress("NOTHING_TO_INLINE")
inline fun IntArray.getUnchecked(index: Int): Int {
    return unsafe.getInt(this, INT_ARRAY_BASE + index * INT_ARRAY_SCALE)
}

/**
 * Stack-based AST interpreter. Each call to [executeTurn] runs until one action
 * is performed, then returns. The execution stack persists across turns so the
 * program counter is maintained.
 */
class BugInterpreter(val bug: Bug,
                     private var bytecode: IntArray,
                     private val world: World) {

    private var i = 0 // counter

    private var species: Int = Cell.EMPTY.ordinal
    private var enemy: Int = Cell.EMPTY.ordinal
    private var posIndex = bug.posIndex
    private var dirIndex = DirectionOffsets.OFFSETS[bug.dir]
    private var nextCell = world.grid.getUnchecked(posIndex + dirIndex)

    init { restartProgram() }

    fun restartProgram() {
        i = 0
        updateFactions()
        updatePositionAndDirection()
    }

    private fun updateFactions() {
        if (world.grid.getUnchecked(posIndex) == Cell.SPECIES1.ordinal) {
            species = Cell.SPECIES1.ordinal
            enemy = Cell.SPECIES2.ordinal
        } else {
            species = Cell.SPECIES2.ordinal
            enemy = Cell.SPECIES1.ordinal
        }
    }

    private fun updatePositionAndDirection() {
        posIndex = bug.posIndex
        dirIndex = DirectionOffsets.OFFSETS[bug.dir]
    }

    /**
     * Executes the bug's logic until an action is performed.
     */
    fun executeTurn() {
        nextCell = world.grid.getUnchecked(posIndex + dirIndex)
        val code = this.bytecode
        var pc = this.i
        val codeSize = code.size

        var iterations = 0
        while (iterations++ < 100) {
            if (pc >= codeSize) return // Bytecode ended, no more actions to perform

            when (code.getUnchecked(pc++)) {
                Opcode.MOVE -> { performMove(); this.i = pc; return }
                Opcode.TURN_LEFT -> { performTurnLeft(); this.i = pc; return }
                Opcode.TURN_RIGHT -> { performTurnRight(); this.i = pc; return }
                Opcode.INFECT -> { performInfect(); this.i = pc; return }

                Opcode.JUMP -> pc = code.getUnchecked(pc) // jump to the specified instruction index
                Opcode.JUMP_IF_FALSE -> {
                    val conditionId = code.getUnchecked(pc++)
                    val jumpTarget = code.getUnchecked(pc++)
                    if (!evaluateCondition(conditionId)) {
                        pc = jumpTarget
                    }
                }

                else -> throw IllegalStateException("Invalid opcode")
            }
        }

        this.i = pc
    }

    private fun performMove() {
        if (nextCell == Cell.EMPTY.ordinal) {
            world.grid[posIndex] = Cell.EMPTY.ordinal
            world.bugGrid[posIndex] = null

            bug.posIndex += dirIndex
            posIndex = bug.posIndex

            world.grid[posIndex] = species
            world.bugGrid[posIndex] = bug
        }
    }

    private fun performTurnLeft() {
        val dir = (bug.dir + 3) and 3 // turn left (counter-clockwise)
        bug.dir = dir
        dirIndex = DirectionOffsets.OFFSETS[dir]
    }

    private fun performTurnRight() {
        val dir = (bug.dir + 1) and 3 // turn right (clockwise)
        bug.dir = dir
        dirIndex = DirectionOffsets.OFFSETS[dir]
    }

    private fun performInfect() {
        if (nextCell == enemy) {
            world.grid[posIndex + dirIndex] = species // change the cell to the bug's species
            val targetBug = world.bugGrid[posIndex + dirIndex] ?: return

            if (enemy == Cell.SPECIES1.ordinal) {
                world.species1Count--
                world.species2Count++
            } else {
                world.species2Count--
                world.species1Count++
            }

            val interpreter = world.interpreters[targetBug.id]
            if (interpreter != null) {
                interpreter.bytecode = this.bytecode
                interpreter.restartProgram()
            }
        }
    }

    private fun evaluateCondition(conditionId: Int): Boolean {

        return when (conditionId) {
            TRUE -> true
            RANDOM -> Random.nextBoolean()
            NEXT_IS_FRIEND -> nextCell == species
            NEXT_IS_WALL -> nextCell == CELL_WALL
            NEXT_IS_EMPTY -> nextCell == CELL_EMPTY
            NEXT_IS_ENEMY -> nextCell == enemy
            NEXT_IS_NOT_FRIEND -> nextCell != species
            NEXT_IS_NOT_WALL -> nextCell != CELL_WALL
            NEXT_IS_NOT_EMPTY -> nextCell != CELL_EMPTY
            NEXT_IS_NOT_ENEMY -> nextCell != enemy
            else -> throw IllegalStateException("Invalid condition id: $conditionId")
        }
    }
}

val NEXT_IS_WALL = Condition.NEXT_IS_WALL.ordinal
val NEXT_IS_EMPTY = Condition.NEXT_IS_EMPTY.ordinal
val NEXT_IS_FRIEND = Condition.NEXT_IS_FRIEND.ordinal
val NEXT_IS_ENEMY = Condition.NEXT_IS_ENEMY.ordinal
val NEXT_IS_NOT_WALL = Condition.NEXT_IS_NOT_WALL.ordinal
val NEXT_IS_NOT_EMPTY = Condition.NEXT_IS_NOT_EMPTY.ordinal
val NEXT_IS_NOT_FRIEND = Condition.NEXT_IS_NOT_FRIEND.ordinal
val NEXT_IS_NOT_ENEMY = Condition.NEXT_IS_NOT_ENEMY.ordinal

val TRUE = Condition.TRUE.ordinal
val RANDOM = Condition.RANDOM.ordinal

val CELL_WALL = Cell.WALL.ordinal
val CELL_EMPTY = Cell.EMPTY.ordinal