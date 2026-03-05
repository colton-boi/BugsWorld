package me.thecodingduck.bugsworld.world

import kotlinx.coroutines.yield
import me.thecodingduck.bugsworld.Action
import me.thecodingduck.bugsworld.Block
import me.thecodingduck.bugsworld.BugLogic
import me.thecodingduck.bugsworld.Condition
import me.thecodingduck.bugsworld.IfStatement
import me.thecodingduck.bugsworld.Statement
import me.thecodingduck.bugsworld.WhileLoop

class BugInterpreter(val bug: Bug, val logic: BugLogic, private val world: World) {
    /**
     * The cell the bug is currently targeting, used for condition evaluation.
     * This is updated at the start of each turn to reflect the cell in front of the bug.
     */
    var target: Cell = Cell.EMPTY

    /**
     * The species of the bug, used for condition evaluation.
     * This is determined by the bug's species ID and is constant throughout the simulation.
     */
    private val species = if (bug.speciesId == 1) Cell.SPECIES1 else Cell.SPECIES2

    /**
     * The enemy species of the bug, used for condition evaluation.
     */
    private val enemy = if (bug.speciesId == 1) Cell.SPECIES2 else Cell.SPECIES1
    private var iterations = 0

    /**
     * Runs one turn of the bug's logic, updating the bug's state and the world accordingly.
     */
    val turnIterator = sequence {
        while (true) {
            executeNode(logic.statement)
            yield(Unit) // Failsafe yield if the AST tree is empty
        }
    }.iterator()

    /**
     * Recursively executes a statement node, updating the bug's state and the world as needed.
     * Yields after each action to allow for turn-based simulation.
     * Has an iteration limit to prevent infinite loops from hanging.
     */
    private suspend fun SequenceScope<Unit>.executeNode(node: Statement) {
        iterations++
        if (iterations > 100) {
            // Prevent infinite loops by yielding after too many iterations
            yield(Unit)
            iterations = 0
        }
        when (node) {
            is Action -> {
                performAction(node)
                yield(Unit) // End the turn after taking an action
            }
            is IfStatement -> executeIf(node)
            is WhileLoop -> executeWhile(node)
            is Block -> node.statements.forEach { executeNode(it) }
        }
    }

    private fun performAction(node: Action) {
        when (node) {
            Action.MOVE -> {
                val nextPos = nextPos()
                if (world.grid[nextPos.x][nextPos.y] == Cell.EMPTY) {
                    world.grid[bug.position.x][bug.position.y] = Cell.EMPTY
                    bug.position = nextPos
                    world.grid[nextPos.x][nextPos.y] = species
                }
            }
            Action.TURN_LEFT -> bug.direction = when (bug.direction) {
                Direction.NORTH -> Direction.WEST
                Direction.EAST -> Direction.NORTH
                Direction.SOUTH -> Direction.EAST
                Direction.WEST -> Direction.SOUTH
            }
            Action.TURN_RIGHT -> bug.direction = when (bug.direction) {
                Direction.NORTH -> Direction.EAST
                Direction.EAST -> Direction.SOUTH
                Direction.SOUTH -> Direction.WEST
                Direction.WEST -> Direction.NORTH
            }
            Action.INFECT -> {
                val nextPos = nextPos()
                if (world.grid[nextPos.x][nextPos.y] == enemy) {
                    world.grid[nextPos.x][nextPos.y] = species
                }
                world.bugs.find { it.position == nextPos }?.let { it.speciesId = bug.speciesId }
            }
        }
    }

    private suspend fun SequenceScope<Unit>.executeIf(node: IfStatement) {
        if (evaluateCondition(node.condition)) {
            executeNode(node.thenBlock)
        } else if (node.elseBlock != null) {
            executeNode(node.elseBlock)
        }
    }

    private suspend fun SequenceScope<Unit>.executeWhile(node: WhileLoop) {
        while (evaluateCondition(node.condition)) {
            executeNode(node.body)
        }
    }

    private fun evaluateCondition(condition: Condition): Boolean {
        val nextPos = nextPos()
        target = world.grid[nextPos.x][nextPos.y]
        return when (condition) {
            Condition.NEXT_IS_EMPTY -> target == Cell.EMPTY
            Condition.NEXT_IS_WALL -> target == Cell.WALL
            Condition.NEXT_IS_ENEMY -> target == enemy
            Condition.NEXT_IS_FRIEND -> target == species
            Condition.NEXT_IS_NOT_EMPTY -> target != Cell.EMPTY
            Condition.NEXT_IS_NOT_WALL -> target != Cell.WALL
            Condition.NEXT_IS_NOT_ENEMY -> target != enemy
            Condition.NEXT_IS_NOT_FRIEND -> target != species
            Condition.TRUE -> true
            Condition.RANDOM -> Math.random() < 0.5
        }
    }

    fun nextPos(): Point = when (bug.direction) {
        Direction.NORTH -> Point(bug.position.x, bug.position.y - 1)
        Direction.EAST -> Point(bug.position.x + 1, bug.position.y)
        Direction.SOUTH -> Point(bug.position.x, bug.position.y + 1)
        Direction.WEST -> Point(bug.position.x - 1, bug.position.y)
    }
}