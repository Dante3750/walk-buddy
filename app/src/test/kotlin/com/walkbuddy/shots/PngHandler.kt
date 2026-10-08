package com.walkbuddy.shots

import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Writes every frame as a full-resolution PNG named after the snapshot. Paparazzi's own report writer shrinks images
 * to at most 1000 px, which is too small to judge type and spacing, so the screenshot loop uses this instead.
 */
class PngHandler(private val dir: File = File("build/shots")) : SnapshotHandler {
    init { dir.mkdirs() }

    override fun newFrameHandler(snapshot: Snapshot, frameCount: Int, fps: Int): SnapshotHandler.FrameHandler =
        object : SnapshotHandler.FrameHandler {
            override fun handle(image: BufferedImage) {
                ImageIO.write(image, "png", File(dir, (snapshot.name ?: snapshot.testName.methodName) + ".png"))
            }
            override fun close() = Unit
        }

    override fun close() = Unit
}
