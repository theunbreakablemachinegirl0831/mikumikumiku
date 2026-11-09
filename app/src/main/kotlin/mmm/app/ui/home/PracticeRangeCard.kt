package mmm.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import mmm.app.ui.theme.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import mmm.app.ui.theme.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mmm.training.FrequencyRange
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Where Band ID and resonance questions are aimed: the whole spectrum, a named range, or one the
 * listener draws. The slider is logarithmic - an octave is the same distance anywhere on it - and
 * snaps to sixth-octave steps so the labels stay readable.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PracticeRangeCard(current: FrequencyRange?, onChange: (FrequencyRange?) -> Unit) {
    val presetName = current?.let { range -> FrequencyRange.PRESETS.firstOrNull { it.second == range }?.first }
    var custom by remember { mutableStateOf(current != null && presetName == null) }
    var sliderRange by remember(current) {
        val range = current ?: FrequencyRange(250.0, 4000.0)
        mutableStateOf(toPosition(range.lowHz)..toPosition(range.highHz))
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("연습 음역대", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = current == null,
                    onClick = { custom = false; onChange(null) },
                    label = { Text("전 대역") },
                )
                FrequencyRange.PRESETS.forEach { (name, range) ->
                    FilterChip(
                        selected = current == range,
                        onClick = { custom = false; onChange(range) },
                        label = { Text(name) },
                    )
                }
                FilterChip(
                    selected = custom,
                    onClick = {
                        custom = true
                        onChange(FrequencyRange(toHz(sliderRange.start), toHz(sliderRange.endInclusive)))
                    },
                    label = { Text("직접 지정") },
                )
            }

            if (custom) {
                Text(
                    "${FrequencyRange(toHz(sliderRange.start), toHz(sliderRange.endInclusive)).label}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                RangeSlider(
                    value = sliderRange,
                    onValueChange = { range ->
                        // Keep at least half an octave between the ends, or there is nothing to choose between.
                        if (range.endInclusive - range.start >= MIN_SPAN) sliderRange = range
                    },
                    onValueChangeFinished = {
                        onChange(FrequencyRange(toHz(sliderRange.start), toHz(sliderRange.endInclusive)))
                    },
                    valueRange = 0f..1f,
                )
            }

            Text(
                when {
                    current == null -> "밴드 식별과 레조넌스 문제가 전체 스펙트럼에서 나온다."
                    else -> "밴드 식별과 레조넌스 문제가 ${current.label} 안에서만 나온다. " +
                        "레벨의 격자 촘촘함과 게인은 그대로 쓰고, 범위만 좁힌다."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private const val LOW_HZ = 20.0
private const val HIGH_HZ = 20000.0
private const val STEPS_PER_OCTAVE = 6
private val OCTAVES = ln(HIGH_HZ / LOW_HZ) / ln(2.0)

/** Half an octave, in slider units. */
private val MIN_SPAN = (0.5 / OCTAVES).toFloat()

private fun toPosition(hz: Double): Float = (ln(hz / LOW_HZ) / ln(2.0) / OCTAVES).toFloat().coerceIn(0f, 1f)

private fun toHz(position: Float): Double {
    val octaves = (position * OCTAVES * STEPS_PER_OCTAVE).roundToInt().toDouble() / STEPS_PER_OCTAVE
    return LOW_HZ * 2.0.pow(octaves)
}
