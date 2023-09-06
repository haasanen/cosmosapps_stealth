package com.cosmos.unreddit.data.model

data class ServiceQuery(
    val service: Service,

    val communities: List<String>
)
