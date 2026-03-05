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
    const val GENERATIONS = 2500
    const val ELITE_COUNT = (POPULATION_SIZE * 0.075).toInt()
    const val TOURNAMENT_SIZE = (POPULATION_SIZE * 0.03).toInt()
    const val MUTATION_RATE = 2.5 // Base mutation rate, adjusted dynamically based on fitness and size
    const val DEFAULT_OPPONENTS = 5 // Each handcrafted opponent is played this many times (x5 opponents)
    const val MATCHES_PER_OPPONENT = 16
    const val MAX_NODES = 500
    const val NODE_PENALTY = 0.5
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

val apexSwarmLogic = WhileLoop(Condition.TRUE,
    IfStatement(Condition.NEXT_IS_ENEMY, Action.INFECT,
        IfStatement(Condition.NEXT_IS_EMPTY, Action.MOVE,
            IfStatement(Condition.RANDOM, Action.TURN_LEFT, Action.TURN_RIGHT)
        )
    )
)


val handcraftedOpponents = listOf(
    BugLogic("Default", defaultLogic),
    BugLogic("AggressiveScanner", aggressiveScannerLogic),
    BugLogic("WallFollower", wallFollowerLogic),
    BugLogic("RandomTurner", randomTurnerLogic),
    BugLogic("DoubleInfector", doubleInfectorLogic),
    BugLogic("ApexSwarm", apexSwarmLogic)
)

fun loadBestBugFromFile(): BugLogic? {
    val file = File("best_bug.bl")
    if (!file.exists()) return null
    return try {
        val bug = parseBL(file.readText())
        println("Loaded best bug from file: ${bug.name} (${bug.statement.countNodes()} nodes)")
        bug
    } catch (e: Exception) {
        println("Warning: Failed to parse best_bug.bl: ${e.message}")
        null
    }
}

data class GenerationRecord(val generation: Int, val bestBugs: List<BugLogic>) // Store top 3 best bugs from the generation

val generationHistory = mutableListOf<GenerationRecord>()

var population = MutableList(GaConfig.POPULATION_SIZE) {
    BugLogic(it.toString(), generateRandomAST(7))
}

fun main() {
    println("Starting Evolutionary Algorithm with population size ${GaConfig.POPULATION_SIZE} and ${GaConfig.GENERATIONS} generations")
    println("Configuration: TOURNAMENT_SIZE=${GaConfig.TOURNAMENT_SIZE}, MUTATION_RATE=${GaConfig.MUTATION_RATE}, NODE_PENALTY=${GaConfig.NODE_PENALTY}")

    val savedBug = loadBestBugFromFile()
    if (savedBug != null) {
        population[0] = savedBug
        // Also seed a few mutated variants of the saved bug
        for (i in 1..minOf(9, GaConfig.POPULATION_SIZE - 1)) {
            population[i] = BugLogic(UUID.randomUUID().toString(),
                mutate(savedBug.statement,
                    GaConfig.MUTATION_RATE * 2.0 /
                        savedBug.statement.countNodes().toDouble()))
        }
    }
    population.addAll(handcraftedOpponents)

    var bestEver: Pair<BugLogic, Double>? = null

    repeat(GaConfig.GENERATIONS) { gen ->
        simpleMap.clear()

        // Snapshot population for thread-safe parallel evaluation
        val populationSnapshot = population.toList()

        // Evaluation Phase
        var fitnessScores: List<Pair<BugLogic, Double>> = emptyList()
        val took = measureTimeMillis {
             fitnessScores = runBlocking(Dispatchers.Default) {
                populationSnapshot.map { bug ->
                    async {
                        bug to evaluateFitness(bug, bestEver?.first ?: bug, gen)
                    }
                }.awaitAll()
            }.sortedByDescending { it.second }
        }

        val bestThisGen = fitnessScores.first()
        if (bestEver == null || bestThisGen.second > bestEver.second) {
            bestEver = bestThisGen
        }

        // Record best opponent from this generation
        generationHistory.add(GenerationRecord(gen, fitnessScores.take(3).map { it.first }))

        val minFitness = fitnessScores.last().second
        val avgFitness = fitnessScores.map { it.second }.average()
        val maxFitness = fitnessScores.first().second
        val uniqueNodes = bestThisGen.first.statement.countNodes()

        // Test best against all handcrafted opponents
        val beatResults = mutableListOf<Pair<String, Boolean>>()
        repeat(20) { handcraftedOpponents.map { opponent ->
            val simulation = Simulation(17, bestThisGen.first, opponent)
            val winner = simulation.playMatch()
            beatResults.add(opponent.name to (winner.first == 1))
        }}
        // Summarize per opponent results
        val beatsSummary = beatResults.groupBy { it.first }.mapValues { entry ->
            val wins = entry.value.count { it.second }
            "$wins/${entry.value.size}"
        }.entries.joinToString(", ") { "${it.key}: ${it.value}" }

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

        // Keep the top N (Elitism) + all-time best + handcrafted opponents
        newPopulation.addAll(fitnessScores.take(GaConfig.ELITE_COUNT).map { it.first })
        newPopulation.add(bestEver.first)

        // Fill the rest of the population, ensuring all children can infect
        while (newPopulation.size < GaConfig.POPULATION_SIZE) {
            val parent = tournamentSelection(fitnessScores)
            val mate = tournamentSelection(fitnessScores)
            val child = crossOver(parent.statement, mate.statement)
            val mutationRate = ((if (avgFitness.toInt() < 1000) GaConfig.MUTATION_RATE * 2 else GaConfig.MUTATION_RATE)
                    / parent.statement.countNodes().toDouble())

            val childAST = mutate(child, mutationRate)

            // Only add children that can infect
            if (childAST.getAllNodes().any { node -> node == Action.INFECT }) {
                newPopulation.add(BugLogic(UUID.randomUUID().toString(), childAST))
            }
        }
        population = newPopulation
    }

    println("\nFinal best bug (all generations):")
    if (bestEver != null) {
        println(bestEver.first.statement.toBL())
        saveBestBugToFile(bestEver.first)
    }
}

