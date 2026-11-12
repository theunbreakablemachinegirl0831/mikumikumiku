package mmm.app.data

import org.json.JSONArray
import org.json.JSONObject

/** How a spectrum display maps levels onto its height. */
enum class VisualizerScaling(val label: String) {
    /** The top follows the loudest part of the music, so neither quiet nor loud passages are cut off. */
    AUTO("자동"),
    /** A fixed window the listener sets: a top level and a range below it. */
    MANUAL("수동"),
}

/** The frequency weighting the spectrum displays apply - to what they draw only, never to the audio. */
enum class VisualizerWeighting(val label: String) {
    FLAT("Flat"),
    A("A-Weighting"),
    EQUAL_LOUDNESS("Equal-Loudness"),
}

/**
 * A background gradient: two or three colours along a line at [angleDegrees] (0 = top to bottom,
 * 90 = left to right), each at its stop position along that line, the whole shifted by [offset].
 */
data class GradientSpec(
    val start: Long = DEFAULT_START,
    val middle: Long? = null,
    val end: Long = DEFAULT_END,
    val angleDegrees: Float = 0f,
    val startStop: Float = 0f,
    val middleStop: Float = 0.5f,
    val endStop: Float = 1f,
    /** -0.5..0.5 of the line's length: moves every stop together. */
    val offset: Float = 0f,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("start", start)
        middle?.let { put("middle", it) }
        put("end", end)
        put("angle", angleDegrees.toDouble())
        put("startStop", startStop.toDouble())
        put("middleStop", middleStop.toDouble())
        put("endStop", endStop.toDouble())
        put("offset", offset.toDouble())
    }

    companion object {
        /** The app's own look: the dusky violet it has always had, falling to near-black. */
        const val DEFAULT_START = 0xFF3B3150L
        const val DEFAULT_END = 0xFF0C0B10L
        val DEFAULT = GradientSpec()

        fun fromJson(o: JSONObject): GradientSpec = GradientSpec(
            start = o.optLong("start", DEFAULT_START),
            middle = if (o.has("middle")) o.getLong("middle") else null,
            end = o.optLong("end", DEFAULT_END),
            angleDegrees = o.optDouble("angle", 0.0).toFloat().mod(360f),
            startStop = o.optDouble("startStop", 0.0).toFloat().coerceIn(0f, 1f),
            middleStop = o.optDouble("middleStop", 0.5).toFloat().coerceIn(0f, 1f),
            endStop = o.optDouble("endStop", 1.0).toFloat().coerceIn(0f, 1f),
            offset = o.optDouble("offset", 0.0).toFloat().coerceIn(-0.5f, 0.5f),
        )
    }
}

data class NamedGradient(val name: String, val gradient: GradientSpec)

enum class BackgroundMode(val label: String) {
    ALBUM("앨범아트 따라가기"),
    CUSTOM("직접 그라데이션"),
}

internal object AppearanceCodec {
    fun presetsToJson(presets: List<NamedGradient>): String = JSONArray().apply {
        presets.forEach { put(JSONObject().apply { put("name", it.name); put("gradient", it.gradient.toJson()) }) }
    }.toString()

    fun presetsFromJson(text: String?): List<NamedGradient> = runCatching {
        val array = JSONArray(text ?: return emptyList())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            NamedGradient(o.getString("name"), GradientSpec.fromJson(o.getJSONObject("gradient")))
        }
    }.getOrDefault(emptyList())

    fun gradientFromJson(text: String?): GradientSpec =
        text?.let { runCatching { GradientSpec.fromJson(JSONObject(it)) }.getOrNull() } ?: GradientSpec.DEFAULT
}
