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
    override fun toBL() = statements.joinToString("\n") { it.toBL().prependIndent(" ") }
}

data class IfStatement(val condition: Condition, val thenBlock: Statement, val elseBlock: Statement? = null) : Statement {
    override fun toBL() = "IF ${condition.code} THEN\n${thenBlock.toBL()}\n" +
            (if (elseBlock != null) "ELSE\n${elseBlock.toBL()}\n" else "") + "END IF"
}

data class WhileLoop(val condition: Condition, val body: Statement) : Statement {
    override fun toBL() = "WHILE ${condition.code} DO\n${body.toBL()}\nEND WHILE"
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
            val elseBlock = if (Math.random() < 0.3) {
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

    lateinit var target1: Statement
    lateinit var donor: Statement
    var found = false

    // First try: strict type matching
    for (attempt in 0..20) {
        val candidate = nodes1.random()
        val matches = nodes2.filter { it::class == candidate::class }
        if (matches.isNotEmpty()) {
            target1 = candidate
            donor = matches.random()
            found = true
            break
        }
    }

    // Fallback: allow Block↔IfStatement or Block↔WhileLoop
    if (!found) {
        for (attempt in 0..20) {
            val candidate = nodes1.random()
            val compatibleTypes = when (candidate) {
                is Block -> listOf(IfStatement::class, WhileLoop::class)
                is IfStatement -> listOf(Block::class)
                is WhileLoop -> listOf(Block::class)
                else -> emptyList()
            }
            val matches = nodes2.filter { it::class in compatibleTypes }
            if (matches.isNotEmpty()) {
                target1 = candidate
                donor = matches.random()
                found = true
                break
            }
        }
    }

    // If still not found, just use a random action as fallback
    if (!found) {
        target1 = nodes1.random()
        donor = Action.entries.random()
    }

    return ast1.replaceNode(target1, donor)
}

fun mutate(ast: Statement, mutationRate: Double): Statement {
    val roll = Math.random()

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
                val newCondition = if (Math.random() < 0.5) {
                    toggleConditionNegation(ast.condition)
                } else {
                    Condition.entries.random()
                }
                IfStatement(newCondition, mutate(ast.thenBlock, mutationRate),
                    if (ast.elseBlock != null) mutate(ast.elseBlock, mutationRate) else null)
            }
            is WhileLoop -> {
                val newCondition = if (Math.random() < 0.5) {
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
        val elseBody = generateRandomAST(2)
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

fun Statement.replaceNode(target: Statement, replacement: Statement): Statement {
    if (this === target) return replacement

    return when (this) {
        is IfStatement -> IfStatement(condition,
            thenBlock.replaceNode(target, replacement),
            elseBlock?.replaceNode(target, replacement))
        is WhileLoop -> WhileLoop(condition,
            body.replaceNode(target, replacement))
        is Block -> Block(statements.map { it.replaceNode(target, replacement) }.toMutableList())
        else -> this
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


val simpleMap = mutableMapOf<Statement, Statement>()
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