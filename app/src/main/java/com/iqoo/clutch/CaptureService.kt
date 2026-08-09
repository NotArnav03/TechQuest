package com.iqoo.clutch

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the whole capture lifecycle:
 *   - MediaProjection -> VirtualDisplay -> MediaRecorder (video + mic audio, to a file)
 *   - MediaProjection -> AudioPlaybackCaptureConfiguration -> AudioRecord (live audio tap,
 *     read on a background thread, fed into HighlightDetector)
 *   - Collects highlight timestamps for the whole session
 *   - On stop: hands the recorded file + timestamp list to ClipExporter
 *
 * Controlled entirely by Intent actions (ACTION_START / ACTION_STOP) — never construct
 * this class yourself, or you'll be poking a dead object while the real service runs on.
 *
 * NOTE for the team: AudioPlaybackCaptureConfiguration behavior varies by OEM/Android
 * version, and it will NOT capture DRM-protected or opted-out app audio. Test against
 * whatever game you're using for the live demo well before the stage.
 */
class CaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "clutch_capture"
        const val NOTIF_ID = 1
        const val ACTION_START = "com.iqoo.clutch.START"
        const val ACTION_STOP = "com.iqoo.clutch.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /**
         * Records the phone's mic alongside the screen so exported clips have sound.
         * The mic picks the game up off the phone's own speaker — not pristine, but a
         * silent highlight reel undersells the whole idea. Flip to false if the mic
         * permission or the |microphone FGS type causes trouble on the demo device.
         */
        const val RECORD_MIC_AUDIO = true

        /** Short-side capture resolution. Full native res is slow to encode and slow to trim. */
        const val TARGET_SHORT_SIDE = 720

        /**
         * Full session recordings are ~6 Mbps, so a day of testing is several GB if nothing
         * ever cleans up. Keep the most recent few in case you want to re-cut by hand.
         */
        private const val SESSION_RECORDINGS_TO_KEEP = 3

        var lastRecordingFile: File? = null
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var audioRecord: AudioRecord? = null
    private var detector: HighlightDetector? = null

    private var captureStartMs = 0L
    private var recordingDurationMs = 0L

    /** Local, not the shared UI flag — the audio loop must not depend on UI state. */
    @Volatile
    private var capturing = false

    /**
     * True from the moment capture stops until the last clip is written. The service stays
     * in the foreground for this whole window: export runs on Transformer's own threads and
     * outlives the capture, and a backgrounded process with no foreground service attached
     * is exactly what the OS kills first — which on stage looks like "the clips never came".
     */
    private var exporting = false

    private val highlights = mutableListOf<Highlight>()
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * API 34 throws IllegalStateException from createVirtualDisplay() unless a callback
     * is registered first. It also fires if the user revokes the capture from the
     * system UI, which we treat as a stop.
     */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w("CLUTCH", "MediaProjection stopped by system/user")
            if (capturing) stopCapture()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }
            ACTION_START -> Unit
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e("CLUTCH", "No valid projection consent in start intent")
            SessionState.setStatus("Screen capture permission was not granted.")
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14 requires the foreground service to be running *before* the
        // projection token is redeemed, and the type must be declared here too.
        startForegroundCompat()

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)?.also {
            it.registerCallback(projectionCallback, mainHandler)
        }
        if (mediaProjection == null) {
            SessionState.setStatus("Could not acquire MediaProjection.")
            stopSelf()
            return START_NOT_STICKY
        }

        // Order matters: these must all be set before any capture thread starts,
        // or the audio loop reads capturing=false and exits immediately, and
        // elapsed timestamps come out as epoch millis.
        highlights.clear()
        captureStartMs = SystemClock.elapsedRealtime()
        capturing = true
        // A previous export may still be draining; its callback is now stale (it checks
        // `capturing` before stopping). Clearing this keeps the new session stoppable.
        exporting = false
        SessionState.startSession()

        try {
            startVideoCapture()
            startAudioTap()
            startElapsedTicker()
        } catch (t: Throwable) {
            Log.e("CLUTCH", "Failed to start capture", t)
            SessionState.setStatus("Capture failed to start: ${t.message}")
            stopCapture()
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    private fun startVideoCapture() {
        val metrics = DisplayMetrics().also {
            (getSystemService(DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)
                .getRealMetrics(it)
        }
        val (width, height) = scaledCaptureSize(metrics.widthPixels, metrics.heightPixels)
        Log.w("CLUTCH", "Capturing at ${width}x$height (screen ${metrics.widthPixels}x${metrics.heightPixels})")

        val outputFile = File(getExternalFilesDir(null), "session_${System.currentTimeMillis()}.mp4")
        lastRecordingFile = outputFile

        mediaRecorder = MediaRecorder(this).apply {
            // Audio source must be set before setOutputFormat; encoders after it.
            if (RECORD_MIC_AUDIO) setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (RECORD_MIC_AUDIO) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
            }
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(width, height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(6_000_000)
            setOutputFile(outputFile.absolutePath)
            prepare()
        }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "clutch_capture",
            width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder!!.surface, null, null
        )

        mediaRecorder?.start()
    }

    /**
     * Encoders reject odd dimensions and choke on very large ones. Scale the short
     * side to TARGET_SHORT_SIDE, preserve aspect, and round both to a multiple of 16.
     */
    private fun scaledCaptureSize(screenW: Int, screenH: Int): Pair<Int, Int> {
        val shortSide = minOf(screenW, screenH)
        val scale = if (shortSide > TARGET_SHORT_SIDE) TARGET_SHORT_SIDE.toFloat() / shortSide else 1f
        fun align(v: Int) = ((v * scale).toInt() / 16) * 16
        return align(screenW).coerceAtLeast(16) to align(screenH).coerceAtLeast(16)
    }

    private fun startAudioTap() {
        val projection = mediaProjection ?: return

        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(44100)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val bufferSize = AudioRecord.getMinBufferSize(
            44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize * 2)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("CLUTCH", "AudioRecord failed to initialize — is RECORD_AUDIO granted?")
            SessionState.setStatus("Audio tap unavailable — check mic permission.")
            record.release()
            return
        }
        audioRecord = record

        detector = HighlightDetector(
            sampleRate = 44100,
            onLevel = { level, baseline -> SessionState.updateLevels(level, baseline) },
            onHighlight = { timestampMs, confidence ->
                Log.w("CLUTCH", "Highlight @ ${timestampMs}ms confidence=$confidence")
                val highlight = Highlight(timestampMs, confidence)
                highlights.add(highlight)
                SessionState.addHighlight(highlight)
            }
        )

        record.startRecording()

        serviceScope.launch {
            val buffer = ShortArray(bufferSize)
            while (capturing) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    val elapsed = SystemClock.elapsedRealtime() - captureStartMs
                    detector?.onAudioBuffer(buffer, read, elapsed)
                } else if (read < 0) {
                    Log.e("CLUTCH", "AudioRecord.read error: $read")
                    break
                }
            }
        }
    }

    private fun startElapsedTicker() {
        serviceScope.launch {
            while (isActive && capturing) {
                SessionState.updateElapsed(SystemClock.elapsedRealtime() - captureStartMs)
                delay(250)
            }
        }
    }

    fun stopCapture() {
        // A second stop while clips are still being written must not tear the service down
        // underneath the exporter — the shade action and the projection callback can both fire.
        if (exporting) return
        if (!capturing) {
            stopForegroundCompat()
            stopSelf()
            return
        }
        capturing = false
        recordingDurationMs = SystemClock.elapsedRealtime() - captureStartMs
        SessionState.endSession()

        // MediaRecorder.stop() throws if it never received a frame (e.g. a sub-second
        // session). Catching it means a mistimed tap doesn't take the demo down.
        runCatching { mediaRecorder?.stop() }
            .onFailure { Log.e("CLUTCH", "MediaRecorder.stop failed — clip may be unusable", it) }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null

        runCatching {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        }
        mediaProjection = null

        val file = lastRecordingFile
        val captured = highlights.toList()
        if (file != null && file.exists() && file.length() > 0) {
            exporting = true
            reviewThenExport(file, captured)
        } else {
            SessionState.setStatus("Session ended, but no usable recording was produced.")
            stopForegroundCompat()
            stopSelf()
        }
    }

    /**
     * Two stages, in this order and never the other way round:
     *   1. Review (network, optional) — HighlightNarrator looks at frames from each candidate
     *      and decides which are real and what to call them.
     *   2. Export (local, always) — trims the winners out of the session recording.
     *
     * Stage 1 can only ever narrow and name what the detector already found. If it returns
     * null — no API key, no signal, a venue hotspot that times out — we export the detector's
     * own top picks instead, so an offline run is identical apart from the titles.
     */
    private fun reviewThenExport(file: File, captured: List<Highlight>) {
        SessionState.setStatus("Session ended — reviewing ${captured.size} moment(s)...")
        updateNotification("Reviewing your session...", ongoing = true)

        serviceScope.launch {
            val narration = runCatching {
                HighlightNarrator().narrate(file, captured, recordingDurationMs)
            }.getOrNull()

            withContext(Dispatchers.Main) {
                val chosen = narration?.clips?.map { it.highlight } ?: captured
                val titles = narration?.clips
                    ?.associate { it.highlight.timestampMs to it.title }
                    .orEmpty()
                SessionState.setSummary(narration?.summary)
                Log.w(
                    "CLUTCH",
                    "Review ${if (narration == null) "skipped/failed" else "kept ${chosen.size}"}" +
                        " of ${captured.size} candidate(s)"
                )

                updateNotification("Cutting your highlight clips...", ongoing = true)
                ClipExporter(applicationContext).exportClips(
                    sourceFile = file,
                    highlights = chosen,
                    recordingDurationMs = recordingDurationMs,
                    titles = titles,
                    onFinished = {
                        exporting = false
                        pruneOldRecordings(keep = file)
                        // If the user already started a new session while this was
                        // finishing, the callback is stale — tearing down now would
                        // kill the capture that's currently running.
                        if (!capturing) {
                            stopForegroundCompat()
                            stopSelf()
                        }
                    }
                )
            }
        }
    }

    /** Deletes all but the most recent session recordings, always sparing the current one. */
    private fun pruneOldRecordings(keep: File) {
        val recordings = getExternalFilesDir(null)
            ?.listFiles { f -> f.isFile && f.name.startsWith("session_") && f.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        recordings
            .filter { it.absolutePath != keep.absolutePath }
            .drop(SESSION_RECORDINGS_TO_KEEP - 1)
            .forEach { if (it.delete()) Log.w("CLUTCH", "Pruned old recording ${it.name}") }
    }

    private fun startForegroundCompat() {
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (RECORD_MIC_AUDIO && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        capturing = false
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "CLUTCH Capture", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    /** Swaps the notification text without dropping foreground state (e.g. capture -> export). */
    private fun updateNotification(text: String, ongoing: Boolean) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(
            NOTIF_ID,
            buildNotification(
                title = "CLUTCH",
                text = text,
                showStopAction = false,
                ongoing = ongoing
            )
        )
    }

    private fun buildNotification(
        title: String = "CLUTCH is watching for highlights",
        text: String = "Recording session — clips saved automatically",
        showStopAction: Boolean = true,
        ongoing: Boolean = true
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_clutch_notification)
            .setContentIntent(openApp)
            .setOngoing(ongoing)

        if (showStopAction) {
            val stopIntent = PendingIntent.getService(
                this, 1,
                Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE
            )
            // Stop from the shade without leaving the game — the demo never has to
            // tab back to CLUTCH mid-session.
            builder.addAction(android.R.drawable.ic_media_pause, "Stop session", stopIntent)
        }
        return builder.build()
    }
}
