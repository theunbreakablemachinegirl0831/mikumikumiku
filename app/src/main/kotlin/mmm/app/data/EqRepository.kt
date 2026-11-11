package mmm.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mmm.app.audiometry.AudiogramStore
import mmm.dsp.artifacts.TubeBand
import mmm.dsp.artifacts.TubeSettings
import mmm.eq.EarphoneCalibration
import mmm.eq.EqFilter
import mmm.eq.EqFilterType
import mmm.eq.EqProfile
import mmm.eq.EqStack
import mmm.eq.FrequencyResponse
import mmm.eq.GraphicEq
import mmm.eq.HearingPrescription
import mmm.eq.HearingRule
import mmm.eq.MatchRule
import mmm.eq.PeqFitSettings
import mmm.eq.PeqFitter
import mmm.training.audiometry.AudiogramCalibration
import mmm.training.audiometry.Ear
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One band of the listener's own parametric EQ. A band can be switched off without losing its settings. */
data class PeqBand(
    val type: EqFilterType = EqFilterType.PEAK,
    val frequency: Double,
    val gainDb: Double = 0.0,
    val q: Double = 1.0,
    val enabled: Boolean = true,
) {
    fun toFilter(): EqFilter = EqFilter(type, frequency, q, gainDb)
}

enum class RuleTargetKind { TARGET, EARPHONE }

/**
 * One line of the automatic EQ: between [fromHz] and [toHz], make the earphone in use sound like
 * [ref] - a target curve by name, or another earphone by id.
 */
data class AutoRule(
    val id: String = UUID.randomUUID().toString().take(8),
    val kind: RuleTargetKind,
    val ref: String,
    val fromHz: Double = 20.0,
    val toHz: Double = 20000.0,
)

/**
 * Everything the playback EQ is set to. Four layers add up - the automatic earphone correction,
 * the hearing correction, the parametric bands and the graphic sliders - under one master
 * switch, with the valve harmonics after them.
 */
data class PlaybackEq(
    val enabled: Boolean = true,
    val preampDb: Double = 0.0,
    /** Lower the level by the largest boost of all layers together, so the EQ cannot clip. */
    val autoHeadroom: Boolean = true,
    val geqEnabled: Boolean = false,
    val geqGains: List<Double> = List(GraphicEq.OCTAVE.size) { 0.0 },
    val peqEnabled: Boolean = false,
    val peq: List<PeqBand> = DEFAULT_PEQ,
    val autoEnabled: Boolean = true,
    val autoRules: List<AutoRule> = emptyList(),
    /** Follow the rules in detail up to 20 kHz instead of 10 kHz. */
    val autoFullRange: Boolean = false,
    val tubeEnabled: Boolean = false,
    val tube: List<TubeBand> = TubeSettings.DEFAULT_BANDS,
    /** Compensate for the listener's own hearing, from the last hearing test. */
    val hearingEnabled: Boolean = false,
    val hearingRule: HearingRule = HearingRule.NAL_R,
    /** 1 is the rule as written; less for a lighter hand. */
    val hearingStrength: Double = 1.0,
    /** Keep the rule's fixed shaping for speech, on top of what the listener's loss asks for. */
    val hearingSpeechShape: Boolean = false,
    /** Also put the EQ on other apps' audio sessions (Spotify), through the session service. */
    val systemEq: Boolean = false,
    /** And on the phone's whole output, for apps that do not announce a session. */
    val systemGlobal: Boolean = false,
    /** How finely the EQ curve is laid out as bands for other apps; the device may take fewer. */
    val systemBandsPerOctave: Int = SYSTEM_BANDS.first(),
) {
    val geq: GraphicEq get() = GraphicEq.of(geqGains.size)

    companion object {
        /** A shelf at each end and three bells between, flat until moved. */
        val DEFAULT_PEQ = listOf(
            PeqBand(EqFilterType.LOW_SHELF, 70.0, q = 0.7),
            PeqBand(EqFilterType.PEAK, 200.0),
            PeqBand(EqFilterType.PEAK, 800.0),
            PeqBand(EqFilterType.PEAK, 2500.0),
            PeqBand(EqFilterType.HIGH_SHELF, 12000.0, q = 0.7),
        )
        const val MAX_PEQ_BANDS = 16
        const val GAIN_LIMIT_DB = 15.0
        val SYSTEM_BANDS = listOf(48, 24, 12, 6)
    }
}

