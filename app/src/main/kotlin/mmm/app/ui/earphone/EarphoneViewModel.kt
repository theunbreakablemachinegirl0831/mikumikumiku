package mmm.app.ui.earphone

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mmm.app.MmmApplication
import mmm.app.data.EarphoneProfile
import mmm.eq.FrequencyResponse
import mmm.eq.PeqFitResult
import mmm.eq.PeqFitSettings
import mmm.eq.PeqFitter

data class EarphoneUiState(
    val busy: String? = null,
    val message: String? = null,
    /** Measurement, target and prediction, all normalised to 0 dB at 500 Hz-1 kHz. */
    val measured: FrequencyResponse? = null,
    val target: FrequencyResponse? = null,
    val fit: PeqFitResult? = null,
)

/**
 * One earphone's page: import its measurement, choose a target, fit and keep an EQ.
 *
 * The fit runs whenever its inputs change rather than behind a button, so the graph always shows
 * what the current choices would do; it is a second or so of work, done off the main thread.
 */
class EarphoneViewModel(private val app: MmmApplication, private val id: String) : ViewModel() {

    private val _ui = MutableStateFlow(EarphoneUiState())
    val ui: StateFlow<EarphoneUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { refit() }
    }

    fun profile(): EarphoneProfile? = app.earphones.profile(id)

    fun rename(name: String) = edit { it.copy(name = name) }

    fun setConnection(profile: EarphoneProfile) = edit { profile }

    fun setNote(note: String) = edit { it.copy(note = note) }

    fun chooseTarget(name: String) = edit(refit = true) { it.copy(targetName = name) }

    fun setTilt(dbPerOctave: Double) = edit(refit = true) { it.copy(tiltDbPerOctave = dbPerOctave) }

    fun setFullRange(on: Boolean) = edit(refit = true) { it.copy(fullRange = on) }

    fun importMeasurement(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = "측정 파일을 읽는 중")
            val texts = withContext(Dispatchers.IO) { uris.mapNotNull(::readText) }
            val read = app.earphones.importMeasurement(id, texts)
            _ui.value = _ui.value.copy(
                busy = null,
                message = if (read == 0) "측정 파일에서 주파수/레벨 쌍을 찾지 못했다" else "측정 파일 ${read}개를 평균해 저장했다",
            )
            refit()
        }
    }

    fun importTarget(uri: Uri, displayName: String) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { readText(uri) }
            val name = text?.let { app.earphones.importTarget(displayName, it) }
            if (name == null) {
                _ui.value = _ui.value.copy(message = "타깃 파일을 읽지 못했다")
            } else {
                chooseTarget(name)
            }
        }
    }

    fun use() {
        app.settings.setActiveEarphone(id)
        app.settings.setEqEnabled(true)
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            if (app.settings.current().activeEarphoneId == id) app.settings.setActiveEarphone(null)
            app.earphones.delete(id)
            onDone()
        }
    }

    private fun edit(refit: Boolean = false, change: (EarphoneProfile) -> EarphoneProfile) {
        val current = profile() ?: return
        viewModelScope.launch {
            app.earphones.update(change(current))
            if (refit) refit()
        }
    }

    private suspend fun refit() {
        val profile = profile() ?: return
        val measured = app.earphones.measurement(id)
        val targetName = profile.targetName ?: app.earphones.targets.value.firstOrNull { "Harman" in it }
            ?: app.earphones.targets.value.firstOrNull()
        val rawTarget = targetName?.let { app.earphones.target(it) }
        if (measured == null || rawTarget == null) {
            _ui.value = _ui.value.copy(
                measured = measured?.smooth(12)?.normalized(),
                target = rawTarget?.normalized(),
                fit = null,
            )
            return
        }
        if (profile.targetName == null) app.earphones.update(profile.copy(targetName = targetName))

        _ui.value = _ui.value.copy(busy = "EQ를 계산하는 중")
        val target = rawTarget.tilted(profile.tiltDbPerOctave)
        val settings = if (profile.fullRange) PeqFitSettings.FULL_RANGE else PeqFitSettings.DEFAULT
        val fit = withContext(Dispatchers.Default) { PeqFitter(settings).fit(measured, target) }
        // Kept straight away: the EQ in use is always the one on screen.
        app.earphones.update((profile() ?: profile).copy(eqText = fit.profile.toApoText()))
        _ui.value = _ui.value.copy(
            busy = null,
            measured = measured.smooth(12).normalized(),
            target = target.normalized(),
            fit = fit,
        )
    }

    private fun readText(uri: Uri): String? = runCatching {
        app.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
    }.getOrNull()

    companion object {
        fun factory(app: MmmApplication, id: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { EarphoneViewModel(app, id) }
        }
    }
}
