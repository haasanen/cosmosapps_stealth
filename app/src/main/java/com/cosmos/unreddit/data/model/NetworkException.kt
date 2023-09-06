package com.cosmos.unreddit.data.model

data class NetworkException(val code: Int, override val message: String) : Throwable()
