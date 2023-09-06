package com.cosmos.unreddit.data.model

@Deprecated("Legacy entity")
enum class TimeSorting(val type: String) {
    HOUR("hour"), DAY("day"), WEEK("week"), MONTH("month"),
    YEAR("year"), ALL("all");

    companion object {
        fun fromName(value: String?): TimeSorting? {
            return values().find { it.type == value }
        }
    }
}
