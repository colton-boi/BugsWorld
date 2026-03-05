package me.thecodingduck.bugsworld

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.thecodingduck.bugsworld.world.Simulation
import java.io.File
import java.util.UUID
import kotlin.system.measureTimeMillis

object GaConfig {
    const val POPULATION_SIZE = 1000
    const val GENERATIONS = 500
    const val ELITE_COUNT = 10
    const val TOURNAMENT_SIZE = (POPULATION_SIZE * 0.05).toInt() // 5% of the population for selection pressure
    const val MUTATION_RATE = 0.1
    const val RANDOM_OPPONENTS = 15
    const val DEFAULT_OPPONENTS = 2 // Each handcrafted opponent is played this many times (×5 opponents)
    const val MAX_NODES = 60
    const val NODE_PENALTY = 5
    val GRID_SIZES = listOf(17)
}

val defaultLogic = WhileLoop(Condition.TRUE,
    Block(mutableListOf(
        WhileLoop(Condition.NEXT_IS_EMPTY, Action.MOVE),
        IfStatement(Condition.NEXT_IS_ENEMY,
            Action.INFECT,
            IfStatement(Condition.NEXT_IS_WALL, Action.TURN_LEFT)), Action.INFECT))
    )

// Aggressive Scanner: prioritizes infecting, moves into empty space, turns right at obstacles
val aggressiveScannerLogic = WhileLoop(Condition.TRUE,
    IfStatement(Condition.NEXT_IS_ENEMY,
        Action.INFECT,
        IfStatement(Condition.NEXT_IS_EMPTY,
            Action.MOVE,
            Action.TURN_RIGHT))
)

// Wall Follower: infects enemies, turns left at walls, turns right past friends, otherwise moves
val wallFollowerLogic = WhileLoop(Condition.TRUE,
    IfStatement(Condition.NEXT_IS_ENEMY,
        Action.INFECT,
        IfStatement(Condition.NEXT_IS_WALL,
            Action.TURN_LEFT,
            IfStatement(Condition.NEXT_IS_FRIEND,
                Action.TURN_RIGHT,
                Action.MOVE)))
)

// Random Turner: rushes forward, infects enemies, uses random turns at walls for unpredictability
val randomTurnerLogic = WhileLoop(Condition.TRUE,
    Block(mutableListOf(
        WhileLoop(Condition.NEXT_IS_EMPTY, Action.MOVE),
        IfStatement(Condition.NEXT_IS_ENEMY,
            Action.INFECT,
            IfStatement(Condition.NEXT_IS_WALL,
                IfStatement(Condition.RANDOM, Action.TURN_LEFT, Action.TURN_RIGHT),
                Action.TURN_LEFT))))
)

// Double Infector: checks and infects from current facing, then moves/turns and checks again
val doubleInfectorLogic = WhileLoop(Condition.TRUE,
    Block(mutableListOf(
        IfStatement(Condition.NEXT_IS_ENEMY,
            Action.INFECT,
            IfStatement(Condition.NEXT_IS_EMPTY, Action.MOVE, Action.TURN_RIGHT)),
        IfStatement(Condition.NEXT_IS_ENEMY, Action.INFECT)))
)

val handcraftedOpponents = listOf(
    BugLogic("Default", defaultLogic),
    BugLogic("AggressiveScanner", aggressiveScannerLogic),
    BugLogic("WallFollower", wallFollowerLogic),
    BugLogic("RandomTurner", randomTurnerLogic),
    BugLogic("DoubleInfector", doubleInfectorLogic),
)

var population = MutableList(GaConfig.POPULATION_SIZE) {
    BugLogic(it.toString(), generateRandomAST(5))
}

