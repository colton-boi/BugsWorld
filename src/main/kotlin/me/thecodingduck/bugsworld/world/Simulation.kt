package me.thecodingduck.bugsworld.world

import me.thecodingduck.bugsworld.BugLogic

class Simulation(sideLength: Int, private val bugOne: BugLogic, private val bugTwo: BugLogic) {
    private val world = World(sideLength)
    private val interpreters = mutableListOf<BugInterpreter>()

    private fun spawnBug(speciesId: Int, position: Point, direction: Direction) {
        val bug = Bug(world.bugs.size, position, direction, speciesId)
        world.bugs.add(bug)
        world.grid[position.x][position.y] = if (speciesId == 1) Cell.SPECIES1 else Cell.SPECIES2
        interpreters.add(BugInterpreter(bug, if (speciesId == 1) bugOne else bugTwo, world))
    }

    private fun spawnBugRandom(speciesId: Int) {
        var position: Point
        do {
            position = Point((0 until world.sideLength).random(), (0 until world.sideLength).random())
        } while (world.grid[position.x][position.y] != Cell.EMPTY)
        val direction = Direction.entries.toTypedArray().random()
        spawnBug(speciesId, position, direction)
    }

    private fun spawnWalls() {
        for (i in 0 until world.sideLength) {
            world.grid[0][i] = Cell.WALL
            world.grid[world.sideLength - 1][i] = Cell.WALL
            world.grid[i][0] = Cell.WALL
            world.grid[i][world.sideLength - 1] = Cell.WALL
        }
    }

    private fun setup() {
        world.grid.forEach { row -> row.fill(Cell.EMPTY) }
        spawnWalls()
        // Spawn 10 of each in random orders
        var totalOne = 0
        var totalTwo = 0
        repeat(20) {
            if (totalOne < 10 && (totalTwo >= 10 || Math.random() < 0.5)) {
                spawnBugRandom(1)
                totalOne++
            } else {
                spawnBugRandom(2)
                totalTwo++
            }
        }
    }

    private fun playTurn() {
        // Iterate through all bugs and trigger their turn
        for (interpreter in interpreters) {
            // Execute the bug's logic until it hits a yield(Unit)
            if (interpreter.turnIterator.hasNext()) {
                interpreter.turnIterator.next()
            }
        }
    }

    fun playMatch(): Pair<Int, Int> {
        setup()
        var it = 0
        while (world.bugs.count { it.speciesId == 1 } > 0 && world.bugs.count { it.speciesId == 2 } > 0) {
            playTurn()
            it++
            if (it > 2000) {
                return 0 to it // Draw after 2k turns to prevent infinite loops
            }
        }
        return (if (world.bugs.count { it.speciesId == 1 } > 0) 1 else 2) to it
    }
}