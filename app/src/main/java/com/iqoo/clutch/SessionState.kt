package com.iqoo.clutch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** One detected moment: when it fired, and how far above baseline it was. */
data class Highlight(
    val timestampMs: Long,
    val confidence: Float
)

/**
 * One exported clip on disk, ready to play or share.
 *
 * [title] is the name the review model gave it. Null when the review was skipped or
 * failed — the UI falls back to the filename, so a clip is never unlabelled.
 */
data class Clip(
    val file: File,
    val sourceTimestampMs: Long,
    val confidence: Float,
    val title: String? = null
)

/**
 * Single in-memory source of truth shared between CaptureService and the UI.
 *
 * A plain object (not a ViewModel) on purpose: the capture work lives in a Service,
 * so it outlives any Activity/ViewModel scope, and a hackathon doesn't need a
 * repository layer to prove the idea.
 */
object SessionState {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _highlights = MutableStateFlow<List<Highlight>>(emptyList())
    val highlights: StateFlow<List<Highlight>> = _highlights.asStateFlow()

    private val _clips = MutableStateFlow<List<Clip>>(emptyList())
    val clips: StateFlow<List<Clip>> = _clips.asStateFlow()

    private val _status = MutableStateFlow("Ready.")
    val status: StateFlow<String> = _status.asStateFlow()

    /** One-line recap of the session from the review model; null when it didn't run. */
    private val _summary = MutableStateFlow<String?>(null)
    val summary: StateFlow<String?> = _summary.asStateFlow()

    /** Live audio meter, so the judges can see the detector is actually listening. */
    private val _levelDb = MutableStateFlow(0f)
    val levelDb: StateFlow<Float> = _levelDb.asStateFlow()

    private val _baselineDb = MutableStateFlow(0f)
    val baselineDb: StateFlow<Float> = _baselineDb.asStateFlow()

    /** Wall-clock ms of the session so far, for the recording timer. */
    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    fun startSession() {
        _highlights.value = emptyList()
        _elapsedMs.value = 0L
        _isRecording.value = true
        _summary.value = null
        _status.value = "Watching for highlight moments..."
    }

    fun setSummary(text: String?) {
        _summary.value = text
    }

    fun endSession() {
        _isRecording.value = false
        _levelDb.value = 0f
    }

    fun setStatus(text: String) {
        _status.value = text
    }

    fun addHighlight(highlight: Highlight) {
        _highlights.value = _highlights.value + highlight
    }

    fun updateLevels(level: Float, baseline: Float) {
        _levelDb.value = level
        _baselineDb.value = baseline
    }

    fun updateElapsed(ms: Long) {
        _elapsedMs.value = ms
    }

    fun addClip(clip: Clip) {
        _clips.value = (_clips.value + clip).sortedBy { it.sourceTimestampMs }
    }

    fun setClips(clips: List<Clip>) {
        _clips.value = clips.sortedBy { it.sourceTimestampMs }
    }

    /** Remembered from loadClipsFromDisk so clearing can reach files not in the current list. */
    private var clipsDir: File? = null

    /**
     * Clears the reel and deletes every clip on disk — including ones from earlier
     * sessions that aren't in the current list. Deleting only the visible clips left
     * older files behind, which then reappeared on the next launch.
     */
    fun clearClips() {
        clipsDir?.listFiles { f -> f.extension == "mp4" }?.forEach { runCatching { it.delete() } }
        _clips.value.forEach { runCatching { it.file.delete() } }
        _clips.value = emptyList()
        _highlights.value = emptyList()
        _summary.value = null
        _status.value = "Cleared. Ready."
    }

    /**
     * Repopulates the clip list from disk on app launch, so a session recorded
     * earlier (your demo backup) is still there after the app is killed.
     *
     * Only the most recent session's clips are shown. ClipExporter keeps a couple of
     * older sessions on disk as a fallback, but surfacing all of them at once makes
     * stale clips look like they came from the run you just did.
     */
    fun loadClipsFromDisk(clipsDir: File) {
        this.clipsDir = clipsDir
        if (!clipsDir.isDirectory) return
        val newestSession = clipsDir.listFiles { f -> f.extension == "mp4" }
            ?.groupBy { it.nameWithoutExtension.substringAfter("clutch_").substringBeforeLast("_") }
            ?.maxByOrNull { it.key } // tags are millis, so lexical max is the latest session
            ?.value
            ?.sortedBy { it.name }
            ?.map { Clip(it, sourceTimestampMs = 0L, confidence = 0f) }
            ?: emptyList()
        if (newestSession.isNotEmpty()) _clips.value = newestSession
    }
}
