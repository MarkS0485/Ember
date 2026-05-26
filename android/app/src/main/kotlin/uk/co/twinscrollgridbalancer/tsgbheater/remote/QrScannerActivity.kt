package uk.co.twinscrollgridbalancer.tsgbheater.remote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import androidx.camera.core.Preview as CameraPreview

// Scans QR codes via the device camera, listening for any payload
// whose contents start with `tsgb://`. First match wins — the activity
// finishes with the raw URI in extra "uri" so the caller can parse.
class QrScannerActivity : ComponentActivity() {

    companion object { const val RESULT_URI_EXTRA = "uri" }

    private var scanner: BarcodeScanner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build())

        setContent {
            ScannerUi(onUri = { uri ->
                val data = android.content.Intent().putExtra(RESULT_URI_EXTRA, uri)
                setResult(RESULT_OK, data)
                finish()
            })
        }
    }

    override fun onDestroy() {
        scanner?.close()
        super.onDestroy()
    }

    @androidx.compose.runtime.Composable
    private fun ScannerUi(onUri: (String) -> Unit) {
        val ctx = LocalContext.current
        var permitted by remember { mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) }
        val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()) { granted -> permitted = granted }

        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (!permitted) launcher.launch(Manifest.permission.CAMERA)
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (permitted) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        val pv = PreviewView(c)
                        bind(pv, onUri)
                        pv
                    }
                )
                Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                    Text(
                        text = "Hold the QR code from the Windows app inside the frame",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                Text(
                    "Camera permission needed to scan the pairing code.",
                    color = Color.White,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }

    private fun bind(previewView: PreviewView, onUri: (String) -> Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = CameraPreview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val mainExecutor = ContextCompat.getMainExecutor(this)
            analysis.setAnalyzer(mainExecutor) { proxy -> analyse(proxy, onUri) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, analysis)
            } catch (_: Throwable) { /* device may have rotated */ }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyse(proxy: ImageProxy, onUri: (String) -> Unit) {
        val media = proxy.image
        if (media == null) { proxy.close(); return }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner?.process(input)
            ?.addOnSuccessListener { codes ->
                for (b in codes) {
                    val raw = b.rawValue ?: continue
                    if (raw.startsWith("tsgb://", ignoreCase = true)) {
                        onUri(raw)
                        return@addOnSuccessListener
                    }
                }
            }
            ?.addOnCompleteListener { proxy.close() }
    }
}
