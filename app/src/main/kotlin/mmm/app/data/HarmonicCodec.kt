package mmm.app.data

import mmm.dsp.harmonics.GroupParams
import mmm.dsp.harmonics.HarmonicAlgorithm
import mmm.dsp.harmonics.HarmonicPreset
import mmm.dsp.harmonics.HarmonicProfile
import mmm.dsp.harmonics.HarmonicSettings
import mmm.dsp.harmonics.TailParams
import org.json.JSONArray
import org.json.JSONObject

/**
 * The harmonic settings as JSON, inside the playback EQ's file.
 *
 * Every number is clamped to its control's range on the way in, so a hand-edited or older file
 * can never put the processor somewhere the screen cannot show.
 */
internal object HarmonicCodec {

    fun write(s: HarmonicSettings): JSONObject = JSONObject().apply {
        put("preset", s.preset.name)
        put("intensity", s.intensityPercent)
        put("nos", s.nos)
        put("bands", JSONArray().apply { s.bandTrimsDb.forEach { put(it) } })
        val p = s.profile
        put("profile", JSONObject().apply {
            put("h2", p.h2Db)
            put("h3", p.h3Db)
            put("algorithm", p.algorithm.name)
            put("tails", JSONObject().apply {
                p.tails.forEach { (algorithm, t) ->
                    put(algorithm.name, JSONObject().apply {
                        put("intensity", t.intensity)
                        put("slope", t.slopeDb)
                        put("centre", t.centre)
                        put("shape", t.shape)
                        put("limit", t.outputLimitDb)
                    })
                }
            })
            put("evenLevel", p.even.levelDb)
            put("evenSlope", p.even.slopeDb)
            put("oddLevel", p.odd.levelDb)
            put("oddSlope", p.odd.slopeDb)
            put("balance", p.balance)
            put("maxOrder", p.maxOrder)
            put("overrides", JSONObject().apply { p.overrides.forEach { (order, db) -> put(order.toString(), db) } })
        })
    }

    fun read(o: JSONObject): HarmonicSettings {
        val defaults = HarmonicSettings()
        val preset = runCatching { HarmonicPreset.valueOf(o.getString("preset")) }.getOrDefault(defaults.preset)
        val p = o.optJSONObject("profile")
        val profile = if (p == null) defaults.profile else {
            val maxOrder = p.optInt("maxOrder", 10).coerceIn(HarmonicSettings.MIN_ORDER, HarmonicSettings.MAX_ORDER)
            val tails = HashMap(HarmonicProfile.DEFAULT_TAILS)
            p.optJSONObject("tails")?.let { t ->
                for (algorithm in HarmonicAlgorithm.entries) {
                    val j = t.optJSONObject(algorithm.name) ?: continue
                    tails[algorithm] = TailParams(
                        intensity = j.optDouble("intensity", 0.8).coerceIn(0.0, 1.0),
                        slopeDb = j.optDouble("slope", 2.0).coerceIn(0.0, HarmonicSettings.SLOPE_MAX_DB),
                        centre = j.optInt("centre", 6).coerceIn(4, HarmonicSettings.MAX_ORDER),
                        shape = j.optDouble("shape", 1.0).coerceIn(HarmonicSettings.SHAPE_MIN, HarmonicSettings.SHAPE_MAX),
                        outputLimitDb = db(j.optDouble("limit", 0.0)),
                    )
                }
            }
            val overrides = HashMap<Int, Double>()
            p.optJSONObject("overrides")?.let { j ->
                j.keys().forEach { key -> key.toIntOrNull()?.takeIf { it in 2..HarmonicSettings.MAX_ORDER }?.let { overrides[it] = db(j.getDouble(key)) } }
            }
            HarmonicProfile(
                h2Db = db(p.optDouble("h2", 0.0)),
                h3Db = db(p.optDouble("h3", -9.0)),
                algorithm = runCatching { HarmonicAlgorithm.valueOf(p.getString("algorithm")) }.getOrDefault(HarmonicAlgorithm.SIGMOID),
                tails = tails,
                even = GroupParams(db(p.optDouble("evenLevel", 0.0)), db(p.optDouble("evenSlope", 0.0))),
                odd = GroupParams(db(p.optDouble("oddLevel", 0.0)), db(p.optDouble("oddSlope", 0.0))),
                balance = p.optDouble("balance", 0.0).coerceIn(-1.0, 1.0),
                maxOrder = maxOrder,
                overrides = overrides,
            )
        }
        val bands = o.optJSONArray("bands")?.let { a -> (0 until a.length()).map { db(a.getDouble(it)) } }
            ?.takeIf { it.size == HarmonicSettings.BANDS } ?: defaults.bandTrimsDb
        return HarmonicSettings(
            preset = preset,
            profile = profile,
            intensityPercent = o.optInt("intensity", 100).coerceIn(0, 100),
            nos = o.optBoolean("nos", false),
            bandTrimsDb = bands,
        )
    }

    /**
     * The settings before version 2, which had H2 and H3 per band in dB below full scale
     * (-150 for off). They become a custom profile with the loudest of each, re-expressed on
     * the new scale, and band trims that keep the bands' differences.
     */
    fun migrateBands(bands: JSONArray): HarmonicSettings {
        val second = (0 until bands.length()).map { bands.getJSONObject(it).optDouble("h2", -150.0) }
        val third = (0 until bands.length()).map { bands.getJSONObject(it).optDouble("h3", -150.0) }
        val loudest2 = second.maxOrNull() ?: -150.0
        val loudest3 = third.maxOrNull() ?: -150.0
        val peak = maxOf(loudest2, loudest3)
        if (peak <= -150.0) return HarmonicSettings()
        val toScale = { level: Double -> db(level - HarmonicSettings.REFERENCE_DB) }
        val trims = (0 until bands.length()).map { i -> db(maxOf(second[i], third[i]) - peak) }
            .takeIf { it.size == HarmonicSettings.BANDS } ?: listOf(0.0, 0.0, 0.0)
        return HarmonicSettings(
            preset = HarmonicPreset.CUSTOM,
            profile = HarmonicProfile(
                h2Db = toScale(loudest2),
                h3Db = toScale(loudest3),
                // The old stage made nothing above the third.
                maxOrder = HarmonicSettings.MIN_ORDER,
            ),
            bandTrimsDb = trims,
        )
    }

    private fun db(value: Double): Double = HarmonicSettings.clampDb(value)
}
