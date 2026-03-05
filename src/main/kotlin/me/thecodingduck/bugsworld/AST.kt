package me.thecodingduck.bugsworld

sealed interface Statement {
    fun toBL(): String // Converts the tree back to text for the OSU compiler
}

enum class Action(val code: String) : Statement {
    MOVE("move"),
    TURN_LEFT("turnleft"),
    TURN_RIGHT("turnright"),
    INFECT("infect"); // Skip is not included because it's a worse version of INFECT
    override fun toBL() = code
}

enum class Condition(val code: String) {
    NEXT_IS_EMPTY("next-is-empty"), NEXT_IS_WALL("next-is-wall"),
    NEXT_IS_ENEMY("next-is-enemy"), NEXT_IS_FRIEND("next-is-friend"),
    NEXT_IS_NOT_EMPTY("next-is-not-empty"), NEXT_IS_NOT_WALL("next-is-not-wall"),
    NEXT_IS_NOT_ENEMY("next-is-not-enemy"), NEXT_IS_NOT_FRIEND("next-is-not-friend"),
    TRUE("true"), RANDOM("random");
}

data class Block(val statements: MutableList<Statement>) : Statement {
    override fun toBL() = statements.joinToString("\n") { it.toBL() }
}

data class IfStatement(val condition: Condition, val thenBlock: Statement, val elseBlock: Statement? = null) : Statement {
    override fun toBL() = "IF ${condition.code} THEN\n${thenBlock.toBL().prependIndent("  ")}\n" +
            (if (elseBlock != null) "ELSE\n${elseBlock.toBL().prependIndent("  ")}\n" else "") + "END IF"
}

data class WhileLoop(val condition: Condition, val body: Statement) : Statement {
    override fun toBL() = "WHILE ${condition.code} DO\n${body.toBL().prependIndent("  ")}\nEND WHILE"
}

data class BugLogic(val name: String, var statement: Statement) {
    fun toBL() = "DEFINE $name\n${statement.toBL()}\nEND DEFINE"
}

fun generateRandomAST(depth: Int): Statement {
    if (depth == 0) return Action.entries.random()
    return when ((0..12).random()) {
        0, 1, 2 -> Action.entries.random()
        3, 4, 5, 6 -> {
            var random = generateRandomAST(depth - 1)
            if (random !is Block) random = Block(mutableListOf(random))
            val elseBlock = if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < 0.3) {
                var elseBody = generateRandomAST(depth - 1)
                if (elseBody !is Block) elseBody = Block(mutableListOf(elseBody))
                elseBody
            } else null
            IfStatement(Condition.entries.random(), random, elseBlock)
        }
        7, 8 -> {
            var random = generateRandomAST(depth - 1)
            if (random !is Block) random = Block(mutableListOf(random))
            WhileLoop(Condition.entries.random(), random)
        }
        else -> Block(MutableList((2..4).random()) { generateRandomAST(depth - 1) })
    }
}

fun crossOver(ast1: Statement, ast2: Statement): Statement {
    val nodes1 = ast1.getAllNodes()
    val nodes2 = ast2.getAllNodes()
    val rng = java.util.concurrent.ThreadLocalRandom.current()

    // Build indexed list of (index, node) for ast1 and group ast2 nodes by type
    val indexed1 = nodes1.mapIndexed { i, n -> i to n }
    val byType = nodes2.groupBy { it::class }

    var targetIndex = -1
    var donor: Statement? = null

    // First try: strict type matching
    for (attempt in 0..20) {
        val (idx, candidate) = indexed1[rng.nextInt(indexed1.size)]
        val matches = byType[candidate::class]
        if (!matches.isNullOrEmpty()) {
            targetIndex = idx
            donor = matches[rng.nextInt(matches.size)]
            break
        }
    }

    // Fallback: allow Block↔IfStatement or Block↔WhileLoop
    if (donor == null) {
        val blockCompat = (byType[IfStatement::class].orEmpty() + byType[WhileLoop::class].orEmpty())
        val structCompat = byType[Block::class].orEmpty()

        for (attempt in 0..20) {
            val (idx, candidate) = indexed1[rng.nextInt(indexed1.size)]
            val matches = when (candidate) {
                is Block -> blockCompat
                is IfStatement, is WhileLoop -> structCompat
                else -> emptyList()
            }
            if (matches.isNotEmpty()) {
                targetIndex = idx
                donor = matches[rng.nextInt(matches.size)]
                break
            }
        }
    }

    // If still not found, just use a random action as fallback
    if (donor == null) {
        targetIndex = rng.nextInt(nodes1.size)
        donor = Action.entries.random()
    }

    return ast1.replaceNodeAtIndex(targetIndex, donor).first
}

