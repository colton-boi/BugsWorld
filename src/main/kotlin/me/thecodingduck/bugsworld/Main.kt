package me.thecodingduck.bugsworld

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.thecodingduck.bugsworld.bytecode.Compiler
import me.thecodingduck.bugsworld.world.Simulation
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.system.measureTimeMillis

object GaConfig {
    const val POPULATION_SIZE = 500
    const val GENERATIONS = 2500
    const val ELITE_COUNT = (POPULATION_SIZE * 0.075).toInt()
    const val TOURNAMENT_SIZE = (POPULATION_SIZE * 0.05).toInt()
    const val MUTATION_RATE = 2 // Base mutation rate, adjusted dynamically based on fitness and size
    const val DEFAULT_OPPONENTS = 1 // Each handcrafted opponent is played this many times (x5 opponents)
    const val MATCHES_PER_OPPONENT = 128
    const val MAX_NODES = 500
    const val SIDE_LENGTH = 17
    const val TIMEOUT_THRESHOLD = 1000 // turns, matches lasting longer than this are considered ties
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

val spinToWinLogic = WhileLoop(Condition.TRUE,
    IfStatement(Condition.NEXT_IS_ENEMY, Action.INFECT, Action.TURN_LEFT)
)

val lawnMowerLogic = WhileLoop(Condition.TRUE,
    IfStatement(Condition.NEXT_IS_ENEMY, Action.INFECT,
        IfStatement(Condition.NEXT_IS_EMPTY, Action.MOVE, Action.TURN_LEFT)
    )
)


val handcraftedOpponents = listOf(
    BugLogic("Default", defaultLogic),
    BugLogic("AggressiveScanner", aggressiveScannerLogic),
    BugLogic("WallFollower", wallFollowerLogic),
    BugLogic("SpinToWin", spinToWinLogic),
    BugLogic("SpinToWin", spinToWinLogic),
    BugLogic("RandomTurner", randomTurnerLogic),
    BugLogic("DoubleInfector", doubleInfectorLogic),
    BugLogic("ApexSwarm", apexSwarmLogic),
    BugLogic("LawnMower", lawnMowerLogic)
)

fun loadTop50FromFile(): List<BugLogic> {
    val file = File("top_bugs.bl")
    if (!file.exists()) return emptyList()
    return try {
        val text = file.readText()
        val blocks = "DEFINE[\\s\\S]*?END DEFINE".toRegex().findAll(text).map { it.value }.toList()
        val bugs = blocks.mapNotNull { block ->
            try { parseBL(block) } catch (e: Exception) { null }
        }
        println("Loaded ${bugs.size} bugs from file")
        bugs
    } catch (e: Exception) {
        println("Warning: Failed to parse top_bugs.bl: ${e.message}")
        emptyList()
    }
}

data class GenerationRecord(val generation: Int, val bestBugs: List<BugLogic>) // Store top 3 best bugs from the generation

val generationHistory = mutableListOf<GenerationRecord>()

// Rolling top 50 bugs across all generations, tracked as (bug, fitness)
val rollingTop50 = mutableListOf<Pair<BugLogic, Double>>()

var population = MutableList(GaConfig.POPULATION_SIZE) {
    BugLogic(it.toString(), generateRandomAST(7))
}

fun main() {
    println("Starting Evolutionary Algorithm with population size ${GaConfig.POPULATION_SIZE} and ${GaConfig.GENERATIONS} generations")
    println("Configuration: TOURNAMENT_SIZE=${GaConfig.TOURNAMENT_SIZE}, MUTATION_RATE=${GaConfig.MUTATION_RATE}")

    val savedBugs = loadTop50FromFile()
    for ((i, bug) in savedBugs.take(GaConfig.POPULATION_SIZE - 1).withIndex()) {
        population[i] = bug
    }
    // Also seed mutated variants of the best saved bug
    if (savedBugs.isNotEmpty()) {
        val bestSaved = savedBugs[0]
        val startIdx = savedBugs.size
        for (i in startIdx..minOf(startIdx + 9, GaConfig.POPULATION_SIZE - 1)) {
            population[i] = BugLogic(UUID.randomUUID().toString(),
                mutate(bestSaved.statement,
                    GaConfig.MUTATION_RATE * 2.0 /
                        bestSaved.statement.countNodes().toDouble()))
        }
    }

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
                        bug to evaluateFitness(bug)
                    }
                }.awaitAll()
            }.sortedByDescending { it.second }
        }

        val bestThisGen = fitnessScores.first()
        if (bestEver == null || bestThisGen.second > bestEver.second) {
            bestEver = bestThisGen
        }

        // Update rolling top 50
        rollingTop50.addAll(fitnessScores.take(10))
        val seen = mutableSetOf<String>()
        val deduped = rollingTop50
            .sortedByDescending { it.second }
            .filter { seen.add(it.first.statement.simplify().toBL()) }
        rollingTop50.clear()
        rollingTop50.addAll(deduped.take(50))

        // Record best opponent from this generation
        generationHistory.add(GenerationRecord(gen, listOf(fitnessScores.first().first)))

        val minFitness = fitnessScores.last().second
        val avgFitness = fitnessScores.map { it.second }.average()
        val maxFitness = fitnessScores.first().second
        val uniqueNodes = bestThisGen.first.statement.countNodes()

        // Test best against all handcrafted opponents
        val beatResults = mutableListOf<Pair<String, Boolean>>()
        var timeoutWins = 0
        var timeoutLosses = 0

        val bestLogic = Compiler.compile(bestThisGen.first.statement)

        runBlocking(Dispatchers.Default) {
            async {
                handcraftedOpponents.map { opponent ->
                    val opponentLogic = Compiler.compile(opponent.statement)
                    repeat(125) {
                        val simulation = Simulation(GaConfig.SIDE_LENGTH, bestLogic, opponentLogic)
                        val packed = simulation.playMatch()
                        val winner = Simulation.unpackWinner(packed)
                        val duration = Simulation.unpackTurns(packed)
                        if (duration >= GaConfig.TIMEOUT_THRESHOLD) {
                            if (winner == 1) {
                                timeoutWins++
                            } else {
                                timeoutLosses++
                            }
                        } else {
                            beatResults.add(opponent.name to (winner == 1))
                        }
                    }
                }
            }
        }
        // Summarize per opponent results
        val beatsSummary = beatResults.groupBy { it.first }.mapValues { entry ->
            val wins = entry.value.count { it.second }
            "$wins/${entry.value.size}"
        }.entries.joinToString(", ") { "${it.key}: ${it.value}" }

        val winsCount = beatResults.count { it.second }
        println("Gen $gen: size=${population.size}, " +
                "min/avg/max fitness = ${minFitness.toInt()} / ${avgFitness.toInt()} / ${maxFitness.toInt()}, " +
                "nodes=${uniqueNodes}, wins=${winsCount}/${beatResults.size}, time=${took}ms, " +
                "timeouts=$timeoutWins wins, $timeoutLosses losses. ")
        println("  Matchups: $beatsSummary")

        if (gen % 10 == 0) {
            println("Best Logic:\n${bestThisGen.first.statement.toBL()}\n")
            saveTop50ToFile(rollingTop50.map { it.first })
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
            val mutationRate = ((if (abs(maxFitness - bestEver.second) < 100)
                GaConfig.MUTATION_RATE * 2 else GaConfig.MUTATION_RATE) / parent.statement.countNodes().toDouble())

            val childAST = mutate(child, mutationRate)

            // Only add children that can infect
            if (childAST.getAllNodes().any { node -> node == Action.INFECT }) {
                newPopulation.add(BugLogic(UUID.randomUUID().toString(), childAST.simplify()))
            }
        }
        population = newPopulation
    }

    println("\nFinal best bug (all generations):")
    if (bestEver != null) {
        println(bestEver.first.statement.toBL())
    }
    saveTop50ToFile(rollingTop50.map { it.first })
}

