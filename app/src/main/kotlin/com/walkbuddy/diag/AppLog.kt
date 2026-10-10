package com.walkbuddy.diag

import android.content.Context
import com.walkbuddy.BuildConfig
import com.walkbuddy.domain.LogLevel
import com.walkbuddy.domain.RingLog
import java.io.File
import java.util.concurrent.Executors

/**
 * A tiny local diagnostics log: a size-capped ring buffer that is redacted on the way in (codes, links, places, ids, names) and saved to one
 * app-private file. Nothing is ever uploaded; the only way out is the "Export diagnostics" button, which hands a text file to the share sheet.
 */
object AppLog {
    private val ring = RingLog()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "wb-diag").apply { isDaemon = true } }
    @Volatile private var file: File? = null
    @Volatile private var lastWrite = 0L

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "diag.log")
        file = f
        io.execute { runCatching { if (f.exists()) ring.load(f.readText()) } }
    }

    fun d(tag: String, msg: String) = add(LogLevel.Debug, tag, msg)
    fun i(tag: String, msg: String) = add(LogLevel.Info, tag, msg)
    fun w(tag: String, msg: String) = add(LogLevel.Warn, tag, msg)
    fun e(tag: String, msg: String) = add(LogLevel.Error, tag, msg)

    private fun add(level: LogLevel, tag: String, msg: String) {
        ring.add(System.currentTimeMillis(), level, tag, msg)
        val now = System.currentTimeMillis()
        if (level >= LogLevel.Warn || now - lastWrite > 15_000) { lastWrite = now; flush() }
    }

    private fun flush() {
        val f = file ?: return
        io.execute { runCatching { f.writeText(ring.dump()) } }
    }

    /** The text people share. Only app facts are in the header; no ids, no names. */
    fun exportText(extra: List<String> = emptyList()): String =
        ring.export(
            listOf(
                "App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            ) + extra,
        )

    fun clear() { ring.clear(); flush() }

    fun writeExportFile(ctx: Context, extra: List<String> = emptyList()): File {
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, "walk-buddy-diagnostics.txt")
        f.writeText(exportText(extra))
        return f
    }
}
