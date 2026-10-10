package com.walkbuddy.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.walkbuddy.domain.WalkNotes
import java.io.ByteArrayOutputStream
import java.io.File

/** Photos attached to a walk live in app-private storage only: copied, down-sized and re-encoded, never referencing the original. */
object WalkMedia {
    private fun dir(ctx: Context): File = File(ctx.filesDir, "walk_media").apply { mkdirs() }

    fun file(ctx: Context, name: String): File = File(dir(ctx), File(name).name)

    /** Copies the picked image, shrinks it to the size limit and returns the stored file name, or null if it cannot be read. */
    fun save(ctx: Context, walkId: Long, uri: Uri): String? = runCatching {
        val resolver = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        val opts = BitmapFactory.Options().apply { inSampleSize = WalkNotes.sampleSize(bounds.outWidth, bounds.outHeight) }
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val (w, h) = WalkNotes.targetSize(raw.width, raw.height)
        val bmp = if (w == raw.width && h == raw.height) raw else Bitmap.createScaledBitmap(raw, w, h, true)
        var q = 85
        var bytes: ByteArray
        while (true) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
            bytes = out.toByteArray()
            q = WalkNotes.nextQuality(q, bytes.size.toLong()) ?: break
        }
        val name = WalkNotes.photoFileName(walkId)
        file(ctx, name).writeBytes(bytes)
        name
    }.getOrNull()

    fun delete(ctx: Context, name: String?) {
        if (name != null) runCatching { file(ctx, name).delete() }
    }

    fun deleteAll(ctx: Context) {
        runCatching { dir(ctx).listFiles()?.forEach { it.delete() } }
    }
}
