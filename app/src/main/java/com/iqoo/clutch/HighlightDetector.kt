package com.iqoo.clutch

import android.util.Log
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Scores incoming game-audio buffers in real time and decides when a "highlight
 * moment" is happening: gunfire, crowd/announcer stings, sudden action spikes.
 *
 * Approach for a hackathon timeframe (deliberately NOT a trained ML model —
 * this is a fast, explainable signal-processing heuristic you can tune live):
 *   1. RMS (loudness) of each buffer, converted to dB
 *   2. Rolling baseline (moving average) of recent RMS, so it adapts to a game's
 *      general volume level instead of using one fixed threshold
 *   3. A highlight fires when current loudness spikes well above the recent
 *      baseline AND stays elevated for a short window (filters out single-frame
 *      pops/clicks)
 *   4. A cooldown window prevents one long action sequence from spamming
 *      dozens of overlapping "highlights"
 *
 * All tuning constants below are expressed in MILLISECONDS, not buffer counts.
 * AudioRecord's minimum buffer size differs per device, so a buffer is ~40ms on
 * one phone and ~150ms on another — frame-count thresholds would silently mean
 * different things on the demo device than on the one you tuned against.
 *
 * TUNING: every buffer is logged under tag CLUTCH_LEVELS as
 *   "level=<dB> baseline=<dB> delta=<dB>".
 * Run one test session against your demo game, `adb logcat -s CLUTCH_LEVELS`,
 * and look at what delta the exciting moments actually hit. Set SPIKE_THRESHOLD_DB
 * a little under that. Do this once with numbers rather than guessing on stage.
 *
 * Swap-in points for later, if time allows:
 *   - Spectral flux / onset detection instead of plain RMS for more precision
 *   - A small on-device classifier (TFLite) trained on a handful of labelled
 *     clips (gunfire vs ambient vs voice) to replace the threshold heuristic
 */
