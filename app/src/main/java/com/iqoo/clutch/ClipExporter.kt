package com.iqoo.clutch

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

/**
 * Takes the full session recording + the detected highlights and cuts a short clip
 * around each one (default: 6s before, 4s after — tune these live during the demo
 * based on how "early" your audio spikes tend to fire relative to the visual moment).
 *
 * Uses androidx.media3 Transformer — Google's first-party trim/export API, so this
 * avoids pulling in a third-party FFmpeg build under hackathon time pressure.
 *
 * Things this deliberately does that the naive version didn't:
 *   1. Exports strictly SEQUENTIALLY. Phones expose only a handful of hardware codec
 *      instances; firing one Transformer per highlight at once makes most of them fail.
 *   2. Merges highlights whose clip windows would overlap FIRST, and only then keeps
 *      the top MAX_CLIPS by confidence — so three spikes inside one firefight collapse
 *      to one clip and you still get clips from elsewhere in the session, rather than
 *      spending your whole quota on a single loud moment.
 *   3. Names output files per session. Fixed names (clip_1/2/3) meant a short second
 *      session left the previous session's clips on disk, and loadClipsFromDisk()
 *      resurrected them later looking like they came from the current run.
 */
class ClipExporter(private val context: Context) {

    companion object {
        private const val LEAD_IN_MS = 6_000L
        private const val LEAD_OUT_MS = 4_000L

        /** You will never show more than a handful on stage. Fewer clips = faster export. */
        private const val MAX_CLIPS = 3

        /** Shortest clip worth putting in front of a judge. */
        private const val MIN_CLIP_MS = 1_000L

        /** Clips from older sessions to keep on disk (they reload on next app launch). */
        private const val SESSIONS_OF_CLIPS_TO_KEEP = 2
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<PendingClip>()
    private var totalToExport = 0
    private var onFinished: (() -> Unit)? = null
    private var titles: Map<Long, String> = emptyMap()

    private data class PendingClip(
        val startMs: Long,
        val endMs: Long,
        val highlight: Highlight,
        val outputFile: File
    )

    /**
     * @param titles names from the review model, keyed by highlight timestamp. Empty when
     *   the review was skipped or failed — clips then fall back to their filename.
     * @param onFinished invoked on the main thread once every clip has been exported or
     *   failed. CaptureService uses this to hold its foreground notification until the
     *   work is genuinely done — export outlives the capture, and a backgrounded process
     *   with no foreground service is a candidate for being killed mid-encode.
     */
    fun exportClips(
        sourceFile: File,
        highlights: List<Highlight>,
        recordingDurationMs: Long,
        titles: Map<Long, String> = emptyMap(),
        onFinished: () -> Unit = {}
    ) {
        this.titles = titles
        this.onFinished = onFinished

        if (highlights.isEmpty()) {
            Log.d("CLUTCH", "No highlights detected this session — nothing to export")
            finish("Session ended — no highlight moments detected.")
            return
        }

        val outputDir = File(context.getExternalFilesDir(null), "clips").apply { mkdirs() }
        // Ties every clip to the session recording that produced it, so successive runs
        // never collide and you can tell at a glance which take a clip came from.
        val sessionTag = sourceFile.nameWithoutExtension.removePrefix("session_")

        val selected = mergeOverlapping(highlights.sortedBy { it.timestampMs })
            .sortedByDescending { it.confidence }
            .take(MAX_CLIPS)
            .sortedBy { it.timestampMs }

        queue.clear()
        selected.forEachIndexed { index, highlight ->
            val startMs = (highlight.timestampMs - LEAD_IN_MS).coerceAtLeast(0)
            val endMs = (highlight.timestampMs + LEAD_OUT_MS)
                .coerceAtMost(if (recordingDurationMs > 0) recordingDurationMs else Long.MAX_VALUE)
            if (endMs - startMs < MIN_CLIP_MS) return@forEachIndexed // too short to be worth showing

            queue.addLast(
                PendingClip(
                    startMs = startMs,
                    endMs = endMs,
                    highlight = highlight,
                    outputFile = File(outputDir, "clutch_${sessionTag}_${index + 1}.mp4")
                )
            )
        }

        totalToExport = queue.size
        if (totalToExport == 0) {
            finish("Session ended — highlights were too close to the edges to clip.")
            return
        }

        pruneOldClips(outputDir, sessionTag)
        SessionState.setClips(emptyList())
        mainHandler.post { exportNext(sourceFile) }
    }

    /** Collapses highlights whose clip windows would overlap into a single, longer clip. */
    private fun mergeOverlapping(sorted: List<Highlight>): List<Highlight> {
        val merged = mutableListOf<Highlight>()
        sorted.forEach { candidate ->
            val previous = merged.lastOrNull()
            if (previous != null &&
                candidate.timestampMs - previous.timestampMs < LEAD_IN_MS + LEAD_OUT_MS
            ) {
                // keep whichever spiked harder
                if (candidate.confidence > previous.confidence) {
                    merged[merged.lastIndex] = candidate
                }
            } else {
                merged.add(candidate)
            }
        }
        return merged
    }

    /**
     * Keeps a couple of previous sessions' clips around as a demo backup, but stops the
     * folder growing without bound across a day of testing.
     */
    private fun pruneOldClips(outputDir: File, currentSessionTag: String) {
        val bySession = outputDir.listFiles { f -> f.extension == "mp4" }
            ?.groupBy { it.nameWithoutExtension.substringAfter("clutch_").substringBeforeLast("_") }
            ?: return
        bySession.keys
            .filter { it != currentSessionTag }
            .sortedDescending() // tags are millis, so lexical order is chronological
            .drop(SESSIONS_OF_CLIPS_TO_KEEP)
            .forEach { staleTag ->
                bySession[staleTag]?.forEach { file ->
                    if (file.delete()) Log.d("CLUTCH", "Pruned old clip ${file.name}")
                }
            }
    }

    private fun exportNext(sourceFile: File) {
        val pending = queue.removeFirstOrNull()
        if (pending == null) {
            finish("Done — ${SessionState.clips.value.size} highlight clip(s) ready.")
            return
        }

        val done = totalToExport - queue.size
        SessionState.setStatus("Exporting clip $done of $totalToExport...")

        val clippingConfig = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(pending.startMs)
            .setEndPositionMs(pending.endMs)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(sourceFile))
            .setClippingConfiguration(clippingConfig)
            .build()

        val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()

        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    Log.d("CLUTCH", "Clip exported -> ${pending.outputFile.absolutePath}")
                    SessionState.addClip(
                        Clip(
                            file = pending.outputFile,
                            sourceTimestampMs = pending.highlight.timestampMs,
                            confidence = pending.highlight.confidence,
                            title = titles[pending.highlight.timestampMs]
                        )
                    )
                    exportNext(sourceFile)
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException
                ) {
                    Log.e("CLUTCH", "Clip export failed", exception)
                    // Keep going — one bad clip shouldn't sink the rest of the reel.
                    exportNext(sourceFile)
                }
            })
            .build()

        // Transformer must be created and started on a thread with a Looper.
        transformer.start(editedMediaItem, pending.outputFile.absolutePath)
    }

    /** Single exit point, so the service is always released no matter which path we took. */
    private fun finish(status: String) {
        SessionState.setStatus(status)
        val callback = onFinished
        onFinished = null
        callback?.let { mainHandler.post(it) }
    }
}