fun getBestOpponentsCandidates(currentGen: Int): List<BugLogic> {
    val candidates = mutableSetOf<BugLogic>()

    // Add best 3 opponents from last 3 generations
    if (generationHistory.isNotEmpty()) {
        val recentCutoff = maxOf(0, currentGen - 3)
        val recentOpponents = generationHistory
            .filter { it.generation >= recentCutoff }
            .flatMap { it.bestBugs }
        candidates.addAll(recentOpponents)

        // Add best opponents from last 20 generations for long-term memory
        val longTermCutoff = maxOf(0, currentGen - 20)
        val longTermBest = generationHistory
            .filter { it.generation >= longTermCutoff }
            .map { it.bestBugs.first() }
        candidates.addAll(longTermBest)
    }

    if (currentGen > 20) {
        // After 20 generations, also include the best ever bug to ensure it's always a benchmark
        val bestEver = generationHistory.flatMap { it.bestBugs }.maxByOrNull { evaluateFitness(it, it) }
        if (bestEver != null) {
            candidates.add(bestEver)
        }
    }

    return candidates.toList()
}

fun evaluateFitness(bug: BugLogic, bestEver: BugLogic, currentGen: Int = 0): Double {
    var score = 0.0
    val nodeCount = bug.statement.countNodes()

    // Hard cap: penalize heavily if too large
    if (nodeCount > GaConfig.MAX_NODES) {
        return -10000.0
    }

    val evalBug = BugLogic(bug.name, bug.statement.simplify())

    val opponents = mutableListOf<BugLogic>()

    // Get best opponents from recent history
    val bestOpponents = getBestOpponentsCandidates(currentGen)
    bestOpponents.forEach { opponent ->
        opponents.add(BugLogic(opponent.name, opponent.statement.simplify()))
    }

    // Add handcrafted opponents
    repeat(GaConfig.DEFAULT_OPPONENTS) {
        opponents.addAll(handcraftedOpponents)
    }

    // Test against the best ever
    repeat(2) {
        opponents.add(BugLogic(bestEver.name, bestEver.statement.simplify()))
    }

    var timeouts = 0

    var totalLosses = 0
    for (opponent in opponents) {
        var wins = 0
        repeat(GaConfig.MATCHES_PER_OPPONENT) {
            val simulation = Simulation(17, evalBug, opponent)
            val winner = simulation.playMatch()
            val duration = winner.second
            if (duration == 2001) {
                timeouts++
                if (timeouts > 5) {
                    // If too many timeouts, assume the bug sucks and penalize heavily
                    return -2000.0 / 20.0 * GaConfig.MATCHES_PER_OPPONENT
                }
            }

            when (winner.first) {
                1 -> {
                    wins += 1
                    score += (2000 - duration) / 20 // Faster wins are better
                }
                2 -> score -= (2000 - duration) / 20 // Losing faster is worse
            }
        }
        if (wins == GaConfig.MATCHES_PER_OPPONENT) {
            score += 5000.0 // Huge bonus for perfect sweep against an opponent
        } else if (wins >= GaConfig.MATCHES_PER_OPPONENT / 1.25) {
            score += 3000.0 // Bonus for beating an opponent in a large majority of matches
        } else if (wins >= GaConfig.MATCHES_PER_OPPONENT / 1.5) {
            score += 1500.0 // Smaller bonus for winning most matches, even if not dominant
        } else if (wins >= GaConfig.MATCHES_PER_OPPONENT / 2) {
            score += 500.0 // Small bonus for winning at least half the matches, showing some competence
        } else if (wins >= GaConfig.MATCHES_PER_OPPONENT / 3) {
            score += 100.0 // Minimal bonus for winning some matches, but likely not a strong opponent
        } else if (wins == 0) {
            totalLosses++
            score -= 2000.0 // Penalty for being completely dominated by an opponent
            if (totalLosses > opponents.size / 10) {
                // If losing to most opponents, assume the bug is very bad and penalize heavily
                return score - 10000.0
            }
        }
    }

    score -= nodeCount * GaConfig.NODE_PENALTY
    score -= (bug.statement.getAllNodes().count { it is IfStatement && it.condition == Condition.RANDOM }
            * GaConfig.NODE_PENALTY)

    return score
}

fun tournamentSelection(scoredPopulation: List<Pair<BugLogic, Double>>): BugLogic {
    val rng = java.util.concurrent.ThreadLocalRandom.current()
    var bestScore = Double.NEGATIVE_INFINITY
    var best: BugLogic? = null
    repeat(GaConfig.TOURNAMENT_SIZE) {
        val candidate = scoredPopulation[rng.nextInt(scoredPopulation.size)]
        if (candidate.second > bestScore) {
            bestScore = candidate.second
            best = candidate.first
        }
    }
    return best!!
}

fun saveBestBugToFile(bug: BugLogic) {
    File("best_bug.bl").writeText(bug.statement.simplify().toBL())
}