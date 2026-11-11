package mmm.app.ui.eq

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import mmm.app.MmmApplication
import mmm.app.data.AutoFitState
import mmm.app.data.AutoRule
import mmm.app.data.EarphoneProfile
import mmm.app.data.PlaybackEq
import mmm.app.data.RuleTargetKind
import mmm.app.data.SquigClient
import mmm.app.ui.earphone.GraphCurve
import mmm.app.ui.earphone.ResponseGraph
import mmm.app.ui.theme.FilterChip
import mmm.app.ui.theme.GlassButton
import mmm.app.ui.theme.GroupDivider
import mmm.app.ui.theme.PillTabs
import mmm.app.ui.theme.Switch
import mmm.app.ui.theme.glass
import mmm.eq.EqProfile
import mmm.training.FrequencyRange
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The automatic EQ: the earphone in use, and the rules that say what it should sound like.
 *
 * A rule is "between these two frequencies, sound like that" - that being a target curve or
 * another earphone from the archive. Several rules are fitted together as one EQ, so an earphone
 * can take its vocals from one and its bass from another. With no rules, the EQ made on the
 * earphone's own page is used, which is the plain correction to its target.
 */
@Composable
internal fun AutoEqSection(
    app: MmmApplication,
    state: PlaybackEq,
    auto: AutoFitState,
    onOpenEarphones: () -> Unit,
    onFindEarphone: () -> Unit,
) {
    val earphones by app.earphones.earphones.collectAsStateWithLifecycle()
    val targets by app.earphones.targets.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val source = earphones.firstOrNull { it.id == settings.activeEarphoneId }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<AutoRule?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }

    fun nameOf(rule: AutoRule): String = when (rule.kind) {
        RuleTargetKind.TARGET -> rule.ref
        RuleTargetKind.EARPHONE -> earphones.firstOrNull { it.id == rule.ref }?.name ?: "지워진 이어폰"
    }

    Column(Modifier.fillMaxWidth().glass()) {
        Box {
            Row(
                Modifier.fillMaxWidth().clickable { choosing = true }.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("쓰는 이어폰", style = MaterialTheme.typography.bodySmall)
                    Text(source?.name ?: "고르기", style = MaterialTheme.typography.titleMedium)
                }
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = "이어폰 고르기")
            }
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                earphones.forEach { earphone ->
                    DropdownMenuItem(
                        text = { Text(earphone.name) },
                        onClick = { choosing = false; app.settings.setActiveEarphone(earphone.id) },
                    )
                }
                if (earphones.isNotEmpty()) HorizontalDivider()
                DropdownMenuItem(text = { Text("아카이브에서 이어폰 찾기") }, onClick = { choosing = false; onFindEarphone() })
                DropdownMenuItem(text = { Text("이어폰 보정 열기") }, onClick = { choosing = false; onOpenEarphones() })
            }
        }
        if (auto.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color.White, trackColor = Color.White.copy(alpha = 0.15f))
        Text(
            when {
                source == null -> "먼저 쓰는 이어폰을 고른다. 측정치는 earphonesarchive.squig.link에서 받는다."
                auto.fromEarphonePage -> "규칙이 없어 이어폰 보정에서 만든 EQ를 쓴다" +
                    (source.targetName?.let { " (타깃: $it)" } ?: "") + " · 필터 ${auto.filters.size}개"
                auto.rmsBeforeDb != null && auto.rmsAfterDb != null ->
                    "규칙 ${state.autoRules.size}개로 맞춤 · 필터 ${auto.filters.size}개 · 목표와의 차이 " +
                        "${db(auto.rmsBeforeDb)} → ${db(auto.rmsAfterDb)} dB"
                else -> "필터 ${auto.filters.size}개"
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 18.dp).padding(bottom = 12.dp),
        )
        auto.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 18.dp).padding(bottom = 12.dp),
            )
        }
    }

    Column(Modifier.fillMaxWidth().glass()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text("규칙", style = MaterialTheme.typography.titleMedium)
            Text(
                "\"이 대역에서는 저것처럼\" - 타깃 곡선이나 다른 이어폰을 대역별로 따라 한다. 여러 규칙은 한 번에 함께 맞춘다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.autoRules.forEach { rule ->
            GroupDivider()
            Row(
                Modifier.fillMaxWidth().clickable { editing = rule }.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("→ ${nameOf(rule)}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        (if (rule.kind == RuleTargetKind.EARPHONE) "이어폰" else "타깃") + " · " + FrequencyRange(rule.fromHz, rule.toHz).label,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
            }
        }
        GlassButton(
            text = "규칙 추가",
            onClick = {
                editing = AutoRule(
                    id = NEW_RULE,
                    kind = RuleTargetKind.TARGET,
                    ref = source?.targetName ?: targets.firstOrNull { "Harman" in it } ?: targets.firstOrNull().orEmpty(),
                )
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }

    Row(
        Modifier.fillMaxWidth().glass().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("20 kHz까지 상세 보정", style = MaterialTheme.typography.bodyLarge)
            Text(
                "끄면 10 kHz 위는 넓은 셸프 하나로만 맞춘다. 그 위의 좁은 봉우리는 꽂는 깊이와 귀마다 달라서, 규칙에만 적용된다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = state.autoFullRange, onCheckedChange = { on -> app.eq.update { it.copy(autoFullRange = on) } })
    }

    val measured = auto.measured
    if (measured != null) {
        Column(Modifier.fillMaxWidth().glass().padding(14.dp)) {
            ResponseGraph(
                listOfNotNull(
                    GraphCurve("측정 (${auto.sourceName.orEmpty()})", measured, Color.White.copy(alpha = 0.55f)),
                    auto.predicted?.let { GraphCurve("EQ 적용 후 예상", it, MaterialTheme.colorScheme.tertiary) },
                ),
            )
        }
    }
    if (auto.filters.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().glass().padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text("만들어진 필터", style = MaterialTheme.typography.titleMedium)
            Text(
                EqProfile(auto.filters, 0.0).toApoText().lines().drop(1).joinToString("\n").trim(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }

    editing?.let { rule ->
        RuleSheet(
            initial = rule,
            targets = targets,
            others = earphones.filter { it.id != source?.id && it.hasMeasurement },
            fetching = fetching,
            onFetchTargets = {
                fetching = true
                scope.launch {
                    runCatching {
                        val client = SquigClient()
                        for (name in SquigClient.DEFAULT_TARGETS) {
                            client.target(name)?.let { app.earphones.importTarget("$name.txt", it) }
                        }
                    }
                    fetching = false
                }
            },
            onFindEarphone = { editing = null; onFindEarphone() },
            onSave = { saved ->
                app.eq.update { s ->
                    val stored = if (saved.id == NEW_RULE) saved.copy(id = AutoRule(kind = saved.kind, ref = saved.ref).id) else saved
                    s.copy(
                        autoEnabled = true,
                        autoRules = if (s.autoRules.any { it.id == stored.id }) s.autoRules.map { if (it.id == stored.id) stored else it } else s.autoRules + stored,
                    )
                }
                editing = null
            },
            onDelete = if (rule.id == NEW_RULE) null else {
                { app.eq.update { s -> s.copy(autoRules = s.autoRules.filterNot { it.id == rule.id }) }; editing = null }
            },
            onDismiss = { editing = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RuleSheet(
    initial: AutoRule,
    targets: List<String>,
    others: List<EarphoneProfile>,
    fetching: Boolean,
    onFetchTargets: () -> Unit,
    onFindEarphone: () -> Unit,
    onSave: (AutoRule) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf(initial.kind) }
    var ref by remember { mutableStateOf(initial.ref) }
    var fromHz by remember { mutableStateOf(initial.fromHz) }
    var toHz by remember { mutableStateOf(initial.toHz) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1E1D25),
        contentColor = Color.White,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("규칙", style = MaterialTheme.typography.titleLarge)
            PillTabs(
                labels = listOf("타깃 곡선", "다른 이어폰"),
                selected = if (kind == RuleTargetKind.TARGET) 0 else 1,
                onSelect = { choice ->
                    val next = if (choice == 0) RuleTargetKind.TARGET else RuleTargetKind.EARPHONE
                    if (next != kind) {
                        kind = next
                        ref = ""
                    }
                },
                fill = true,
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (kind == RuleTargetKind.TARGET) {
                    targets.forEach { name ->
                        FilterChip(selected = ref == name, onClick = { ref = name }, label = { Text(name) })
                    }
                } else {
                    others.forEach { earphone ->
                        FilterChip(selected = ref == earphone.id, onClick = { ref = earphone.id }, label = { Text(earphone.name) })
                    }
                }
            }
            if (kind == RuleTargetKind.TARGET) {
                if (targets.isEmpty()) {
                    Text("받아 둔 타깃 곡선이 없다.", style = MaterialTheme.typography.bodySmall)
                    GlassButton(if (fetching) "받는 중…" else "타깃 받기 (Harman · JM-1)", onClick = onFetchTargets, enabled = !fetching)
                }
            } else {
                if (others.isEmpty()) {
                    Text("따라 할 이어폰이 아직 없다. 아카이브에서 찾아 추가하면 여기에 나온다.", style = MaterialTheme.typography.bodySmall)
                }
                GlassButton("아카이브에서 이어폰 찾기", onClick = onFindEarphone)
            }

            Text("대역", style = MaterialTheme.typography.titleMedium)
            Text(FrequencyRange(fromHz, toHz).label, style = MaterialTheme.typography.bodyLarge)
            RangeSlider(
                value = positionOf(fromHz)..positionOf(toHz),
                onValueChange = { next ->
                    if (next.endInclusive - next.start >= MIN_SPAN) {
                        fromHz = hzAt(next.start)
                        toHz = hzAt(next.endInclusive)
                    }
                },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.22f),
                ),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RANGE_PRESETS.forEach { (name, preset) ->
                    FilterChip(
                        selected = fromHz == preset.lowHz && toHz == preset.highHz,
                        onClick = {
                            fromHz = preset.lowHz
                            toHz = preset.highHz
                        },
                        label = { Text(name) },
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onDelete != null) GlassButton("삭제", onClick = onDelete, modifier = Modifier.weight(1f))
                GlassButton(
                    "저장",
                    onClick = { onSave(initial.copy(kind = kind, ref = ref, fromHz = fromHz, toHz = toHz)) },
                    modifier = Modifier.weight(1f),
                    primary = true,
                    enabled = ref.isNotBlank(),
                )
            }
        }
    }
}

private const val NEW_RULE = "new"

private val RANGE_PRESETS: List<Pair<String, FrequencyRange>> = listOf(
    "전 대역" to FrequencyRange(20.0, 20000.0),
    "저역 20–500" to FrequencyRange(20.0, 500.0),
    "보컬 500–8k" to FrequencyRange(500.0, 8000.0),
    "고역 8k–20k" to FrequencyRange(8000.0, 20000.0),
)

private const val LOW_HZ = 20.0
private const val HIGH_HZ = 20000.0
private val OCTAVES = ln(HIGH_HZ / LOW_HZ) / ln(2.0)

/** Half an octave, in slider units: a narrower rule is less than one filter can follow. */
private val MIN_SPAN = (0.5 / OCTAVES).toFloat()

private fun positionOf(hz: Double): Float = (ln(hz / LOW_HZ) / ln(2.0) / OCTAVES).toFloat().coerceIn(0f, 1f)

/** The slider's position as a frequency, rounded to two figures so the label reads as a number someone would say. */
private fun hzAt(position: Float): Double {
    val hz = LOW_HZ * 2.0.pow(position.coerceIn(0f, 1f) * OCTAVES)
    val magnitude = 10.0.pow(kotlin.math.floor(kotlin.math.log10(hz)) - 1)
    return ((hz / magnitude).roundToInt() * magnitude).coerceIn(LOW_HZ, HIGH_HZ)
}
