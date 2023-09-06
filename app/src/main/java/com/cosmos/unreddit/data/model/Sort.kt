package com.cosmos.unreddit.data.model

@Deprecated("Legacy entity")
enum class Sort(val type: String) {
    HOT("hot"), NEW("new"), TOP("top"), RISING("rising"),
    CONTROVERSIAL("controversial"), RELEVANCE("relevance"), COMMENTS("comments"),
    BEST("confidence"), OLD("old"), QA("qa");

    companion object {
        fun fromName(value: String?, default: Sort = BEST): Sort {
            return values().find { it.type == value } ?: default
        }
    }
}
