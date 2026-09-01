package com.cosmos.unreddit.util.extension

fun <T> Iterable<Iterable<T>>.interlace(): List<T> {
    val result = ArrayList<T>()
    val lists = map { it.toList() }.filter { it.isNotEmpty() }
    if (lists.isEmpty()) return result

    val max = lists.maxOf { it.size }

    for (i in 0 until max) {
        lists
            .mapNotNull { it.getOrNull(i) }
            .let { result.addAll(it) }
    }

    return result
}
