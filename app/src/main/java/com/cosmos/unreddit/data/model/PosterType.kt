package com.cosmos.unreddit.data.model

import androidx.annotation.ColorRes
import com.cosmos.unreddit.R

enum class PosterType(val value: Int, @ColorRes val color: Int) {
    REGULAR(0, R.color.colorPrimary),
    ADMIN(1, R.color.admin_color),
    MODERATOR(2, R.color.moderator_color),
    BOT(3, R.color.bot_color);

    companion object {
        fun toType(type: Int): PosterType = entries.find { it.value == type } ?: REGULAR

        fun fromDistinguished(distinguished: String?): PosterType {
            return when (distinguished) {
                "admin" -> ADMIN
                "moderator" -> MODERATOR
                "bot" -> BOT
                else -> REGULAR
            }
        }
    }
}
