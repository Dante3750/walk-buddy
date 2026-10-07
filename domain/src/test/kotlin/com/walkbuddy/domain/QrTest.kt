package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrTest {
    @Test fun reedSolomonMatchesPublishedVector() {
        // ISO 18004 / Thonky "HELLO WORLD" 1-M example: 16 data codewords, 10 EC codewords.
        val data = intArrayOf(32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17)
        assertEquals(listOf(196, 35, 39, 119, 235, 215, 231, 226, 93, 23), ReedSolomon.encode(data, 10).toList())
    }

    @Test fun generatorPolynomialDegree7() {
        // Known generator for 7 EC codewords: alpha exponents 0,87,229,146,149,238,102,21 -> coefficients below.
        assertEquals(listOf(127, 122, 154, 164, 11, 68, 117), ReedSolomon.generator(7).toList())
    }

    @Test fun formatInformationMatchesSpec() {
        assertEquals(0b111011111000100, QrEncoder.formatBits(0)) // L, mask 0
        assertEquals(0b111001011110011, QrEncoder.formatBits(1)) // L, mask 1
        assertEquals(0b110110001000001, QrEncoder.formatBits(6)) // L, mask 6
        assertEquals(0b110100101110110, QrEncoder.formatBits(7)) // L, mask 7
    }

    @Test fun sizesFollowVersions() {
        assertEquals(21, QrEncoder.encode("A").size)
        assertEquals(25, QrEncoder.encode("walkbuddy://join/ABC234").size)
        assertEquals(37, QrEncoder.encode("y".repeat(100)).size)
        assertEquals(5, QrEncoder.encode("y".repeat(100)).version)
    }

    @Test fun finderTimingAndDarkModulePatterns() {
        val q = QrEncoder.encode("walkbuddy://join/ABC234")
        val n = q.size
        for ((ox, oy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
            for (i in 0..6) {
                assertTrue(q.isDark(ox + i, oy)); assertTrue(q.isDark(ox + i, oy + 6)); assertTrue(q.isDark(ox, oy + i)); assertTrue(q.isDark(ox + 6, oy + i))
            }
            assertFalse(q.isDark(ox + 1, oy + 1)); assertTrue(q.isDark(ox + 3, oy + 3))
        }
        for (i in 8 until n - 8) assertEquals(i % 2 == 0, q.isDark(i, 6))
        for (i in 8 until n - 8) assertEquals(i % 2 == 0, q.isDark(6, i))
        assertTrue("dark module", q.isDark(8, n - 8))
        assertTrue("alignment centre", q.isDark(n - 7, n - 7))
        assertFalse(q.isDark(n - 6, n - 7))
    }

    @Test fun formatBitsAreWrittenTwice() {
        val q = QrEncoder.encode("hello")
        val bits = QrEncoder.formatBits(q.mask)
        for (i in 0..7) assertEquals(((bits ushr i) and 1) != 0, q.isDark(q.size - 1 - i, 8))
        for (i in 0..5) assertEquals(((bits ushr i) and 1) != 0, q.isDark(8, i))
    }

    @Test fun deterministicAndContentSensitive() {
        fun dump(t: String): String { val q = QrEncoder.encode(t); return (0 until q.size).joinToString("") { y -> (0 until q.size).joinToString("") { x -> if (q.isDark(x, y)) "1" else "0" } } }
        assertEquals(dump("walkbuddy://join/ABC234"), dump("walkbuddy://join/ABC234"))
        assertNotEquals(dump("walkbuddy://join/ABC234"), dump("walkbuddy://join/ABC235"))
    }

    @Test fun maskIsChosenByPenaltyAndRoughlyBalanced() {
        val q = QrEncoder.encode("walkbuddy://join/K7M2QX?s=wss%3A%2F%2Fsignal.example.org%2Fws")
        var dark = 0
        for (y in 0 until q.size) for (x in 0 until q.size) if (q.isDark(x, y)) dark++
        val ratio = dark.toDouble() / (q.size * q.size)
        assertTrue("dark ratio $ratio", ratio in 0.35..0.65)
        assertTrue(QrEncoder.penalty(q) > 0)
    }

    @Test fun tooLongTextIsRejectedNotTruncated() {
        var failed = false
        try { QrEncoder.encode("z".repeat(QrEncoder.MAX_BYTES + 1)) } catch (e: IllegalArgumentException) { failed = true }
        assertTrue(failed)
        QrEncoder.encode("z".repeat(QrEncoder.MAX_BYTES)) // boundary fits
    }

    @Test fun utf8Works() = assertEquals(25, QrEncoder.encode("नमस्ते").size)
}
