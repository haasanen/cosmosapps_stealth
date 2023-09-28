package com.cosmos.unreddit.util.extension

import android.content.Context
import android.util.TypedValue
import kotlin.math.floor
import kotlin.math.round

fun Context.toPixels(value: Number, unit: Int = TypedValue.COMPLEX_UNIT_DIP): Float {
    return TypedValue.applyDimension(unit, value.toFloat(), resources.displayMetrics)
}

infix fun Int.fitTo(range: IntRange): Int {
    val last = range.last + 1
    return (this - (floor((this / last).toDouble()) * last) + range.first).toInt()
}

@Suppress("MagicNumber")
fun Double.toPercentage(): Int {
    return round(this * 100).toInt()
}
