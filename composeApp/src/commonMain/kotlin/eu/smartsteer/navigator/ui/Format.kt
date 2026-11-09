package eu.smartsteer.navigator.ui

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round

/** Liczba z przecinkiem dziesiętnym i prawdziwym minusem: -1.15 -> "−1,2". */
fun Double.pl(decimals: Int = 1, plusSign: Boolean = false): String {
    val m = 10.0.pow(decimals)
    val rounded = round(this * m) / m
    val a = abs(rounded)
    var whole = floor(a).toLong()
    var frac = round((a - whole) * m).toLong()
    if (frac >= m.toLong()) { whole++; frac -= m.toLong() }
    val body = if (decimals > 0) "$whole," + frac.toString().padStart(decimals, '0') else "$whole"
    val isZero = whole == 0L && frac == 0L
    return when {
        rounded < 0 && !isZero -> "−$body"
        plusSign && !isZero -> "+$body"
        else -> body
    }
}
