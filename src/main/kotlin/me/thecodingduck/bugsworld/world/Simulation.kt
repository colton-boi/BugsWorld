package me.thecodingduck.bugsworld.world

import me.thecodingduck.bugsworld.BugLogic

class Simulation(sideLength: Int, private val bugOne: BugLogic, private val bugTwo: BugLogic) {
    private val world = World(sideLength)

    private fun spawnBug(speciesId: Int, position: Point, direction: Direction) {
        val bug = Bug(world.bugs.size, position.x, position.y, direction, speciesId)
        world.bugs.add(bug)
        world.grid[position.x][position.y] = if (speciesId == 1) Cell.SPECIES1 else Cell.SPECIES2
        world.bugGrid[position.x][position.y] = bug
        if (speciesId == 1) world.species1Count++ else world.species2Count++
        val interpreter = BugInterpreter(bug, if (speciesId == 1) bugOne else bugTwo, world)
        world.interpreters[bug.id] = interpreter
        world.interpreterCount++
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
        repeat(100) {
            if (totalOne < 50 && (totalTwo >= 50 || java.util.concurrent.ThreadLocalRandom.current().nextDouble() < 0.5)) {
                spawnBugRandom(1)
                totalOne++
            } else {
                spawnBugRandom(2)
                totalTwo++
            }
        }
    }

    private fun playTurn() {
        val interps = world.interpreters
        for (i in 0 until world.interpreterCount) {
            interps[i]!!.executeTurn()
        }
    }

    fun playMatch(): Pair<Int, Int> {
        setup()
        var it = 0
        while (world.species1Count > 0 && world.species2Count > 0) {
            playTurn()
            it++
            if (it >= 1000) {
                // Timeout, determine winner by who has more bugs
                return (if (world.species1Count > world.species2Count) 1 else 2) to it
            }
        }
        return (if (world.species1Count > 0) 1 else 2) to it
    }
}