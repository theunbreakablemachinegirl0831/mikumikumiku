package mmm.app.ui.audiometry

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import mmm.app.ui.theme.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mmm.app.MmmApplication
import mmm.app.audiometry.AudiometryProgress
import mmm.app.audiometry.AudiometryRunner
import mmm.app.ui.theme.ScreenHeader
import mmm.eq.EarphoneCalibration
import mmm.training.audiometry.AudiogramCalibration
import mmm.training.audiometry.AudiogramResult
import mmm.training.audiometry.AudiometryProtocol
import mmm.training.audiometry.Ear
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln

data class AudiometryUiState(
    val running: Boolean = false,
    val progress: AudiometryProgress? = null,
    val result: AudiogramResult? = null,
    /** Relative hearing level per (ear, frequency), when a result exists. */
    val levels: Map<Pair<Ear, Double>, Double> = emptyMap(),
    /** False when no earphone measurement was available, so the earphone is still in the result. */
    val calibrated: Boolean = false,
    val error: String? = null,
)

class AudiometryViewModel(private val app: MmmApplication) : ViewModel() {

    private val _ui = MutableStateFlow(AudiometryUiState())
    val ui: StateFlow<AudiometryUiState> = _ui.asStateFlow()

    private var runner: AudiometryRunner? = null
    private var job: Job? = null

    init {
        viewModelScope.launch { app.audiograms.load()?.let { show(it) } }
    }

    fun start() {
        if (job?.isActive == true) return
        val earphone = app.earphones.profile(app.settings.settings.value.activeEarphoneId ?: "")
        val test = AudiometryRunner(onProgress = { _ui.value = _ui.value.copy(progress = it) })
        runner = test
        _ui.value = _ui.value.copy(running = true, error = null, progress = null)
        job = viewModelScope.launch(Dispatchers.Default) {
            try {
                val result = test.run(earphone?.name)
                app.audiograms.save(result)
                show(result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(error = "검사를 진행하지 못했다: ${e.message ?: e::class.simpleName}")
            } finally {
                _ui.value = _ui.value.copy(running = false, progress = null)
            }
        }
    }

    fun press() {
        runner?.press()
    }

    fun stop() {
        job?.cancel()
    }

    private suspend fun show(result: AudiogramResult) {
        val earphoneId = app.settings.settings.value.activeEarphoneId
        val measured = earphoneId?.let { app.earphones.measurement(it) }
        val calibration = measured?.let { EarphoneCalibration(it) }
        val levels = AudiogramCalibration.relativeHearingLevels(result) { f -> calibration?.relativeGainDb(f) ?: 0.0 }
        _ui.value = _ui.value.copy(result = result, levels = levels, calibrated = calibration != null)
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(app: MmmApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer { AudiometryViewModel(app) }
        }
    }
}

@Composable
fun AudiometryScreen(app: MmmApplication, onBack: (() -> Unit)?) {
    val vm: AudiometryViewModel = viewModel(factory = AudiometryViewModel.factory(app))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val earphones by app.earphones.earphones.collectAsStateWithLifecycle()
    val report by app.live.usb.report.collectAsStateWithLifecycle()
    val earphone = earphones.firstOrNull { it.id == settings.activeEarphoneId }
    val dacClaimed = report.claim?.claimed == true
    val context = LocalContext.current

    DisposableEffect(Unit) { onDispose { vm.stop() } }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader(title = "청력 검사", onBack = onBack?.let { back -> { vm.stop(); back() } })

            if (ui.running) {
                RunningContent(ui.progress, onPress = vm::press, onStop = vm::stop)
                return@Column
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("준비", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "조용한 곳에서 이어폰을 깊게 꽂는다. 한쪽 귀씩 '삐-삐-삐' 소리가 작게 났다 커졌다 한다. " +
                            "아무리 작아도 들렸다고 느끼면 바로 큰 버튼을 누른다. 양쪽 귀 9개 주파수, 10분 남짓 걸린다.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "미디어 볼륨: ${mediaVolume(context)} - 검사 중에도, 다음 검사 때도 같은 볼륨으로 둔다. " +
                            "50% 안팎이 적당하다. 이어폰 EQ는 검사 중에 걸리지 않는다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (earphone != null) "이어폰: ${earphone.name} - 이 이어폰의 측정치로 결과를 보정한다."
                        else "고른 이어폰이 없다. 결과에 이어폰 특성이 섞인다 - 설정 → 이어폰 보정에서 먼저 고르면 좋다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "의료 검사가 아니다. 결과가 이상하면 이비인후과에서 정식 청력 검사를 받는다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (dacClaimed) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("실시간 모드가 USB DAC을 점유하고 있다. 지금은 검사 소리가 폰 스피커로 나간다.")
                        OutlinedButton(onClick = { app.live.release() }) { Text("점유 해제") }
                    }
                }
            }

