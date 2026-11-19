package com.hans.aura.ui.player

internal fun upcomingIndices(count: Int, current: Int, next: (Int) -> Int): List<Int> {
    if (current !in 0 until count) return emptyList()
    val visited = mutableSetOf(current)
    val result = mutableListOf<Int>()
    var index = next(current)
    while (index in 0 until count && visited.add(index)) {
        result.add(index)
        index = next(index)
    }
    return result
}
