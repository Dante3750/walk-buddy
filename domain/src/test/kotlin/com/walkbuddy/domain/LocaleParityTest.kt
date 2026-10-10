package com.walkbuddy.domain

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every translatable resource name in values/ must also exist in values-hi/ (strings and plurals). */
class LocaleParityTest {
    private fun resDir(): File? {
        var d: File? = File("").absoluteFile
        repeat(4) {
            val r = File(d, "app/src/main/res")
            if (r.isDirectory) return r
            d = d?.parentFile
        }
        return null
    }

    private val nameRe = Regex("""<(string|plurals|string-array)\s+name="([^"]+)"([^>]*)>""")

    private fun names(dir: File): Set<String> =
        dir.listFiles { f -> f.name.endsWith(".xml") }.orEmpty().flatMap { f ->
            nameRe.findAll(f.readText()).filter { !it.groupValues[3].contains("translatable=\"false\"") }
                .map { it.groupValues[1] + ":" + it.groupValues[2] }.toList()
        }.toSet()

    @Test
    fun hindiHasEveryDefaultString() {
        val res = resDir() ?: return
        val en = names(File(res, "values"))
        val hi = names(File(res, "values-hi"))
        val missing = en - hi
        assertTrue("Missing in values-hi: $missing", missing.isEmpty())
    }

    @Test
    fun hindiHasNoOrphans() {
        val res = resDir() ?: return
        val orphans = names(File(res, "values-hi")) - names(File(res, "values"))
        assertTrue("Only in values-hi: $orphans", orphans.isEmpty())
    }
}