fun main() {
    println("Starting Evolutionary Algorithm with population size ${GaConfig.POPULATION_SIZE} and ${GaConfig.GENERATIONS} generations")
    println("Configuration: TOURNAMENT_SIZE=${GaConfig.TOURNAMENT_SIZE}, MUTATION_RATE=${GaConfig.MUTATION_RATE}, NODE_PENALTY=${GaConfig.NODE_PENALTY}")

    var bestEver: Pair<BugLogic, Double>? = null

    repeat(GaConfig.GENERATIONS) { gen ->
        simpleMap.clear()

        // Evaluation Phase
        var fitnessScores: List<Pair<BugLogic, Double>> = emptyList()
        val took = measureTimeMillis {
             fitnessScores = runBlocking(Dispatchers.Default) {
                population.map { bug ->
                    async {
                        bug to evaluateFitness(bug)
                    }
                }.awaitAll()
            }.sortedByDescending { it.second }
        }

        val bestThisGen = fitnessScores.first()
        if (bestEver == null || bestThisGen.second > bestEver.second) {
            bestEver = bestThisGen
        }

        val minFitness = fitnessScores.last().second
        val avgFitness = fitnessScores.map { it.second }.average()
        val maxFitness = fitnessScores.first().second
        val uniqueNodes = bestThisGen.first.statement.countNodes()

        // Test best against all handcrafted opponents
        val beatResults = handcraftedOpponents.map { opponent ->
            val simulation = Simulation(17, bestThisGen.first, opponent)
            val winner = simulation.playMatch()
            opponent.name to (winner.first == 1)
        }
        val beatsAll = beatResults.all { it.second }
        val beatsSummary = beatResults.joinToString(", ") { "${it.first}=${it.second}" }

        val winsCount = beatResults.count { it.second }
        println("Gen $gen: size=${population.size}, " +
                "min/avg/max fitness = ${minFitness.toInt()} / ${avgFitness.toInt()} / ${maxFitness.toInt()}, " +
                "nodes=${uniqueNodes}, wins=${winsCount}/${beatResults.size}, time=${took}ms")
        println("  Matchups: $beatsSummary")

        if (gen % 10 == 0) {
            println("Best Logic:\n${bestThisGen.first.statement.toBL()}\n")
            saveBestBugToFile(bestThisGen.first)
        }

        // Selection & Reproduction
        val newPopulation = mutableListOf<BugLogic>()

        // Keep the top N (Elitism)
        newPopulation.addAll(fitnessScores.take(GaConfig.ELITE_COUNT).map { it.first })
        newPopulation.addAll(handcraftedOpponents)

        // Fill the rest of the population
        while (newPopulation.size < GaConfig.POPULATION_SIZE) {
            val parentA = tournamentSelection(fitnessScores)
            val parentB = tournamentSelection(fitnessScores)

            var childAST = crossOver(parentA.statement, parentB.statement)
            val adaptedRate = if (fitnessScores.last().second - fitnessScores.first().second < 100.0) {
                GaConfig.MUTATION_RATE * 1.5
            } else {
                GaConfig.MUTATION_RATE
            }
            childAST = mutate(childAST, adaptedRate)

            newPopulation.add(BugLogic(UUID.randomUUID().toString(), childAST))
        }
        population = newPopulation

        population.toList().forEach {
            if (it.statement.getAllNodes().none { node -> node == Action.INFECT }) { // Remove any that can't even infect
                population.remove(it)
            }
        }
    }

    println("\nFinal best bug (all generations):")
    if (bestEver != null) {
        println(bestEver.first.statement.toBL())
        saveBestBugToFile(bestEver.first)
    }
}

fun evaluateFitness(bug: BugLogic): Double {
    var score = 0.0
    val nodeCount = bug.statement.countNodes()

    // Hard cap: penalize heavily if too large
    if (nodeCount > GaConfig.MAX_NODES) {
        return -10000.0
    }

    // Simplify a copy for simulation — removes IF TRUE and single-element Blocks
    // without touching the original tree used for reproduction
    val evalBug = BugLogic(bug.name, bug.statement.simplify())

    // Create a diverse set of opponents
    val opponents = mutableListOf<BugLogic>()
    repeat(GaConfig.RANDOM_OPPONENTS) {
        // Get a random logic from the population as an opponent
        val randomOpponent = population.random()
        opponents.add(BugLogic(randomOpponent.name, randomOpponent.statement.simplify()))
    }
    repeat(GaConfig.DEFAULT_OPPONENTS) {
        opponents.addAll(handcraftedOpponents)
    }

    for (opponent in opponents) {
        GaConfig.GRID_SIZES.forEach { sideLength ->
            val simulation = Simulation(sideLength, evalBug, opponent)
            val winner = simulation.playMatch()
            val duration = winner.second

            when (winner.first) {
                1 -> {
                    score += 500
                    score += (2000 - duration) / 10
                }
                2 -> score -= 500
                else -> score += 75
            }
        }
    }

    score -= nodeCount * GaConfig.NODE_PENALTY
    score -= (bug.statement.getAllNodes().count { it == Action.INFECT } - 1) * 10.0 // penalize to prevent INFECT spam

    return score
}

fun tournamentSelection(scoredPopulation: List<Pair<BugLogic, Double>>): BugLogic {
    // Pick TOURNAMENT_SIZE random individuals, return the best
    return scoredPopulation.shuffled().subList(0, GaConfig.TOURNAMENT_SIZE).maxByOrNull { it.second }!!.first
}

fun saveBestBugToFile(bug: BugLogic) {
    File("best_bug.bl").writeText(bug.toBL())
}