class HighlightDetector(
    private val sampleRate: Int,
    private val onLevel: (levelDb: Float, baselineDb: Float) -> Unit = { _, _ -> },
    private val onHighlight: (timestampMs: Long, confidence: Float) -> Unit
) {

    companion object {
        /** How far above the rolling baseline counts as "something just happened". */
        const val SPIKE_THRESHOLD_DB = 9.0

        /** How long the level must stay elevated before we believe it. Rejects clicks/pops. */
        const val CONFIRM_WINDOW_MS = 250L

        /** How much recent audio defines "normal" for this game. */
        const val BASELINE_WINDOW_MS = 3_000L

        /** Ignore the opening moments — with no baseline yet, the first loud buffer always "spikes". */
        const val WARMUP_MS = 1_500L

        /** Two highlights closer than this are the same action beat. */
        const val COOLDOWN_MS = 8_000L

        /**
         * Frames quieter than this are treated as "no audio yet" and ignored entirely.
         *
         * rmsToDb floors true silence to exactly 0.0 dB, while real gameplay measures
         * 47-83 dB. AudioRecord hands back a few hundred ms of silence before the
         * playback tap starts delivering, and letting those 0 dB frames into the
         * baseline drags it to zero — after which every real frame looks like a +60 dB
         * spike. Measured on an iQOO Z11 against Call of Duty Mobile: 218 consecutive
         * samples, baseline stuck at 0.0 for every single one.
         */
        const val SILENCE_FLOOR_DB = 20.0
    }

    private val baselineWindow = ArrayDeque<Double>()

    /**
     * Derived from the real buffer duration on the first callback, so the windows above
     * mean the same wall-clock time regardless of what buffer size the device handed us.
     */
    private var baselineCapacity = 40
    private var framesRequiredToConfirm = 3
    private var warmupFrames = 20
    private var timingCalibrated = false

    private var consecutiveSpikeFrames = 0
    private var peakDeltaThisSpike = 0.0

    private var lastHighlightAtMs = Long.MIN_VALUE / 2
    private var framesSeen = 0

    /**
     * Feed this raw PCM16 mono buffer as it arrives from AudioRecord.
     * elapsedMs = milliseconds since capture started (tracked in CaptureService).
     */
    fun onAudioBuffer(pcm16: ShortArray, length: Int, elapsedMs: Long) {
        if (length <= 0) return
        if (!timingCalibrated) calibrateTiming(length)

        val rmsDb = rmsToDb(computeRms(pcm16, length))

        // Near-silence: the tap hasn't started delivering yet, or the game is paused.
        // These frames must not touch the baseline — see SILENCE_FLOOR_DB.
        if (rmsDb < SILENCE_FLOOR_DB) {
            consecutiveSpikeFrames = 0
            peakDeltaThisSpike = 0.0
            onLevel(rmsDb.toFloat(), currentBaseline(rmsDb).toFloat())
            return
        }

        framesSeen++
        val baselineAvg = currentBaseline(rmsDb)
        val delta = rmsDb - baselineAvg

        onLevel(rmsDb.toFloat(), baselineAvg.toFloat())

        // Throttled so a 90s session doesn't produce thousands of log lines
        if (framesSeen % 5 == 0) {
            Log.w(
                "CLUTCH_LEVELS",
                "level=%.1f baseline=%.1f delta=%.1f".format(rmsDb, baselineAvg, delta)
            )
        }

        // Feed the baseline on EVERY audible frame, including spiking ones. Gating this
        // on "not currently spiking" turns a momentarily wrong baseline into a permanently
        // wrong one — nothing can ever correct it. The window is ~3s, so a 250ms spike
        // shifts the average by well under a dB, which is the protection the gate was
        // reaching for anyway. Add AFTER computing delta so a frame never dilutes itself.
        baselineWindow.addLast(rmsDb)
        if (baselineWindow.size > baselineCapacity) baselineWindow.removeFirst()

        if (delta > SPIKE_THRESHOLD_DB) {
            consecutiveSpikeFrames++
            peakDeltaThisSpike = maxOf(peakDeltaThisSpike, delta)
        } else {
            consecutiveSpikeFrames = 0
            peakDeltaThisSpike = 0.0
        }

        if (framesSeen < warmupFrames) return

        // >= not ==. With exact equality the counter can sail past the trigger value
        // while warmup is still returning early, and then never equal it again for the
        // rest of the session. The cooldown below is what prevents repeat fires.
        if (consecutiveSpikeFrames >= framesRequiredToConfirm &&
            elapsedMs - lastHighlightAtMs > COOLDOWN_MS
        ) {
            lastHighlightAtMs = elapsedMs
            // Score on the loudest frame of this spike, not whichever frame happened to
            // confirm it — otherwise a huge explosion and a marginal one rank the same.
            val confidence = (peakDeltaThisSpike / SPIKE_THRESHOLD_DB).coerceIn(0.0, 3.0).toFloat()
            onHighlight(elapsedMs, confidence)
        }
    }

    /** Average of recent audible frames; falls back to the current frame while empty. */
    private fun currentBaseline(fallback: Double): Double =
        if (baselineWindow.isEmpty()) fallback else baselineWindow.average()

    /** Convert the millisecond-based tuning constants into buffer counts for this device. */
    private fun calibrateTiming(bufferLength: Int) {
        val bufferMs = (bufferLength * 1000.0 / sampleRate).coerceAtLeast(1.0)
        fun frames(windowMs: Long) = (windowMs / bufferMs).roundToInt().coerceAtLeast(1)

        baselineCapacity = frames(BASELINE_WINDOW_MS)
        framesRequiredToConfirm = frames(CONFIRM_WINDOW_MS)
        warmupFrames = frames(WARMUP_MS)
        timingCalibrated = true

        Log.w(
            "CLUTCH",
            "Detector calibrated: buffer=%.0fms confirm=%d baseline=%d warmup=%d frames"
                .format(bufferMs, framesRequiredToConfirm, baselineCapacity, warmupFrames)
        )
    }

    private fun computeRms(pcm16: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) {
            val sample = pcm16[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / length.coerceAtLeast(1))
    }

    private fun rmsToDb(rms: Double): Double {
        val safeRms = abs(rms).coerceAtLeast(1.0)
        return 20 * log10(safeRms)
    }
}
