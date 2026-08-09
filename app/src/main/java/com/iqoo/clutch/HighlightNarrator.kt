package com.iqoo.clutch

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One clip the model chose to keep, with the name it gave it. */
data class NarratedClip(
    val highlight: Highlight,
    val title: String,
    val reason: String
)

/** The model's verdict on a whole session. */
data class Narration(
    val clips: List<NarratedClip>,
    val summary: String
)

/**
 * The second half of CLUTCH's intelligence, and the half that does NOT run in real time.
 *
 * Division of labour:
 *   - HighlightDetector runs continuously, on-device, during play. It optimises for RECALL:
 *     fire on anything that might be a moment. It cannot be a network call — the whole product
 *     depends on it keeping up with live gameplay.
 *   - This class runs ONCE, after the user stops, over the handful of candidates the detector
 *     produced. It optimises for PRECISION and PRESENTATION: throw out the loud menu screen and
 *     the reload, keep the actual plays, and give each one a name a human would recognise.
 *
 * It looks at frames, not just numbers. Audio loudness alone cannot tell a kill from a crowd
 * sting from someone shouting at their phone — two JPEGs per candidate can.
 *
 * FAILS CLOSED, ALWAYS. No key, no signal, a slow venue hotspot, a model that returns prose
 * instead of JSON — every one of those paths returns null, and CaptureService falls straight
 * back to the detector's own confidence ranking. The demo never depends on the network.
 */
