package me.thecodingduck.bugsworld.world

import me.thecodingduck.bugsworld.GaConfig

enum class Cell { EMPTY, WALL, SPECIES1, SPECIES2; }

data class World(val sideLength: Int) {
    val grid = IntArray(sideLength * sideLength) { Cell.EMPTY.ordinal }
    val bugGrid = arrayOfNulls<Bug>(sideLength * sideLength)
    val bugs = mutableListOf<Bug>()
    val interpreters = arrayOfNulls<BugInterpreter>(100) // 50 per species max
    var interpreterCount = 0
    var species1Count = 0
    var species2Count = 0
}

data class Bug(
    val id: Int,
    var posIndex: Int,
    var dir: Int,
)

object DirectionOffsets {
    val OFFSETS = intArrayOf(-GaConfig.SIDE_LENGTH, 1, GaConfig.SIDE_LENGTH, -1) // N, E, S, W
}