package me.thecodingduck.bugsworld.world

import me.thecodingduck.bugsworld.GaConfig


class Simulation(sideLength: Int, private val bugOne: IntArray, private val bugTwo: IntArray) {
    private val world = World(sideLength)
    var interpreters = world.interpreters

    private fun spawnBug(speciesId: Int, posIndex: Int, dir: Int) {
        val bug = Bug(world.bugs.size, posIndex, dir)
        world.bugs.add(bug)
        world.grid[posIndex] =
            (if (speciesId == 1) Cell.SPECIES1 else Cell.SPECIES2).ordinal
        world.bugGrid[posIndex] = bug
        if (speciesId == 1) world.species1Count++ else world.species2Count++
        val interpreter = BugInterpreter(bug,if (speciesId == 1) bugOne else bugTwo, world)
        world.interpreters[bug.id] = interpreter
        world.interpreterCount++
    }

    private fun spawnBugRandom(speciesId: Int) {
        var posIndex: Int
        do {
            posIndex = (0 until world.grid.size).random()
        } while (world.grid[posIndex] != Cell.EMPTY.ordinal)

        spawnBug(speciesId, posIndex, (0..3).random())
    }

    private fun spawnWalls() {
        for (i in 0 until world.sideLength) {
            world.grid[i] = Cell.WALL.ordinal // Top row
            world.grid[(world.sideLength - 1) * world.sideLength + i] = Cell.WALL.ordinal // Bottom row
            world.grid[i * world.sideLength] = Cell.WALL.ordinal // Left column
            world.grid[i * world.sideLength + (world.sideLength - 1)] = Cell.WALL.ordinal // Right column
        }
    }

    private fun setup() {
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

        interpreters = world.interpreters
    }

    private fun playTurn() {
        interpreters.forEach {
            it?.executeTurn()
        }
    }

    fun playMatch(): Int {
        setup()
        var turns = 0
        while (world.species1Count > 0 && world.species2Count > 0) {
            playTurn()
            turns++
            if (turns >= GaConfig.TIMEOUT_THRESHOLD) {
                val winner = if (world.species1Count > world.species2Count) 1 else 2
                return packResult(winner, turns)
            }
            if (turns % 25 == 0) {
                if (world.species1Count > world.species2Count * 2) return packResult(1, turns)
                if (world.species2Count > world.species1Count * 2) return packResult(2, turns)
            }
        }
        val winner = if (world.species1Count > 0) 1 else 2
        return packResult(winner, turns)
    }


    companion object {
        fun packResult(winner: Int, turns: Int): Int {
            return (winner shl 16) or (turns and 0xFFFF)
        }

        fun unpackWinner(result: Int): Int {
            return result ushr 16
        }

        fun unpackTurns(result: Int): Int {
            return result and 0xFFFF
        }
    }
}