/** What the automatic layer currently is, and the curves that explain it. */
data class AutoFitState(
    val busy: Boolean = false,
    val message: String? = null,
    val sourceName: String? = null,
    /** True when no rule is set and the earphone's own EQ (from its page) is in use. */
    val fromEarphonePage: Boolean = false,
    val filters: List<EqFilter> = emptyList(),
    val measured: FrequencyResponse? = null,
    val predicted: FrequencyResponse? = null,
    val rmsBeforeDb: Double? = null,
    val rmsAfterDb: Double? = null,
)

/** The hearing layer: what the last test showed, what the rule asks for, and the filters that give it. */
data class HearingFitState(
    val message: String? = null,
    /** Relative hearing level per frequency for each ear; higher is worse. */
    val left: Map<Double, Double> = emptyMap(),
    val right: Map<Double, Double> = emptyMap(),
    /** False when the test could not be corrected for the earphone it was taken on. */
    val calibrated: Boolean = false,
    val reliable: Boolean = true,
    /** The rule's gain for the two ears averaged, centred on the midrange. */
    val wanted: FrequencyResponse? = null,
    val filters: List<EqFilter> = emptyList(),
)

/**
 * The playback EQ: its settings, kept in a file, and the profile every playback path applies.
 *
 * The automatic layer is refitted here whenever what it depends on changes - the earphone in use,
 * its measurement, the rules - so the screens only ever edit settings and read results.
 */
class EqRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val earphones: EarphoneRepository,
    private val audiograms: AudiogramStore,
) {
    private val file = File(context.filesDir, "playback_eq.json")
    private val autoCache = File(context.filesDir, "playback_auto_eq.txt")

    private val _state = MutableStateFlow(load())
    val state: StateFlow<PlaybackEq> = _state.asStateFlow()

    // The last fit is kept on disk, so the right sound is there at start-up before the refit finishes.
    private val _auto = MutableStateFlow(
        AutoFitState(filters = runCatching { EqProfile.parseApo(autoCache.readText()).filters }.getOrDefault(emptyList()))
    )
    val auto: StateFlow<AutoFitState> = _auto.asStateFlow()

    private val _hearing = MutableStateFlow(HearingFitState())
    val hearing: StateFlow<HearingFitState> = _hearing.asStateFlow()

    /** The EQ to play, or null to play untouched. */
    val profile: StateFlow<EqProfile?> =
        combine(
            _state,
            _auto.map { it.filters }.distinctUntilChanged(),
            _hearing.map { it.filters }.distinctUntilChanged(),
        ) { state, autoFilters, hearingFilters -> compose(state, autoFilters, hearingFilters) }
            .conflate()
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.Eagerly, compose(_state.value, _auto.value.filters, emptyList()))

    /** The valve harmonics to add after the EQ, or null for none. */
    val tube: StateFlow<TubeSettings?> =
        _state.map { s -> if (s.enabled && s.tubeEnabled) TubeSettings(s.tube).takeIf { it.active } else null }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch(Dispatchers.IO) {
            // Saved a moment after the last change: a dragged slider changes the state sixty times a second.
            _state.drop(1).collectLatest { state ->
                delay(SAVE_DELAY_MS)
                runCatching { file.writeText(toJson(state)) }
            }
        }
        scope.launch {
            combine(
                settings.settings.map { it.activeEarphoneId }.distinctUntilChanged(),
                earphones.earphones,
                _state.map { it.autoRules to it.autoFullRange }.distinctUntilChanged(),
            ) { id, list, rules -> Triple(id, list, rules) }
                .collectLatest { (id, list, rules) ->
                    delay(REFIT_DELAY_MS)
                    refit(list.firstOrNull { it.id == id }, rules.first, rules.second)
                }
        }
        scope.launch {
            audiograms.load()
            combine(
                audiograms.latest,
                settings.settings.map { it.activeEarphoneId }.distinctUntilChanged(),
                earphones.earphones,
                _state.map { Triple(it.hearingRule, it.hearingStrength, it.hearingSpeechShape) }.distinctUntilChanged(),
            ) { result, id, list, rule -> Triple(result, list.firstOrNull { it.id == id }, rule) }
                .collectLatest { (result, earphone, rule) ->
                    delay(REFIT_DELAY_MS)
                    _hearing.value = fitHearing(result, earphone, rule.first, rule.second, rule.third)
                }
        }
    }

    fun update(change: (PlaybackEq) -> PlaybackEq) = _state.update(change)

    /** The layers as they are stacked, for graphs: the whole EQ's response without its preamp. */
    fun layerFilters(
        state: PlaybackEq = _state.value,
        autoFilters: List<EqFilter> = _auto.value.filters,
        hearingFilters: List<EqFilter> = _hearing.value.filters,
    ): List<List<EqFilter>> =
        listOf(
            if (state.autoEnabled) autoFilters else emptyList(),
            if (state.hearingEnabled) hearingFilters else emptyList(),
            if (state.peqEnabled) state.peq.filter { it.enabled }.map(PeqBand::toFilter) else emptyList(),
            if (state.geqEnabled) state.geq.filters(state.geqGains) else emptyList(),
        )

    private fun compose(state: PlaybackEq, autoFilters: List<EqFilter>, hearingFilters: List<EqFilter>): EqProfile? {
        if (!state.enabled) return null
        return EqStack.combine(layerFilters(state, autoFilters, hearingFilters), state.preampDb, state.autoHeadroom)
    }

    /**
     * The hearing layer from the last test. The two ears are averaged, because one EQ plays to
     * both; the test is corrected for the earphone in use, as the test screen does.
     */
    private suspend fun fitHearing(
        result: mmm.training.audiometry.AudiogramResult?,
        earphone: EarphoneProfile?,
        rule: HearingRule,
        strength: Double,
        speechShape: Boolean,
    ): HearingFitState {
        if (result == null) return HearingFitState(message = "아직 청력 검사 결과가 없다. 먼저 순음 청력 검사를 한다.")
        val calibration = earphone?.let { earphones.measurement(it.id) }?.let { EarphoneCalibration(it) }
        val levels = AudiogramCalibration.relativeHearingLevels(result) { f -> calibration?.relativeGainDb(f) ?: 0.0 }
        val left = levels.filterKeys { it.first == Ear.LEFT }.mapKeys { it.key.second }
        val right = levels.filterKeys { it.first == Ear.RIGHT }.mapKeys { it.key.second }
        val both = (left.keys + right.keys).associateWith { f -> listOfNotNull(left[f], right[f]).average() }
        val wanted = HearingPrescription.insertionGain(rule, both, speechShape, strength)
            ?: return HearingFitState(
                message = "검사에서 들은 주파수가 너무 적어 보정을 만들 수 없다. 검사를 다시 한다.",
                left = left, right = right, calibrated = calibration != null, reliable = result.reliable,
            )
        val fit = withContext(Dispatchers.Default) {
            val flat = FrequencyResponse(wanted.frequencies, wanted.frequencies.map { 0.0 })
            PeqFitter(HEARING_FIT).fit(flat, wanted)
        }
        return HearingFitState(
            left = left,
            right = right,
            calibrated = calibration != null,
            reliable = result.reliable,
            wanted = fit.wanted,
            filters = fit.profile.filters,
        )
    }

    private suspend fun refit(source: EarphoneProfile?, rules: List<AutoRule>, fullRange: Boolean) {
        if (source == null) {
            publish(AutoFitState(message = "쓰는 이어폰을 고르면 그 측정치로 EQ를 만든다."))
            return
        }
        val measured = earphones.measurement(source.id)
        val shown = measured?.smooth(SMOOTHING)?.normalized()
        if (rules.isEmpty()) {
            val eq = source.eq
            publish(
                AutoFitState(
                    sourceName = source.name,
                    fromEarphonePage = true,
                    filters = eq?.filters.orEmpty(),
                    measured = shown,
                    predicted = if (measured != null && eq != null) {
                        (measured.smooth(SMOOTHING) + eq.copy(preampDb = 0.0).response()).normalized()
                    } else {
                        null
                    },
                    message = when {
                        eq != null -> null
                        measured == null -> "이 이어폰에는 측정치가 없다. 이어폰 보정에서 측정치를 넣는다."
                        else -> "이 이어폰에는 아직 EQ가 없다. 이어폰 보정에서 타깃을 고르거나, 아래에 규칙을 추가한다."
                    },
                )
            )
            return
        }
        if (measured == null) {
            publish(AutoFitState(sourceName = source.name, message = "이 이어폰에는 측정치가 없어 규칙을 맞출 수 없다."))
            return
        }

        _auto.value = _auto.value.copy(busy = true, sourceName = source.name)
        val missing = ArrayList<String>()
        val matchRules = rules.mapNotNull { rule ->
            val curve = when (rule.kind) {
                RuleTargetKind.TARGET -> earphones.target(rule.ref)
                RuleTargetKind.EARPHONE -> earphones.measurement(rule.ref)
            }
            if (curve == null) missing += rule.ref
            curve?.let { MatchRule(it, rule.fromHz, rule.toHz) }
        }
        if (matchRules.isEmpty()) {
            publish(AutoFitState(sourceName = source.name, measured = shown, message = "규칙의 대상 곡선을 찾지 못했다. 규칙을 다시 고른다."))
            return
        }
        val fit = withContext(Dispatchers.Default) {
            PeqFitter(if (fullRange) PeqFitSettings.FULL_RANGE else PeqFitSettings.DEFAULT).fitRules(measured, matchRules)
        }
        publish(
            AutoFitState(
                sourceName = source.name,
                filters = fit.profile.filters,
                measured = shown,
                predicted = fit.predicted,
                rmsBeforeDb = fit.rmsBeforeDb,
                rmsAfterDb = fit.rmsAfterDb,
                message = if (missing.isEmpty()) null else "대상 곡선이 없는 규칙 ${missing.size}개는 건너뛰었다.",
            )
        )
    }

    private suspend fun publish(result: AutoFitState) {
        _auto.value = result
        withContext(Dispatchers.IO) {
            runCatching { autoCache.writeText(EqProfile(result.filters, 0.0).toApoText()) }
        }
    }

    private fun load(): PlaybackEq = runCatching { fromJson(file.readText()) }.getOrDefault(PlaybackEq())

    private fun toJson(s: PlaybackEq): String = JSONObject().apply {
        put("enabled", s.enabled)
        put("preamp", s.preampDb)
        put("autoHeadroom", s.autoHeadroom)
        put("geqEnabled", s.geqEnabled)
        put("geqGains", JSONArray().apply { s.geqGains.forEach { put(it) } })
        put("peqEnabled", s.peqEnabled)
        put("peq", JSONArray().apply {
            s.peq.forEach { band ->
                put(JSONObject().apply {
                    put("type", band.type.name)
                    put("f", band.frequency)
                    put("g", band.gainDb)
                    put("q", band.q)
                    put("on", band.enabled)
                })
            }
        })
        put("autoEnabled", s.autoEnabled)
        put("autoFullRange", s.autoFullRange)
        put("autoRules", JSONArray().apply {
            s.autoRules.forEach { rule ->
                put(JSONObject().apply {
                    put("id", rule.id)
                    put("kind", rule.kind.name)
                    put("ref", rule.ref)
                    put("from", rule.fromHz)
                    put("to", rule.toHz)
                })
            }
        })
        put("tubeEnabled", s.tubeEnabled)
        put("tube", JSONArray().apply {
            s.tube.forEach { band ->
                put(JSONObject().apply {
                    put("from", band.fromHz)
                    put("to", band.toHz)
                    put("h2", band.secondDb)
                    put("h3", band.thirdDb)
                })
            }
        })
        put("hearingEnabled", s.hearingEnabled)
        put("hearingRule", s.hearingRule.name)
        put("hearingStrength", s.hearingStrength)
        put("hearingSpeechShape", s.hearingSpeechShape)
        put("systemEq", s.systemEq)
        put("systemGlobal", s.systemGlobal)
        put("systemBands", s.systemBandsPerOctave)
    }.toString()

    private fun fromJson(text: String): PlaybackEq {
        val o = JSONObject(text)
        val defaults = PlaybackEq()
        fun <T> list(name: String, read: (JSONArray, Int) -> T): List<T>? =
            o.optJSONArray(name)?.let { array -> (0 until array.length()).map { read(array, it) } }

        val gains = list("geqGains") { a, i -> a.getDouble(i) }
            ?.takeIf { it.size == GraphicEq.OCTAVE.size || it.size == GraphicEq.THIRD_OCTAVE.size }
        val peq = list("peq") { a, i ->
            val b = a.getJSONObject(i)
            PeqBand(
                type = runCatching { EqFilterType.valueOf(b.getString("type")) }.getOrDefault(EqFilterType.PEAK),
                frequency = b.getDouble("f").coerceIn(20.0, 20000.0),
                gainDb = b.optDouble("g", 0.0),
                q = b.optDouble("q", 1.0).coerceIn(0.1, 20.0),
                enabled = b.optBoolean("on", true),
            )
        }
        val rules = list("autoRules") { a, i ->
            val r = a.getJSONObject(i)
            val from = r.optDouble("from", 20.0).coerceIn(20.0, 19000.0)
            AutoRule(
                id = r.getString("id"),
                kind = runCatching { RuleTargetKind.valueOf(r.getString("kind")) }.getOrDefault(RuleTargetKind.TARGET),
                ref = r.getString("ref"),
                fromHz = from,
                toHz = r.optDouble("to", 20000.0).coerceIn(from * 1.1, 20000.0),
            )
        }
        val tube = list("tube") { a, i ->
            val b = a.getJSONObject(i)
            TubeBand(b.getDouble("from"), b.getDouble("to"), b.optDouble("h2", TubeBand.OFF_DB), b.optDouble("h3", TubeBand.OFF_DB))
        }?.takeIf { it.size == TubeSettings.DEFAULT_BANDS.size }

        return PlaybackEq(
            enabled = o.optBoolean("enabled", defaults.enabled),
            preampDb = o.optDouble("preamp", 0.0),
            autoHeadroom = o.optBoolean("autoHeadroom", true),
            geqEnabled = o.optBoolean("geqEnabled", false),
            geqGains = gains ?: defaults.geqGains,
            peqEnabled = o.optBoolean("peqEnabled", false),
            peq = peq ?: defaults.peq,
            autoEnabled = o.optBoolean("autoEnabled", true),
            autoRules = rules ?: emptyList(),
            autoFullRange = o.optBoolean("autoFullRange", false),
            tubeEnabled = o.optBoolean("tubeEnabled", false),
            tube = tube ?: defaults.tube,
            hearingEnabled = o.optBoolean("hearingEnabled", false),
            hearingRule = runCatching { HearingRule.valueOf(o.getString("hearingRule")) }.getOrDefault(HearingRule.NAL_R),
            hearingStrength = o.optDouble("hearingStrength", 1.0).coerceIn(0.0, 1.5),
            hearingSpeechShape = o.optBoolean("hearingSpeechShape", false),
            systemEq = o.optBoolean("systemEq", false),
            systemGlobal = o.optBoolean("systemGlobal", false),
            systemBandsPerOctave = o.optInt("systemBands", defaults.systemBandsPerOctave)
                .takeIf { it in PlaybackEq.SYSTEM_BANDS } ?: defaults.systemBandsPerOctave,
        )
    }

    private companion object {
        const val SAVE_DELAY_MS = 400L
        const val REFIT_DELAY_MS = 150L
        const val SMOOTHING = 12

        /** A hearing loss is a broad shape; a few filters follow it, and it may ask for more than an earphone does. */
        val HEARING_FIT = PeqFitSettings(maxFilters = 6, maxBoostDb = 12.0, maxTotalBoostDb = 15.0)
    }
}