            Button(onClick = vm::start, modifier = Modifier.fillMaxWidth(), enabled = !dacClaimed) {
                Text(if (ui.result == null) "검사 시작" else "다시 검사")
            }
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            ui.result?.let { ResultContent(it, ui.levels, ui.calibrated) }
        }
    }
}

@Composable
private fun RunningContent(progress: AudiometryProgress?, onPress: () -> Unit, onStop: () -> Unit) {
    Text(
        progress?.let { "${it.ear.label} 귀 · ${hz(it.frequencyHz)} (${it.step}/${it.steps})" } ?: "준비 중",
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        "소리는 화면에 표시되지 않는다. 귀로만 판단해서, 들릴 때마다 누른다.",
        style = MaterialTheme.typography.bodySmall,
    )
    Button(
        onClick = onPress,
        modifier = Modifier.fillMaxWidth().height(220.dp),
    ) { Text("들렸다!", fontSize = 32.sp) }
    TextButton(onClick = onStop) { Text("검사 중단") }
}

@Composable
private fun ResultContent(result: AudiogramResult, levels: Map<Pair<Ear, Double>, Double>, calibrated: Boolean) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("결과", style = MaterialTheme.typography.titleMedium)
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(result.timestampMillis)) +
                    (result.earphoneName?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            AudiogramChart(levels)
            Text(
                "1 kHz 기준 상대 청력 레벨(근사 dB HL): 아래로 갈수록 그 주파수가 1 kHz보다 덜 들린다. " +
                    "O = 오른쪽, X = 왼쪽. 절대 청력(dB HL)은 보정된 장비가 있어야 알 수 있다." +
                    if (calibrated) "" else " 이어폰 측정치가 없어 이어폰 특성이 결과에 섞여 있다.",
                style = MaterialTheme.typography.bodySmall,
            )
            Ear.entries.forEach { ear ->
                Text(
                    "${ear.label}: " + AudiometryProtocol.CHART_FREQUENCIES.joinToString("  ") { f ->
                        val level = levels[ear to f]
                        "${hz(f)} " + (level?.let { String.format(Locale.ROOT, "%+.0f", it) } ?: "—")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                buildString {
                    append(if (result.reliable) "응답 신뢰도: 양호" else "응답 신뢰도: 낮음 - 다시 검사하는 것이 좋다")
                    append(" · 무음 구간에서 누름 ${result.falseAlarms}회 (무음 제시 ${result.catchTrials}회)")
                    result.retestDifferenceDb.forEach { (ear, db) ->
                        append(" · ${ear.label} 1 kHz 재검 차이 ${db.toInt()} dB")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The audiogram convention: frequency across, hearing level down, right ear red O, left ear blue X. */
@Composable
private fun AudiogramChart(levels: Map<Pair<Ear, Double>, Double>) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val axis = MaterialTheme.colorScheme.outline
    val frequencies = AudiometryProtocol.CHART_FREQUENCIES
    val values = levels.values
    val top = minOf(-10.0, (values.minOrNull() ?: 0.0) - 5)
    val bottom = maxOf(30.0, (values.maxOrNull() ?: 0.0) + 5)

    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        val minLog = ln(frequencies.first() / 1.3)
        val maxLog = ln(frequencies.last() * 1.3)
        fun x(f: Double) = ((ln(f) - minLog) / (maxLog - minLog) * size.width).toFloat()
        fun y(db: Double) = ((db - top) / (bottom - top) * size.height).toFloat()

        frequencies.forEach { f -> drawLine(grid, Offset(x(f), 0f), Offset(x(f), size.height), 1f) }
        var db = kotlin.math.ceil(top / 10) * 10
        while (db <= bottom) {
            drawLine(if (db == 0.0) axis else grid, Offset(0f, y(db)), Offset(size.width, y(db)), 1f)
            db += 10
        }

        for (ear in Ear.entries) {
            val color = if (ear == Ear.RIGHT) RIGHT else LEFT
            val points = frequencies.mapNotNull { f -> levels[ear to f]?.let { Offset(x(f), y(it)) } }
            points.zipWithNext { a, b -> drawLine(color, a, b, 3f) }
            points.forEach { p ->
                if (ear == Ear.RIGHT) {
                    drawCircle(color, radius = 9f, center = p, style = Stroke(3f))
                } else {
                    drawLine(color, Offset(p.x - 8f, p.y - 8f), Offset(p.x + 8f, p.y + 8f), 3f)
                    drawLine(color, Offset(p.x - 8f, p.y + 8f), Offset(p.x + 8f, p.y - 8f), 3f)
                }
            }
        }
    }
    Text(
        frequencies.joinToString(" · ") { hz(it) } + " · 세로 눈금 10 dB",
        style = MaterialTheme.typography.labelSmall,
    )
}

private val RIGHT = Color(0xFFE53935)
private val LEFT = Color(0xFF1E88E5)

private fun hz(f: Double): String = if (f >= 1000) "${(f / 1000).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }}k" else "${f.toInt()}"

private fun mediaVolume(context: Context): String {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    return "$current/$max"
}
