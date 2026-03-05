package me.thecodingduck.bugsworld.world

enum class Cell { EMPTY, WALL, SPECIES1, SPECIES2 }
enum class Direction { NORTH, EAST, SOUTH, WEST }
data class Point(val x: Int, val y: Int)

data class World(val sideLength: Int) {
    val grid = Array(sideLength) { Array(sideLength) { Cell.EMPTY } }
    val bugGrid = Array(sideLength) { arrayOfNulls<Bug>(sideLength) }
    val bugs = mutableListOf<Bug>()
    val interpreters = arrayOfNulls<BugInterpreter>(20) // 10 per species max
    var interpreterCount = 0
    var species1Count = 0
    var species2Count = 0
}

data class Bug(
    val id: Int,
    var position: Point,
    var direction: Direction,
    var speciesId: Int
)