fun mutate(ast: Statement, mutationRate: Double): Statement {
    val roll = java.util.concurrent.ThreadLocalRandom.current().nextDouble()

    // 1. Full replacement with random subtree
    if (roll < mutationRate * 0.2) return generateRandomAST(3)

    // 2. Hoist child
    if (roll < mutationRate * 0.3 && canBeHoisted(ast)) {
        return hoistChild(ast)
    }

    // 3. Mutate condition (if this is an IfStatement or WhileLoop)
    if (roll < mutationRate * 0.5) {
        return when (ast) {
            is IfStatement -> {
                val newCondition = if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < 0.5) {
                    toggleConditionNegation(ast.condition)
                } else {
                    Condition.entries.random()
                }
                IfStatement(newCondition, mutate(ast.thenBlock, mutationRate),
                    if (ast.elseBlock != null) mutate(ast.elseBlock, mutationRate) else null)
            }
            is WhileLoop -> {
                val newCondition = if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < 0.5) {
                    toggleConditionNegation(ast.condition)
                } else {
                    Condition.entries.random()
                }
                WhileLoop(newCondition, mutate(ast.body, mutationRate))
            }
            else -> mutateDefault(ast, mutationRate)
        }
    }

    // 4. Mutate action (if this is an Action)
    if (roll < mutationRate * 0.7 && ast is Action) {
        return Action.entries.random()
    }

    // 5. Add else block to IfStatement that lacks one
    if (roll < mutationRate * 0.85 && ast is IfStatement && ast.elseBlock == null) {
        val elseBody = generateRandomAST(3)
        return IfStatement(ast.condition, mutate(ast.thenBlock, mutationRate), elseBody)
    }

    return mutateDefault(ast, mutationRate)
}

fun mutateDefault(ast: Statement, mutationRate: Double): Statement {
    return when (ast) {
        is IfStatement -> IfStatement(ast.condition, mutate(ast.thenBlock, mutationRate),
            if (ast.elseBlock != null) mutate(ast.elseBlock, mutationRate) else null)
        is WhileLoop -> WhileLoop(ast.condition, mutate(ast.body, mutationRate))
        is Block -> Block(MutableList(ast.statements.size) { i -> mutate(ast.statements[i], mutationRate) })
        else -> ast
    }
}

fun toggleConditionNegation(condition: Condition): Condition {
    return when (condition) {
        Condition.NEXT_IS_EMPTY -> Condition.NEXT_IS_NOT_EMPTY
        Condition.NEXT_IS_NOT_EMPTY -> Condition.NEXT_IS_EMPTY
        Condition.NEXT_IS_WALL -> Condition.NEXT_IS_NOT_WALL
        Condition.NEXT_IS_NOT_WALL -> Condition.NEXT_IS_WALL
        Condition.NEXT_IS_ENEMY -> Condition.NEXT_IS_NOT_ENEMY
        Condition.NEXT_IS_NOT_ENEMY -> Condition.NEXT_IS_ENEMY
        Condition.NEXT_IS_FRIEND -> Condition.NEXT_IS_NOT_FRIEND
        Condition.NEXT_IS_NOT_FRIEND -> Condition.NEXT_IS_FRIEND
        else -> condition // TRUE and RANDOM have no negation
    }
}

fun canBeHoisted(ast: Statement): Boolean = when(ast) {
    is IfStatement -> true
    is WhileLoop -> true
    is Block -> ast.statements.isNotEmpty()
    else -> false
}

fun hoistChild(ast: Statement): Statement = when(ast) {
    is IfStatement -> ast.thenBlock.getAllNodes().randomOrNull() ?: Action.INFECT
    is WhileLoop -> ast.body.getAllNodes().randomOrNull() ?: Action.INFECT
    is Block -> ast.statements.random()
    else -> Action.INFECT
}

fun Statement.getAllNodes(): List<Statement> {
    val list = mutableListOf<Statement>(this)
    when (this) {
        is IfStatement -> {
            list.addAll(thenBlock.getAllNodes())
            elseBlock?.let { list.addAll(it.getAllNodes()) }
        }
        is WhileLoop -> list.addAll(body.getAllNodes())
        is Block -> statements.forEach { list.addAll(it.getAllNodes()) }
        is Action -> { /* Base case */ }
    }
    return list
}

