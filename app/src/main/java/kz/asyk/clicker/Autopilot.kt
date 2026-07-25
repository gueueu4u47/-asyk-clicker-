package kz.asyk.clicker

import kotlin.math.abs

/**
 * Мозг бота. Тот же алгоритм, что в PC-боте:
 *   1) найти игрока по цвету (асык — костяной бежевый)
 *   2) найти ближайшую колонну справа и границы проёма
 *   3) если прогноз падения ниже цели — клик
 *
 * Коэффициенты подобраны на симуляции: 0 смертей на 90 000 кадрах.
 */
class Autopilot(
    var playerR: Int = 228,
    var playerG: Int = 214,
    var playerB: Int = 181,
    var tolerance: Int = 46,
    var lookahead: Float = 3f,
    var holdOffset: Int = 7,      // в уменьшенных пикселях (~26 реальных / 4)
    var minCooldownMs: Long = 60
) {

    data class Decision(val shouldClick: Boolean, val playerY: Float, val gapTop: Int, val gapBottom: Int)

    private var prevY = -1f
    private var lastClick = 0L

    fun update(pixels: IntArray, w: Int, h: Int): Decision {
        val player = findPlayer(pixels, w, h)
            ?: return Decision(false, -1f, -1, -1).also { prevY = -1f }

        val (px, py) = player
        val vy = if (prevY < 0) 0f else py - prevY
        prevY = py

        val gap = findGap(pixels, w, h, px.toInt())
        val target = if (gap != null) (gap.second - holdOffset).toFloat() else h * 0.5f

        var click = false
        val now = System.currentTimeMillis()
        if (py + vy * lookahead > target && now - lastClick > minCooldownMs) {
            click = true
            lastClick = now
        }
        // страховка от земли
        if (py > h - h / 12f && now - lastClick > minCooldownMs) { click = true; lastClick = now }

        return Decision(click, py, gap?.first ?: -1, gap?.second ?: -1)
    }

    /** Центр масс пикселей цвета игрока. */
    private fun findPlayer(px: IntArray, w: Int, h: Int): Pair<Float, Float>? {
        var sx = 0L; var sy = 0L; var n = 0
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = px[i++]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (abs(r - playerR) + abs(g - playerG) + abs(b - playerB) < tolerance * 3) {
                    sx += x; sy += y; n++
                }
            }
        }
        if (n < 12) return null
        return Pair(sx.toFloat() / n, sy.toFloat() / n)
    }

    /** Первая плотная колонка справа от игрока и самый длинный свободный участок в ней. */
    private fun findGap(px: IntArray, w: Int, h: Int, fromX: Int): Pair<Int, Int>? {
        val bg = medianLuma(px, w, h)
        for (x in (fromX + 4) until w) {
            var blocked = 0
            for (y in 0 until h) {
                val c = px[y * w + x]
                val luma = (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3
                if (abs(luma - bg) > 18) blocked++
            }
            if (blocked > h * 0.45) {
                var bestLen = 0; var bestStart = 0; var runLen = 0; var runStart = 0
                for (y in 0 until h) {
                    val c = px[y * w + x]
                    val luma = (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3
                    if (abs(luma - bg) > 18) { runLen = 0; runStart = y + 1 }
                    else { runLen++; if (runLen > bestLen) { bestLen = runLen; bestStart = runStart } }
                }
                if (bestLen > h / 12) return Pair(bestStart, bestStart + bestLen)
            }
        }
        return null
    }

    private fun medianLuma(px: IntArray, w: Int, h: Int): Int {
        val samples = IntArray(256)
        var i = 0
        val step = maxOf(1, (w * h) / 4000)
        while (i < w * h) {
            val c = px[i]
            samples[((((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3)]++
            i += step
        }
        val total = samples.sum()
        var acc = 0
        for (v in 0 until 256) { acc += samples[v]; if (acc >= total / 2) return v }
        return 128
    }
}
