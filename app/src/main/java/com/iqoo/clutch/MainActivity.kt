package com.iqoo.clutch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    /** Fires after the user approves the system "start recording" prompt. */
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
                putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(intent)
        } else {
            SessionState.setStatus("Screen capture was declined.")
        }
    }

    /**
     * RECORD_AUDIO is the one that actually matters — without it AudioRecord comes back
     * uninitialized and the detector silently never fires. Ask before the capture prompt.
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            launchProjectionPrompt()
        } else {
            SessionState.setStatus("Microphone permission is required to detect highlights.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        SessionState.loadClipsFromDisk(File(getExternalFilesDir(null), "clips"))

        setContent {
            MaterialTheme {
                ClutchScreen(
                    onStart = ::requestPermissionsThenStart,
                    onStop = ::stopSession
                )
            }
        }
    }

    private fun requestPermissionsThenStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(needed.toTypedArray())
    }

    private fun launchProjectionPrompt() {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    /**
     * Sends a stop action to the *running* service. (The original build called
     * stopCapture() on a throwaway CaptureService() instance, which silently no-opped
     * while the real service kept recording and never exported anything.)
     */
    private fun stopSession() {
        startService(
            Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)
        )
    }
}

@Composable
fun ClutchScreen(onStart: () -> Unit, onStop: () -> Unit) {
    val isRecording by SessionState.isRecording.collectAsStateWithLifecycle()
    val status by SessionState.status.collectAsStateWithLifecycle()
    val highlights by SessionState.highlights.collectAsStateWithLifecycle()
    val clips by SessionState.clips.collectAsStateWithLifecycle()
    val levelDb by SessionState.levelDb.collectAsStateWithLifecycle()
    val baselineDb by SessionState.baselineDb.collectAsStateWithLifecycle()
    val elapsedMs by SessionState.elapsedMs.collectAsStateWithLifecycle()
    val summary by SessionState.summary.collectAsStateWithLifecycle()

    var playing by remember { mutableStateOf<File?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text("CLUTCH", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(
            "Auto highlight reel — on-device, real time",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { if (isRecording) onStop() else onStart() }) {
                Text(if (isRecording) "Stop session" else "Start session")
            }
            Spacer(Modifier.width(12.dp))
            if (isRecording) {
                Text(formatDuration(elapsedMs), style = MaterialTheme.typography.titleMedium)
            } else if (clips.isNotEmpty()) {
                OutlinedButton(onClick = { SessionState.clearClips() }) { Text("Clear clips") }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Live proof that the detector is listening — the meter moves with the game,
        // and the highlight counter ticks up in front of the judges.
        if (isRecording) {
            AudioMeter(levelDb = levelDb, baselineDb = baselineDb)
            Spacer(Modifier.height(8.dp))
            Text(
                "${highlights.size} highlight moment(s) detected",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(12.dp))
        }

        Text(status, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(16.dp))

        if (clips.isNotEmpty()) {
            Text("Highlights", style = MaterialTheme.typography.titleLarge)
            summary?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(clips, key = { it.file.absolutePath }) { clip ->
                    ClipCard(clip = clip, onPlay = { playing = clip.file })
                }
            }
        }
    }

    playing?.let { file ->
        VideoDialog(file = file, onDismiss = { playing = null })
    }
}

@Composable
private fun AudioMeter(levelDb: Float, baselineDb: Float) {
    // Game audio typically sits in the 40-90 dBFS-ish range with this RMS scale.
    val normalized = ((levelDb - 30f) / 60f).coerceIn(0f, 1f)
    Column {
        LinearProgressIndicator(
            progress = { normalized },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "level %.0f dB   baseline %.0f dB   delta %+.0f dB"
                .format(Locale.US, levelDb, baselineDb, levelDb - baselineDb),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ClipCard(clip: Clip, onPlay: () -> Unit) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth().clickable { onPlay() }) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Thumbnail(
                file = clip.file,
                modifier = Modifier
                    .width(110.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // The review model's title when it ran; the filename is the fallback so a
                // clip is never unlabelled on an offline demo.
                Text(
                    clip.title ?: clip.file.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (clip.title != null) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    buildString {
                        append("at ${formatDuration(clip.sourceTimestampMs)}")
                        if (clip.confidence > 0f) {
                            append("  ·  %.1fx above baseline".format(Locale.US, clip.confidence))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = { shareClip(context, clip.file) }) { Text("Share") }
        }
    }
}

@Composable
private fun Thumbnail(file: File, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(file.absolutePath)
                    retriever.frameAtTime
                }
            }.getOrNull()
        }
    }

    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun VideoDialog(file: File, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(file.name) },
        text = {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoPath(file.absolutePath)
                        setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                        setOnPreparedListener { it.isLooping = true; start() }
                    }
                }
            )
        }
    )
}

private fun shareClip(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(share, "Share highlight"))
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(Locale.US, totalSeconds / 60, totalSeconds % 60)
}
