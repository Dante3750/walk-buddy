package com.walkbuddy.domain

/**
 * Minimal pure-Kotlin QR Code encoder: byte mode, error-correction level L, versions 1-5 (up to 106 bytes),
 * a single Reed-Solomon block. Enough for a join link like `walkbuddy://join/ABC234?s=wss%3A%2F%2Fhost`.
 * Implemented from ISO/IEC 18004; format-info and Reed-Solomon steps are covered by unit tests with published vectors.
 */
class QrCode(val version: Int, val size: Int, private val modules: BooleanArray, val mask: Int) {
    fun isDark(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && modules[y * size + x]
}

object ReedSolomon {
    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    /** Generator polynomial coefficients (highest degree first, leading 1 omitted) for [degree] EC codewords. */
    fun generator(degree: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(poly.size + 1)
            for (j in poly.indices) {
                next[j] = next[j] xor poly[j]
                next[j + 1] = next[j + 1] xor mul(poly[j], EXP[i])
            }
            poly = next
        }
        return poly.copyOfRange(1, poly.size)
    }

    fun encode(data: IntArray, ecLen: Int): IntArray {
        val gen = generator(ecLen)
        val rem = IntArray(ecLen)
        for (b in data) {
            val factor = b xor rem[0]
            System.arraycopy(rem, 1, rem, 0, ecLen - 1)
            rem[ecLen - 1] = 0
            for (i in 0 until ecLen) rem[i] = rem[i] xor mul(gen[i], factor)
        }
        return rem
    }
}

object QrEncoder {
    private class Spec(val version: Int, val dataCw: Int, val ecCw: Int)

    // Level L, single block: (data codewords, EC codewords) per ISO 18004 table 9.
    private val SPECS = listOf(Spec(1, 19, 7), Spec(2, 34, 10), Spec(3, 55, 15), Spec(4, 80, 20), Spec(5, 108, 26))
    const val MAX_BYTES = 106

    /** 15-bit format information for EC level L and the given mask, already XOR-masked with 0x5412. */
    fun formatBits(mask: Int): Int {
        val data = (1 shl 3) or mask // L = 01
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        return ((data shl 10) or rem) xor 0x5412
    }

    fun encode(text: String): QrCode {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val spec = SPECS.firstOrNull { (it.dataCw * 8 - 12) / 8 >= bytes.size }
            ?: throw IllegalArgumentException("Text too long for this encoder (max $MAX_BYTES bytes)")
        val codewords = buildCodewords(bytes, spec)
        val size = 17 + 4 * spec.version
        var best: QrCode? = null
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0..7) {
            val q = Builder(spec.version, size).build(codewords, mask)
            val p = penalty(q)
            if (p < bestPenalty) { bestPenalty = p; best = q }
        }
        return best!!
    }

    private fun buildCodewords(bytes: ByteArray, spec: Spec): IntArray {
        val bits = ArrayList<Int>()
        fun put(value: Int, len: Int) { for (i in len - 1 downTo 0) bits.add((value ushr i) and 1) }
        put(0b0100, 4)
        put(bytes.size, 8)
        for (b in bytes) put(b.toInt() and 0xFF, 8)
        val cap = spec.dataCw * 8
        put(0, minOf(4, cap - bits.size))
        while (bits.size % 8 != 0) bits.add(0)
        val data = ArrayList<Int>()
        for (i in bits.indices step 8) { var v = 0; for (j in 0 until 8) v = (v shl 1) or bits[i + j]; data.add(v) }
        var pad = 0xEC
        while (data.size < spec.dataCw) { data.add(pad); pad = if (pad == 0xEC) 0x11 else 0xEC }
        val d = data.toIntArray()
        return d + ReedSolomon.encode(d, spec.ecCw)
    }

    private class Builder(val version: Int, val size: Int) {
        val modules = BooleanArray(size * size)
        val isFn = BooleanArray(size * size)

        fun setFn(x: Int, y: Int, dark: Boolean) { if (x in 0 until size && y in 0 until size) { modules[y * size + x] = dark; isFn[y * size + x] = true } }

        fun build(codewords: IntArray, mask: Int): QrCode {
            for (i in 0 until size) { setFn(6, i, i % 2 == 0); setFn(i, 6, i % 2 == 0) }
            finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
            if (version >= 2) alignment(size - 7, size - 7)
            drawFormat(mask)
            place(codewords)
            applyMask(mask)
            drawFormat(mask)
            return QrCode(version, size, modules, mask)
        }

        fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val dist = maxOf(Math.abs(dx), Math.abs(dy))
                setFn(cx + dx, cy + dy, dist != 2 && dist != 4)
            }
        }

        fun alignment(cx: Int, cy: Int) {
            for (dy in -2..2) for (dx in -2..2) setFn(cx + dx, cy + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
        }

        fun drawFormat(mask: Int) {
            val bits = formatBits(mask)
            fun b(i: Int) = ((bits ushr i) and 1) != 0
            for (i in 0..5) setFn(8, i, b(i))
            setFn(8, 7, b(6)); setFn(8, 8, b(7)); setFn(7, 8, b(8))
            for (i in 9..14) setFn(14 - i, 8, b(i))
            for (i in 0..7) setFn(size - 1 - i, 8, b(i))
            for (i in 8..14) setFn(8, size - 15 + i, b(i))
            setFn(8, size - 8, true)
        }

        fun place(cw: IntArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFn[y * size + x] && i < cw.size * 8) {
                        modules[y * size + x] = ((cw[i ushr 3] ushr (7 - (i and 7))) and 1) != 0
                        i++
                    }
                }
                right -= 2
            }
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                if (isFn[y * size + x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert) modules[y * size + x] = !modules[y * size + x]
            }
        }
    }

    /** Standard four-rule mask penalty (ISO 18004 section 7.8.3). Public for testing. */
    fun penalty(q: QrCode): Int {
        val n = q.size
        var total = 0
        // Rule 1: runs of 5+ same-colour modules in rows and columns.
        for (vertical in listOf(false, true)) for (a in 0 until n) {
            var run = 1
            for (b in 1 until n) {
                val cur = if (vertical) q.isDark(a, b) else q.isDark(b, a)
                val prev = if (vertical) q.isDark(a, b - 1) else q.isDark(b - 1, a)
                if (cur == prev) { run++; if (run == 5) total += 3 else if (run > 5) total += 1 } else run = 1
            }
        }
        // Rule 2: 2x2 blocks.
        for (y in 0 until n - 1) for (x in 0 until n - 1) {
            val c = q.isDark(x, y)
            if (c == q.isDark(x + 1, y) && c == q.isDark(x, y + 1) && c == q.isDark(x + 1, y + 1)) total += 3
        }
        // Rule 3: finder-like 1:1:3:1:1 patterns with 4 light modules on either side.
        val p1 = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
        val p2 = p1.reversedArray()
        for (vertical in listOf(false, true)) for (a in 0 until n) for (b in 0..n - 11) {
            var m1 = true; var m2 = true
            for (k in 0 until 11) {
                val d = if (vertical) q.isDark(a, b + k) else q.isDark(b + k, a)
                if (d != p1[k]) m1 = false
                if (d != p2[k]) m2 = false
            }
            if (m1) total += 40
            if (m2) total += 40
        }
        // Rule 4: dark/light balance.
        var dark = 0
        for (y in 0 until n) for (x in 0 until n) if (q.isDark(x, y)) dark++
        val k = Math.abs(dark * 20 - n * n * 10) / (n * n)
        total += k * 10
        return total
    }
}
