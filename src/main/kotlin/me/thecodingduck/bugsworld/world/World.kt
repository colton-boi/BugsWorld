package me.thecodingduck.bugsworld.world

enum class Cell { EMPTY, WALL, SPECIES1, SPECIES2 }
enum class Direction { NORTH, EAST, SOUTH, WEST;
    val right by lazy { when (this) {
        NORTH -> EAST
        EAST -> SOUTH
        SOUTH -> WEST
        WEST -> NORTH
    } }
    val left by lazy { when (this) {
        NORTH -> WEST
        EAST -> NORTH
        SOUTH -> EAST
        WEST -> SOUTH
    } }
}
data class Point(val x: Int, val y: Int)

data class World(val sideLength: Int) {
    val grid = Array(sideLength) { Array(sideLength) { Cell.EMPTY } }
    val bugGrid = Array(sideLength) { arrayOfNulls<Bug>(sideLength) }
    val bugs = mutableListOf<Bug>()
    val interpreters = arrayOfNulls<BugInterpreter>(100) // 50 per species max
    var interpreterCount = 0
    var species1Count = 0
    var species2Count = 0
}

data class Bug(
    val id: Int,
    var xPos: Int,
    var yPos: Int,
    var direction: Direction,
    var speciesId: Int
)