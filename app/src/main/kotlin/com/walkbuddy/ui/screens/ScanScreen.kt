package com.walkbuddy.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.walkbuddy.domain.Invites
import com.walkbuddy.ui.components.Disclaimer
import com.walkbuddy.ui.components.ScreenTitle
import com.walkbuddy.ui.components.SectionCard
import java.util.concurrent.Executors

/**
 * Reads a Walk Buddy invite QR with CameraX and ZXing (both run on the phone; no Google Play Services, no network).
 * The camera is used only while this screen is open, nothing is recorded or saved, and a link or code can always be typed instead.
 */
@Composable
fun ScanScreen(onInvite: (String) -> Unit, onTypeInstead: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; asked = true }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Scan an invite", subtitle = "Point the camera at the QR code on your friend's phone.")
        if (granted) {
            ScanPreview(onInvite = onInvite, onTypeInstead = onTypeInstead)
        } else {
            SectionCard("Camera access") {
                Text("Walk Buddy uses the camera only on this screen, to read a QR code. Nothing is recorded, saved or sent anywhere.")
                if (asked) Text("The camera is off, so scanning is not available. You can still join with a link or a code.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Allow the camera") }
                OutlinedButton(onClick = onTypeInstead, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Paste a link or type a code instead") }
            }
        }
        OutlinedButton(onClick = onBack, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Back") }
        Disclaimer("Only join walks with people you trust: the group can see where you are while you walk.")
    }
}

@Composable
private fun ScanPreview(onInvite: (String) -> Unit, onTypeInstead: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var message by remember { mutableStateOf<String?>(null) }
    var handled by remember { mutableStateOf(false) }
    val main = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            runCatching {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor, QrAnalyzer { text ->
                    main.post {
                        if (handled) return@post
                        if (Invites.parse(text) != null) {
                            handled = true
                            onInvite(text)
                        } else {
                            message = "That QR code is not a Walk Buddy invite."
                        }
                    }
                })
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { message = "The camera could not be started. You can paste a link or type a code instead." }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            runCatching { provider?.unbindAll() }
            executor.shutdown()
        }
    }

    Box(
        Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 420.dp).background(Color.Black, RoundedCornerShape(24.dp))
            .semantics { contentDescription = "Camera view for scanning an invite QR code" },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Surface(
            Modifier.size(220.dp), shape = RoundedCornerShape(24.dp), color = Color.Transparent,
            border = BorderStroke(3.dp, Color.White.copy(alpha = 0.85f)),
        ) {}
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
    OutlinedButton(onClick = onTypeInstead, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Paste a link or type a code instead") }
}

/** Decodes QR codes from the camera's luminance plane with ZXing's pure-Java reader. */
private class QrAnalyzer(private val onFound: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }
    private var lastText: String? = null
    private var lastAtMs = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val w = image.width
            val h = image.height
            val rowStride = plane.rowStride
            val data = ByteArray(w * h)
            for (row in 0 until h) {
                val start = row * rowStride
                if (start >= buf.limit()) break
                buf.position(start)
                buf.get(data, row * w, minOf(w, buf.remaining()))
            }
            val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
            val result = try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            } catch (e: NotFoundException) {
                null
            } finally {
                reader.reset()
            }
            val text = result?.text ?: return
            val now = System.currentTimeMillis()
            // The same code stays in view for many frames; report it at most once a second.
            if (text != lastText || now - lastAtMs > 1_000) {
                lastText = text
                lastAtMs = now
                onFound(text)
            }
        } catch (_: Exception) {
            // A bad frame is simply skipped.
        } finally {
            image.close()
        }
    }
}
