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
    const val POPULATION_SIZE = 250
    const val GENERATIONS = 50
    const val ELITE_COUNT = 10
    const val TOURNAMENT_SIZE = (POPULATION_SIZE * 0.05).toInt() // 5% of the population for selection pressure
    const val MUTATION_RATE = 0.1
    const val RANDOM_OPPONENTS = 25
    const val DEFAULT_OPPONENTS = 10
    const val MAX_NODES = 60
    const val NODE_PENALTY = 6
    val GRID_SIZES = listOf(17)
}

val defaultLogic = WhileLoop(Condition.TRUE,
    Block(mutableListOf(
        WhileLoop(Condition.NEXT_IS_EMPTY, Action.MOVE),
        IfStatement(Condition.NEXT_IS_ENEMY,
            Action.INFECT,
            IfStatement(Condition.NEXT_IS_WALL, Action.TURN_LEFT)), Action.INFECT))
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

        // Test best against default
        val opponent = BugLogic("Default", defaultLogic)
        val simulation = Simulation(17, bestThisGen.first, opponent)
        val winner = simulation.playMatch()
        val beatsDefault = winner.first == 1

        println("Gen $gen: size=${population.size}, " +
                "min/avg/max fitness = ${minFitness.toInt()} / ${avgFitness.toInt()} / ${maxFitness.toInt()}, " +
                "nodes=${uniqueNodes}, beats_default=$beatsDefault, time=${took}ms")

        if (gen % 10 == 0) {
            println("Best Logic:\n${bestThisGen.first.statement.toBL()}\n")
            saveBestBugToFile(bestThisGen.first)
        }

        // Selection & Reproduction
        val newPopulation = mutableListOf<BugLogic>()

        // Keep the top N (Elitism)
        newPopulation.addAll(fitnessScores.take(GaConfig.ELITE_COUNT).map { it.first })
        newPopulation.add(BugLogic("Default", defaultLogic))

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
        opponents.add(BugLogic("Default", defaultLogic))
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