fun evaluateFitness(bug: BugLogic): Double {
    var score = 0.0
    val nodeCount = bug.statement.countNodes()

    // Hard cap: penalize heavily if too large
    if (nodeCount > GaConfig.MAX_NODES) {
        return -100000.0
    }

    val opponents = mutableListOf<BugLogic>()
    // Add handcrafted opponents
    repeat(GaConfig.DEFAULT_OPPONENTS) {
        opponents.addAll(handcraftedOpponents)
    }
    // add the top 3 from past generations to ensure continued pressure to beat the best
    // opponents.addAll(rollingTop50.take(3).map { it.first })


    var totalLosses = 0
    var totalWins = 0
    var totalDecisive = 0
    val bugLogic = Compiler.compile(bug.statement)
    for (opponent in opponents) {
        var timeoutWins = 0
        var timeoutLosses = 0
        var wins = 0
        var losses = 0
        val opponentLogic = Compiler.compile(opponent.statement)
        repeat(GaConfig.MATCHES_PER_OPPONENT) {
            val simulation = Simulation(17, bugLogic, opponentLogic)
            val packed = simulation.playMatch()
            val winner = Simulation.unpackWinner(packed)
            val duration = Simulation.unpackTurns(packed)

            if (duration >= GaConfig.TIMEOUT_THRESHOLD) {
                if (winner == 1) timeoutWins++ else timeoutLosses++
            } else {
                when (winner) {
                    1 -> {
                        wins++
                        val speedFraction = (GaConfig.TIMEOUT_THRESHOLD - duration).toDouble() / GaConfig.TIMEOUT_THRESHOLD
                        score += 50 + speedFraction * 200 // 50 base + up to 200 for speed
                    }
                    2 -> {
                        losses++
                        val speedFraction = (GaConfig.TIMEOUT_THRESHOLD - duration).toDouble() / GaConfig.TIMEOUT_THRESHOLD
                        score -= 50 + speedFraction * 150 // faster losses are worse
                    }
                }
            }

            if (score < -100000.0) {
                return score
            }
        }

        val totalTimeouts = timeoutWins + timeoutLosses
        val decisive = wins + losses
        totalDecisive += decisive

        // Flat timeout penalty — timeouts are always bad
        score -= totalTimeouts * 40.0
        // Slight advantage for timeout wins over losses (shows progress toward winning)
        score += timeoutWins * 15.0
        score -= timeoutLosses * 15.0

        // Score decisive matches
        if (decisive > 0) {
            val winRate = wins.toDouble() / decisive
            score += (winRate * winRate) * 5000.0
            score += winRate * 2500.0 // linear component to reward any wins
            score += wins * 100.0 // small per-win bonus to reward more wins even at same win rate
            if (opponent == spinToWinLogic) {
                score += wins * 250.0 // extra bonus for beating the notoriously tough SpinToWin
                score += (winRate * winRate) * 10000.0 // extra bonus for high win rate against SpinToWin
            }
        }
        if (wins == 0 && decisive > 8) {
            totalLosses++
            score -= 4000.0
            if (totalLosses > opponents.size / 5) {
                return score - 20000.0
            }
        }
        totalWins += wins
    }

    // Overall win rate bonus (based on decisive matches only)
    if (totalDecisive > 0) {
        val winRate = totalWins.toDouble() / totalDecisive
        score += (winRate * winRate) * 50000.0
    }

    // Structural bonus: reward having MOVE (encourages mobility)
    if (bug.statement.getAllNodes().any { it == Action.MOVE }) score += 500.0

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

fun saveTop50ToFile(bugs: List<BugLogic>) {
    File("top_bugs.bl").writeText(bugs.joinToString("\n\n") { BugLogic(it.name, it.statement.simplify()).toBL() })
}