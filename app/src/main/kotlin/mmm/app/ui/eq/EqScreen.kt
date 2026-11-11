package mmm.app.ui.eq

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mmm.app.MmmApplication
import mmm.app.data.PeqBand
import mmm.app.data.PlaybackEq
import mmm.app.sessioneq.SessionEqService
import mmm.app.ui.theme.GlassButton
import mmm.app.ui.theme.LocalPanelInset
import mmm.app.ui.theme.PillTabs
import mmm.app.ui.theme.RoundChip
import mmm.app.ui.theme.Switch
import mmm.app.ui.theme.WhiteSlider
import mmm.app.ui.theme.glass
import mmm.app.ui.visual.PlayerSpectrumSource
import mmm.app.ui.visual.VisualizerSpectrumSource
import mmm.app.ui.visual.rememberSpectrum
import mmm.eq.EqFilterType
import mmm.eq.EqProfile
import mmm.eq.EqStack
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

private val TABS = listOf("GEQ", "PEQ", "AutoEQ", "음장")
private const val TAB_GEQ = 0
private const val TAB_PEQ = 1
private const val TAB_AUTO = 2
private const val TAB_TUBE = 3

/**
 * The playback EQ: one master switch, and under it the three layers that add up - graphic,
 * parametric and automatic - plus the valve harmonics. The graph at the top always shows the sum
 * of everything switched on, whichever layer is being edited below it.
 */
@Composable
fun EqScreen(app: MmmApplication, onOpenEarphones: () -> Unit, onFindEarphone: () -> Unit) {
    val state by app.eq.state.collectAsStateWithLifecycle()
    val auto by app.eq.auto.collectAsStateWithLifecycle()
    val hearing by app.eq.hearing.collectAsStateWithLifecycle()
    val profile by app.eq.profile.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val now by app.player.state.collectAsStateWithLifecycle()
    val sessions by SessionEqService.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var tab by rememberSaveable { mutableIntStateOf(TAB_PEQ) }
    var band by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }

    // The player's own audio when it is playing; otherwise whatever other app the EQ is attached to.
    val external = !now.isPlaying && sessions.sessions.any { it.error == null }
    val spectrum = rememberSpectrum(
        source = if (external) VisualizerSpectrumSource else PlayerSpectrumSource,
        playing = now.isPlaying || external,
        points = settings.visualizerPoints,
    )

    val layers = remember(state, auto.filters, hearing.filters) { app.eq.layerFilters(state, auto.filters, hearing.filters) }
    val total = remember(layers) { layers.flatten() }
    val editing = when (tab) {
        TAB_GEQ -> state.geq.filters(state.geqGains)
        TAB_PEQ -> state.peq.filter { it.enabled }.map(PeqBand::toFilter)
        TAB_AUTO -> auto.filters
        else -> null
    }

    val importEq = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } }.getOrNull()
            val parsed = text?.let { EqProfile.parseApo(it) }
            if (parsed != null && parsed.filters.isNotEmpty()) {
                app.eq.update { it.copy(peqEnabled = true, peq = parsed.filters.take(PlaybackEq.MAX_PEQ_BANDS).map(::bandOf)) }
                tab = TAB_PEQ
                band = 0
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("재생 EQ", style = MaterialTheme.typography.headlineMedium)
                Text(
                    when {
                        !state.enabled -> "꺼짐 - 원래 소리 그대로 재생한다"
                        profile == null -> "켜짐 - 걸린 필터가 없다"
                        else -> "필터 ${total.size}개 · 프리앰프 ${db(profile?.preampDb ?: 0.0)} dB" +
                            (if (state.hearingEnabled) " · 청력 보정 포함" else "") +
                            (if (state.systemEq) " · 다른 앱에도 적용" else "")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreHoriz, contentDescription = "더 보기") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("EQ 파일 가져오기 (APO · AutoEq) → PEQ") },
                        onClick = { menu = false; importEq.launch(arrayOf("text/*", "application/octet-stream")) },
                    )
                    DropdownMenuItem(
                        text = { Text("자동 EQ를 PEQ로 복사") },
                        enabled = auto.filters.isNotEmpty(),
                        onClick = {
                            menu = false
                            app.eq.update {
                                it.copy(peqEnabled = true, autoEnabled = false, peq = auto.filters.take(PlaybackEq.MAX_PEQ_BANDS).map(::bandOf))
                            }
                            tab = TAB_PEQ
                            band = 0
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("지금 EQ를 APO 텍스트로 복사") },
                        enabled = profile != null,
                        onClick = {
                            menu = false
                            profile?.let { clipboard.setText(AnnotatedString(it.toApoText())) }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("PEQ 초기화") },
                        onClick = { menu = false; app.eq.update { it.copy(peq = PlaybackEq.DEFAULT_PEQ) }; band = 0 },
                    )
                    DropdownMenuItem(
                        text = { Text("GEQ 초기화") },
                        onClick = { menu = false; app.eq.update { it.copy(geqGains = List(it.geqGains.size) { 0.0 }) } },
                    )
                }
            }
            Switch(checked = state.enabled, onCheckedChange = { on -> app.eq.update { it.copy(enabled = on) } })
        }

        PillTabs(TABS, tab, { tab = it }, fill = true)

        Column(Modifier.fillMaxWidth().glass()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (tab) {
                        TAB_GEQ -> "그래픽 EQ 사용"
                        TAB_PEQ -> "파라메트릭 EQ 사용"
                        TAB_AUTO -> "자동 EQ 사용"
                        else -> "진공관 배음 사용"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = when (tab) {
                        TAB_GEQ -> state.geqEnabled
                        TAB_PEQ -> state.peqEnabled
                        TAB_AUTO -> state.autoEnabled
                        else -> state.tubeEnabled
                    },
                    onCheckedChange = { on ->
                        app.eq.update {
                            when (tab) {
                                TAB_GEQ -> it.copy(geqEnabled = on)
                                TAB_PEQ -> it.copy(peqEnabled = on)
                                TAB_AUTO -> it.copy(autoEnabled = on)
                                else -> it.copy(tubeEnabled = on)
                            }
                        }
                    },
                )
            }
            EqGraph(
                total = total,
                spectrum = spectrum,
                layer = editing,
                active = state.enabled,
                nodes = if (tab == TAB_PEQ) state.peq else emptyList(),
                selectedNode = band,
                onSelectNode = { band = it },
                onMoveNode = { index, frequency, gain ->
                    app.eq.update { it.withBand(index) { b -> b.copy(frequency = roundHz(frequency), gainDb = roundTenth(gain)) } }
                },
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val direction = if (targetState > initialState) 1 else -1
                (fadeIn(tween(240)) + slideInHorizontally(tween(300)) { direction * it / 10 }) togetherWith fadeOut(tween(140))
            },
            label = "eq-tab",
        ) { current ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (current) {
                    TAB_GEQ -> GeqSection(state, app.eq::update)
                    TAB_PEQ -> PeqSection(state, band.coerceIn(0, (state.peq.size - 1).coerceAtLeast(0)), { band = it }, app.eq::update)
                    TAB_AUTO -> AutoEqSection(app, state, auto, onOpenEarphones, onFindEarphone)
                    else -> TubeSection(state, app.eq::update)
                }
            }
        }

        if (tab != TAB_TUBE) PreampCard(state, profile, total, app.eq::update)
        Spacer(Modifier.height(LocalPanelInset.current))
    }
}