class HighlightNarrator(
    private val apiKey: String = BuildConfig.OPENROUTER_API_KEY,
    private val model: String = BuildConfig.OPENROUTER_MODEL
) {

    companion object {
        private const val TAG = "CLUTCH_AI"
        private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"

        /** Two frames per candidate: just before the spike, and just after the payoff. */
        private const val FRAME_LEAD_MS = 1_000L
        private const val FRAME_TRAIL_MS = 1_500L

        /** Small enough to stay cheap and fast; large enough to read a killfeed. */
        private const val FRAME_MAX_EDGE = 512
        private const val FRAME_JPEG_QUALITY = 70

        /** Cap what we send — a 90s session shouldn't produce a 20-image request. */
        private const val MAX_CANDIDATES = 6

        /** How many the model is allowed to keep. Matches ClipExporter.MAX_CLIPS. */
        private const val KEEP_CLIPS = 3

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 45_000

        private val SYSTEM_PROMPT = """
            You review candidate highlight moments from a phone gameplay recording.

            An on-device audio detector flagged these moments by loudness spikes, so it has
            good recall but poor precision: menu music, reloads, ambient crowd noise and the
            player talking all trip it. Your job is to look at the frames and decide which
            candidates are genuinely worth putting in a highlight reel, and to name them.

            Judge each candidate on what the frames actually show — a kill, a goal, a finish,
            a near-miss, a dramatic swing. Reject candidates that show menus, loading screens,
            scoreboards, idle walking, or nothing in particular, however loud they were.

            Titles: 2-5 words, specific to what happened, no punctuation at the end. Prefer
            "Triple kill on B site" over "Exciting moment". Never invent detail you cannot
            see in the frames — if you can tell something big happened but not what, say so
            plainly ("Big fight, close range").

            Reply with JSON only. No markdown, no prose, no code fences. Exact shape:
            {"summary":"<one sentence about the session>",
             "clips":[{"index":<candidate index>,"keep":<true|false>,
                       "title":"<2-5 words>","reason":"<short justification>"}]}

            Include an entry for every candidate you were given. Keep at most $KEEP_CLIPS.
            If none are worth keeping, set keep=false on all of them and say so in the summary.
        """.trimIndent()
    }

    /**
     * @return the model's picks, or null if anything at all went wrong — caller must fall back.
     */
    suspend fun narrate(
        sourceFile: File,
        candidates: List<Highlight>,
        recordingDurationMs: Long
    ): Narration? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Log.i(TAG, "No OPENROUTER_API_KEY set — using the on-device ranking only.")
            return@withContext null
        }
        if (candidates.isEmpty()) return@withContext null

        runCatching {
            val shortlist = candidates
                .sortedByDescending { it.confidence }
                .take(MAX_CANDIDATES)
                .sortedBy { it.timestampMs }

            val frames = extractFrames(sourceFile, shortlist, recordingDurationMs)
            if (frames.values.all { it.isEmpty() }) {
                Log.w(TAG, "Could not extract any frames — skipping review")
                return@runCatching null
            }

            val body = buildRequest(shortlist, frames, recordingDurationMs)
            val raw = post(body) ?: return@runCatching null
            parseNarration(raw, shortlist)
        }.onFailure {
            Log.e(TAG, "Highlight review failed — falling back to detector ranking", it)
        }.getOrNull()
    }

    // ---------------------------------------------------------------- frames

    /** Pulls and JPEG-encodes a couple of frames around each candidate timestamp. */
    private fun extractFrames(
        sourceFile: File,
        candidates: List<Highlight>,
        recordingDurationMs: Long
    ): Map<Int, List<String>> {
        val result = mutableMapOf<Int, List<String>>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(sourceFile.absolutePath)
            candidates.forEachIndexed { index, highlight ->
                val offsets = listOf(
                    highlight.timestampMs - FRAME_LEAD_MS,
                    highlight.timestampMs + FRAME_TRAIL_MS
                ).map { it.coerceIn(0L, maxOf(recordingDurationMs - 100, 0L)) }

                result[index] = offsets.mapNotNull { atMs ->
                    runCatching {
                        // OPTION_CLOSEST is slower than a keyframe seek but lands on the
                        // frame we actually asked for, which is the whole point here.
                        retriever.getFrameAtTime(
                            atMs * 1000,
                            MediaMetadataRetriever.OPTION_CLOSEST
                        )?.let { encodeFrame(it) }
                    }.getOrNull()
                }
            }
        } finally {
            runCatching { retriever.release() }
        }
        return result
    }

    /** Downscale to FRAME_MAX_EDGE on the long side, JPEG, base64 — no newlines. */
    private fun encodeFrame(bitmap: Bitmap): String {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longEdge > FRAME_MAX_EDGE) {
            val ratio = FRAME_MAX_EDGE.toFloat() / longEdge
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }

        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, FRAME_JPEG_QUALITY, out)
            out.toByteArray()
        }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()

        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // --------------------------------------------------------------- request

    private fun buildRequest(
        candidates: List<Highlight>,
        frames: Map<Int, List<String>>,
        recordingDurationMs: Long
    ): String {
        val content = JSONArray()

        val briefing = buildString {
            append("Session length: ${recordingDurationMs / 1000}s. ")
            append("${candidates.size} candidate moment(s), in order.\n")
            candidates.forEachIndexed { index, highlight ->
                append(
                    "Candidate $index — at %ds, %.1fx above the rolling audio baseline. %d frame(s) follow.\n"
                        .format(
                            highlight.timestampMs / 1000,
                            highlight.confidence,
                            frames[index]?.size ?: 0
                        )
                )
            }
        }
        content.put(JSONObject().put("type", "text").put("text", briefing))

        // Label every image so the model can tie frames back to candidate indices —
        // without the labels a flat image list is ambiguous the moment one frame fails.
        candidates.indices.forEach { index ->
            frames[index].orEmpty().forEachIndexed { frameIndex, base64 ->
                content.put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", "Candidate $index, frame ${frameIndex + 1}:")
                )
                content.put(
                    JSONObject()
                        .put("type", "image_url")
                        .put(
                            "image_url",
                            JSONObject().put("url", "data:image/jpeg;base64,$base64")
                        )
                )
            }
        }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", content))

        return JSONObject()
            .put("model", model)
            .put("max_tokens", 1200)
            .put("messages", messages)
            .toString()
    }

    private fun post(body: String): String? {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            // OpenRouter uses these for attribution on its dashboard; both are optional.
            setRequestProperty("HTTP-Referer", "https://github.com/NotArnav03/TechQuest")
            setRequestProperty("X-Title", "CLUTCH")
        }

        return try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            if (connection.responseCode !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "OpenRouter returned ${connection.responseCode}: $error")
                return null
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    // --------------------------------------------------------------- parsing

    private fun parseNarration(raw: String, candidates: List<Highlight>): Narration? {
        val message = JSONObject(raw)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.takeIf { it.isNotBlank() }
            ?: run {
                Log.e(TAG, "No message content in response: ${raw.take(400)}")
                return null
            }

        // Models sometimes wrap JSON in code fences despite being told not to.
        val json = JSONObject(message.trim().removeSurrounding("```").removePrefix("json").trim())

        val verdicts = json.optJSONArray("clips") ?: return null
        val kept = mutableListOf<NarratedClip>()
        for (i in 0 until verdicts.length()) {
            val entry = verdicts.optJSONObject(i) ?: continue
            if (!entry.optBoolean("keep", false)) continue
            val candidate = candidates.getOrNull(entry.optInt("index", -1)) ?: continue
            kept += NarratedClip(
                highlight = candidate,
                title = entry.optString("title").ifBlank { "Highlight" },
                reason = entry.optString("reason")
            )
        }

        if (kept.isEmpty()) {
            Log.i(TAG, "Model kept nothing — falling back to detector ranking")
            return null
        }

        return Narration(
            clips = kept.take(KEEP_CLIPS),
            summary = json.optString("summary").ifBlank { "${kept.size} highlight(s) found." }
        )
    }
}