fun Statement.replaceNodeAtIndex(targetIndex: Int, replacement: Statement): Pair<Statement, Int> {
    if (targetIndex == 0) return replacement to -1

    var remaining = targetIndex

    return when (this) {
        is Action -> this to remaining
        is IfStatement -> {
            remaining--
            val (newThen, r1) = thenBlock.replaceNodeAtIndex(remaining, replacement)
            if (r1 < 0) return IfStatement(condition, newThen, elseBlock) to -1
            remaining = r1
            if (elseBlock != null) {
                val (newElse, r2) = elseBlock.replaceNodeAtIndex(remaining, replacement)
                if (r2 < 0) return IfStatement(condition, newThen, newElse) to -1
                remaining = r2
            }
            IfStatement(condition, newThen, elseBlock) to remaining
        }
        is WhileLoop -> {
            remaining--
            val (newBody, r) = body.replaceNodeAtIndex(remaining, replacement)
            if (r < 0) return WhileLoop(condition, newBody) to -1
            WhileLoop(condition, newBody) to r
        }
        is Block -> {
            remaining--
            val newStatements = statements.toMutableList()
            for (i in newStatements.indices) {
                val (newChild, r) = newStatements[i].replaceNodeAtIndex(remaining, replacement)
                if (r < 0) {
                    newStatements[i] = newChild
                    return Block(newStatements) to -1
                }
                remaining = r
            }
            Block(newStatements) to remaining
        }
    }
}

fun Statement.countNodes(): Int {
    return when (this) {
        is Action -> 1
        is IfStatement -> 1 + thenBlock.countNodes() + (elseBlock?.countNodes() ?: 0)
        is WhileLoop -> 1 + body.countNodes()
        is Block -> 1 + statements.sumOf { it.countNodes() }
    }
}


fun parseBL(text: String): BugLogic {
    val tokens = text.trim().split("\\s+".toRegex()).toMutableList()
    fun expect(expected: String) {
        val tok = tokens.removeFirst()
        require(tok == expected) { "Expected '$expected' but got '$tok'" }
    }

    expect("DEFINE")
    val name = tokens.removeFirst()
    val body = parseStatement(tokens)
    expect("END")
    expect("DEFINE")
    return BugLogic(name, body)
}

private fun parseStatement(tokens: MutableList<String>): Statement {
    val statements = mutableListOf<Statement>()
    while (tokens.isNotEmpty()) {
        when (tokens.first()) {
            "END", "ELSE" -> break
            "IF" -> statements.add(parseIf(tokens))
            "WHILE" -> statements.add(parseWhile(tokens))
            else -> statements.add(parseAction(tokens))
        }
    }
    return if (statements.size == 1) statements[0] else Block(statements)
}

private fun parseIf(tokens: MutableList<String>): IfStatement {
    tokens.removeFirst() // IF
    val condition = parseCondition(tokens)
    val tok = tokens.removeFirst()
    require(tok == "THEN") { "Expected 'THEN' but got '$tok'" }
    val thenBlock = parseStatement(tokens)
    val elseBlock = if (tokens.isNotEmpty() && tokens.first() == "ELSE") {
        tokens.removeFirst() // ELSE
        parseStatement(tokens)
    } else null
    tokens.removeFirst() // END
    tokens.removeFirst() // IF
    return IfStatement(condition, thenBlock, elseBlock)
}

private fun parseWhile(tokens: MutableList<String>): WhileLoop {
    tokens.removeFirst() // WHILE
    val condition = parseCondition(tokens)
    val tok = tokens.removeFirst()
    require(tok == "DO") { "Expected 'DO' but got '$tok'" }
    val body = parseStatement(tokens)
    tokens.removeFirst() // END
    tokens.removeFirst() // WHILE
    return WhileLoop(condition, body)
}

private fun parseCondition(tokens: MutableList<String>): Condition {
    val code = tokens.removeFirst()
    return Condition.entries.first { it.code == code }
}

private fun parseAction(tokens: MutableList<String>): Action {
    val code = tokens.removeFirst()
    return Action.entries.first { it.code == code }
}

val simpleMap = java.util.concurrent.ConcurrentHashMap<Statement, Statement>()
fun Statement.simplify(): Statement {
    val temp = simpleMap[this]
    if (temp != null) return temp

    val simple = when (this) {
        is Action -> this
        is IfStatement -> when (condition) {
            Condition.TRUE -> thenBlock.simplify()
            else -> {
                val simpleThen = thenBlock.simplify()
                val simpleElse = elseBlock?.simplify()
                IfStatement(condition, simpleThen, simpleElse)
            }
        }
        is WhileLoop -> WhileLoop(condition, body.simplify())
        is Block -> {
            val simplified = statements.map { it.simplify() }
            if (simplified.size == 1) simplified[0]
            else Block(simplified.toMutableList())
        }
    }
    simpleMap[this] = simple
    return simple
}