/** The reference's band editor: numbered chips, then the chosen band's type and three sliders. */
@Composable
private fun PeqSection(state: PlaybackEq, selected: Int, onSelect: (Int) -> Unit, update: ((PlaybackEq) -> PlaybackEq) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.peq.forEachIndexed { index, band ->
            RoundChip(selected = index == selected, onClick = { onSelect(index) }, dimmed = !band.enabled) {
                Text((index + 1).toString(), fontWeight = FontWeight.SemiBold)
            }
        }
        if (state.peq.size < PlaybackEq.MAX_PEQ_BANDS) {
            RoundChip(
                selected = false,
                onClick = {
                    update { it.copy(peq = it.peq + PeqBand(frequency = 1000.0)) }
                    onSelect(state.peq.size)
                },
            ) { Icon(Icons.Rounded.Add, contentDescription = "대역 추가") }
        }
    }

    val band = state.peq.getOrNull(selected)
    if (band == null) {
        Text("대역이 없다. +를 눌러 추가한다.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(Modifier.fillMaxWidth().glass().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("대역 ${selected + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TypeMenu(band.type) { type -> update { it.withBand(selected) { b -> b.copy(type = type) } } }
        }
        LabelledSlider(
            label = "주파수",
            value = hz(band.frequency),
            position = (ln(band.frequency / 20.0) / ln(1000.0)).toFloat(),
            onChange = { p -> update { it.withBand(selected) { b -> b.copy(frequency = roundHz(20.0 * 1000.0.pow(p.toDouble()))) } } },
        )
        LabelledSlider(
            label = "게인",
            value = "${signed(band.gainDb)} dB",
            position = band.gainDb.toFloat(),
            range = -PlaybackEq.GAIN_LIMIT_DB.toFloat()..PlaybackEq.GAIN_LIMIT_DB.toFloat(),
            onChange = { g -> update { it.withBand(selected) { b -> b.copy(gainDb = roundTenth(g.toDouble())) } } },
        )
        LabelledSlider(
            label = "Q",
            value = String.format(Locale.ROOT, if (band.q < 1) "%.2f" else "%.1f", band.q),
            position = (ln(band.q / Q_MIN) / ln(Q_MAX / Q_MIN)).toFloat(),
            onChange = { p ->
                update { it.withBand(selected) { b -> b.copy(q = (Q_MIN * (Q_MAX / Q_MIN).pow(p.toDouble()) * 100).roundToInt() / 100.0) } }
            },
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(
                text = if (band.enabled) "끄기" else "켜기",
                onClick = { update { it.withBand(selected) { b -> b.copy(enabled = !b.enabled) } } },
                modifier = Modifier.weight(1f),
            )
            GlassButton(
                text = "삭제",
                onClick = {
                    update { it.copy(peq = it.peq.filterIndexed { i, _ -> i != selected }) }
                    onSelect((selected - 1).coerceAtLeast(0))
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TypeMenu(type: EqFilterType, onChange: (EqFilterType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clickable { open = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(typeLabel(type), style = MaterialTheme.typography.titleMedium)
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = "필터 종류")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            EqFilterType.entries.forEach { option ->
                DropdownMenuItem(text = { Text(typeLabel(option)) }, onClick = { open = false; onChange(option) })
            }
        }
    }
}

@Composable
internal fun LabelledSlider(
    label: String,
    value: String,
    position: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        WhiteSlider(
            value = position.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}

/** The listener's own gain, with what the automatic headroom adds to it spelled out. */
@Composable
private fun PreampCard(
    state: PlaybackEq,
    profile: EqProfile?,
    total: List<mmm.eq.EqFilter>,
    update: ((PlaybackEq) -> PlaybackEq) -> Unit,
) {
    Column(Modifier.fillMaxWidth().glass().padding(horizontal = 18.dp, vertical = 10.dp)) {
        LabelledSlider(
            label = "프리앰프",
            value = "${db(state.preampDb)} dB",
            position = state.preampDb.toFloat(),
            range = -15f..6f,
            onChange = { v -> update { it.copy(preampDb = (v * 2).roundToInt() / 2.0) } },
        )
        val headroom = remember(total) { EqStack.headroomFor(total) }
        Text(
            when {
                !state.autoHeadroom -> "자동 프리앰프가 꺼져 있다. 부스트가 크면 소리가 찢어질 수 있다."
                headroom > 0 -> "부스트만큼 자동으로 ${db(-headroom)} dB 더 내려서 찢어지지 않게 한다 → 실제 ${db(profile?.preampDb ?: state.preampDb)} dB"
                else -> "지금 EQ에는 부스트가 없어 자동으로 내릴 것이 없다."
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

internal fun PlaybackEq.withBand(index: Int, change: (PeqBand) -> PeqBand): PlaybackEq =
    copy(peq = peq.mapIndexed { i, band -> if (i == index) change(band) else band })

private fun bandOf(filter: mmm.eq.EqFilter): PeqBand =
    PeqBand(filter.type, filter.frequency, roundTenth(filter.gainDb), (filter.q * 100).roundToInt() / 100.0)

private fun typeLabel(type: EqFilterType): String = when (type) {
    EqFilterType.PEAK -> "Peak"
    EqFilterType.LOW_SHELF -> "Low shelf"
    EqFilterType.HIGH_SHELF -> "High shelf"
}

private const val Q_MIN = 0.1
private const val Q_MAX = 10.0

internal fun roundTenth(value: Double): Double = (value * 10).roundToInt() / 10.0

/** Three significant figures: 1 Hz steps in the bass, 10 Hz around 1 kHz, 100 Hz at the top. */
internal fun roundHz(frequency: Double): Double {
    val f = frequency.coerceIn(20.0, 20000.0)
    val step = when {
        f < 100 -> 1.0
        f < 1000 -> 5.0
        f < 10000 -> 50.0
        else -> 100.0
    }
    return (f / step).roundToInt() * step
}

internal fun hz(frequency: Double): String =
    if (frequency >= 1000) {
        val k = (frequency / 10).roundToInt() / 100.0
        (if (k == kotlin.math.floor(k)) k.toInt().toString() else k.toString()) + " kHz"
    } else {
        "${frequency.roundToInt()} Hz"
    }

internal fun db(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

internal fun signed(value: Double): String = (if (value > 0) "+" else "") + db(value)
