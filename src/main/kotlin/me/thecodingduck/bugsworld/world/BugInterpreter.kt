package me.thecodingduck.bugsworld.world

import me.thecodingduck.bugsworld.Action
import me.thecodingduck.bugsworld.Block
import me.thecodingduck.bugsworld.BugLogic
import me.thecodingduck.bugsworld.Condition
import me.thecodingduck.bugsworld.IfStatement
import me.thecodingduck.bugsworld.Statement
import me.thecodingduck.bugsworld.WhileLoop
import java.util.concurrent.ThreadLocalRandom

/**
 * Stack-based AST interpreter. Each call to [executeTurn] runs until one action
 * is performed, then returns. The execution stack persists across turns so the
 * program counter is maintained.
 */
class BugInterpreter(val bug: Bug, var logic: BugLogic, private val world: World) {
    private var species: Cell = Cell.EMPTY
    private var enemy: Cell = Cell.EMPTY

    // Execution stack frames
    private sealed class Frame {
        /** Tracks position within a Block's statement list */
        class BlockFrame(val statements: List<Statement>, var index: Int) : Frame()
        /** Re-evaluates condition each iteration; pushes body when true */
        class WhileFrame(val loop: WhileLoop) : Frame()
        class ActionFrame(val action: Action) : Frame()
    }

    private val stack = ArrayDeque<Frame>(16)

    private fun resetStack() {
        stack.clear()
        pushNode(logic.statement)
    }

    init { restartIterator() }

    fun restartIterator() {
        updateFactions()
        resetStack()
    }

    private fun updateFactions() {
        species = if (bug.speciesId == 1) Cell.SPECIES1 else Cell.SPECIES2
        enemy = if (bug.speciesId == 1) Cell.SPECIES2 else Cell.SPECIES1
    }

    /** Push a statement onto the stack for future execution. */
    private fun pushNode(node: Statement) {
        when (node) {
            is Block -> {
                if (node.statements.isNotEmpty()) {
                    stack.addLast(Frame.BlockFrame(node.statements, 0))
                }
            }
            is WhileLoop -> stack.addLast(Frame.WhileFrame(node))
            is IfStatement -> {
                if (evaluateCondition(node.condition)) {
                    pushNode(node.thenBlock)
                } else if (node.elseBlock != null) {
                    pushNode(node.elseBlock)
                }
            }
            is Action -> {
                stack.addLast(Frame.ActionFrame(node))
            }
        }
    }

    /**
     * Execute one turn: process the stack until an action is performed.
     * Returns immediately after the action so each bug gets one action per turn.
     */
    fun executeTurn() {
        var iterations = 0
        while (true) {
            if (stack.isEmpty()) resetStack()
            if (stack.isEmpty() || ++iterations > 100) return // prevent runaway loops

            when (val frame = stack.last()) {
                is Frame.BlockFrame -> {
                    if (frame.index >= frame.statements.size) {
                        stack.removeLast()
                        continue
                    }
                    val node = frame.statements[frame.index]
                    frame.index++

                    when (node) {
                        is Action -> {
                            performAction(node)
                            return // end of turn
                        }
                        is Block -> {
                            if (node.statements.isNotEmpty()) {
                                stack.addLast(Frame.BlockFrame(node.statements, 0))
                            }
                        }
                        is IfStatement -> {
                            if (evaluateCondition(node.condition)) {
                                pushNode(node.thenBlock)
                            } else if (node.elseBlock != null) {
                                pushNode(node.elseBlock)
                            }
                        }
                        is WhileLoop -> {
                            stack.addLast(Frame.WhileFrame(node))
                        }
                    }
                }
                is Frame.WhileFrame -> {
                    if (evaluateCondition(frame.loop.condition)) {
                        pushNode(frame.loop.body)
                    } else {
                        stack.removeLast()
                    }
                }
                is Frame.ActionFrame -> {
                    stack.removeLast()
                    performAction(frame.action)
                    return // end of turn
                }
            }
        }
    }

    private fun performAction(node: Action) {
        when (node) {
            Action.MOVE -> {
                val nx = nextX()
                val ny = nextY()
                if (world.grid[nx][ny] == Cell.EMPTY) {
                    world.grid[bug.xPos][bug.yPos] = Cell.EMPTY
                    world.bugGrid[bug.xPos][bug.yPos] = null

                    bug.xPos = nx
                    bug.yPos = ny

                    world.grid[nx][ny] = species
                    world.bugGrid[nx][ny] = bug
                }
            }
            Action.TURN_LEFT -> bug.direction = bug.direction.left
            Action.TURN_RIGHT -> bug.direction = bug.direction.right
            Action.INFECT -> {
                val nx = nextX()
                val ny = nextY()
                if (world.grid[nx][ny] == enemy) {
                    world.grid[nx][ny] = species
                    val targetBug = world.bugGrid[nx][ny]
                    if (targetBug != null) {
                        if (targetBug.speciesId == 1 && bug.speciesId == 2) {
                            world.species1Count--
                            world.species2Count++
                        } else if (targetBug.speciesId == 2 && bug.speciesId == 1) {
                            world.species2Count--
                            world.species1Count++
                        } else {
                            // Already same species, don't change counts or restart logic
                            return
                        }
                        targetBug.speciesId = bug.speciesId
                        val interp = world.interpreters[targetBug.id]
                        if (interp != null) {
                            interp.logic = logic
                            interp.restartIterator()
                        }
                    }
                }
            }
        }
    }

    private fun evaluateCondition(condition: Condition): Boolean {
        if (condition == Condition.TRUE) return true
        if (condition == Condition.RANDOM) return ThreadLocalRandom.current().nextBoolean()

        val cell = world.grid[nextX()][nextY()]
        return when (condition) {
            Condition.NEXT_IS_EMPTY -> cell == Cell.EMPTY
            Condition.NEXT_IS_WALL -> cell == Cell.WALL
            Condition.NEXT_IS_ENEMY -> cell == enemy
            Condition.NEXT_IS_FRIEND -> cell == species
            Condition.NEXT_IS_NOT_EMPTY -> cell != Cell.EMPTY
            Condition.NEXT_IS_NOT_WALL -> cell != Cell.WALL
            Condition.NEXT_IS_NOT_ENEMY -> cell != enemy
            Condition.NEXT_IS_NOT_FRIEND -> cell != species
            else -> false // Should never happen since all conditions are covered
        }
    }

    private fun nextX(): Int = when (bug.direction) {
        Direction.NORTH, Direction.SOUTH -> bug.xPos
        Direction.EAST -> bug.xPos + 1
        Direction.WEST -> bug.xPos - 1
    }

    private fun nextY(): Int = when (bug.direction) {
        Direction.EAST, Direction.WEST -> bug.yPos
        Direction.NORTH -> bug.yPos - 1
        Direction.SOUTH -> bug.yPos + 1
    }
}