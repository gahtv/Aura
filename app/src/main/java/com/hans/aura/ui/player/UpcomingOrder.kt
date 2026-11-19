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

internal fun queueIndices(
    count: Int,
    current: Int,
    previous: (Int) -> Int,
    next: (Int) -> Int
): List<Int> {
    if (current !in 0 until count) return emptyList()
    val history = upcomingIndices(count, current, previous).asReversed()
    val upcoming = upcomingIndices(count, current, next)
    return (history + current + upcoming).distinct()
}
