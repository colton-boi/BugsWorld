package me.thecodingduck.bugsworld.world

enum class Cell { EMPTY, WALL, SPECIES1, SPECIES2 }
enum class Direction { NORTH, EAST, SOUTH, WEST }
data class Point(val x: Int, val y: Int)

data class World(val sideLength: Int) {
    val grid = Array(sideLength) { Array(sideLength) { Cell.EMPTY } }
    val bugs = mutableListOf<Bug>()
}

data class Bug(
    val id: Int,
    var position: Point,
    var direction: Direction,
    var speciesId: Int
)