package mmm.app.ui.exercise

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mmm.app.MmmApplication
import mmm.app.data.TrainerSettings
import mmm.app.live.LiveSession
import mmm.app.playback.StimulusPlayer
import mmm.app.source.LoadedSource
import mmm.app.source.StimulusSource
import mmm.dsp.SpectrumProfile
import mmm.eq.EqProfile
import mmm.training.Question
import mmm.training.render.StimulusRenderer

/**
 * How a question's stimuli reach the ear. The exercise itself - questions, answers, grading - is
 * the same whether the audio is a clip rendered in advance or music playing right now.
 */
internal interface StimulusBackend {
    /** Shown in the header, so it is always clear what is being listened to. */
    val sourceLabel: String

    /** What the audio has energy in, when that is known before the questions are made. */
    val spectrum: SpectrumProfile?

    /** Called once before the first question; may do slow work. */
    suspend fun open()

    /** Called for every question before any of its stimuli can be played. */
    suspend fun prepare(question: Question)

    fun play(stimulusId: String)

    fun stop()

    fun release()

    /** The earphone EQ, or null for none. */
    fun setCorrection(profile: EqProfile?)
}

/**
 * The file mode: every stimulus rendered from the same excerpt and matched offline, then played
 * with a switch that keeps the playhead where it is.
 */
internal class FileBackend(
    private val app: MmmApplication,
    private val settings: TrainerSettings,
) : StimulusBackend {

    private val player = StimulusPlayer()
    private var source: LoadedSource? = null

    override val sourceLabel: String
        get() = source?.let { s ->
            val start = s.startSeconds ?: return@let s.label
            val total = start.toInt()
            "${s.label} (${total / 60}:${(total % 60).toString().padStart(2, '0')}부터)"
        } ?: ""

    override val spectrum: SpectrumProfile? get() = source?.spectrum

    override suspend fun open() {
        source = withContext(Dispatchers.IO) {
            StimulusSource.load(app, settings.source, settings.excerptSeconds)
        }
    }

    override suspend fun prepare(question: Question) {
        val clip = source ?: return
        player.stop()
        val rendered = withContext(Dispatchers.Default) {
            StimulusRenderer.render(question, clip.audio, clip.sampleRate, settings.loudnessMatched)
        }
        player.load(rendered)
    }

    override fun play(stimulusId: String) = player.play(stimulusId)

    override fun stop() = player.stop()

    override fun release() = player.release()

    override fun setCorrection(profile: EqProfile?) =
        player.setCorrection(profile?.let { p -> { p.createProcessor() } })
}

/**
 * The live mode: the music never stops, and a stimulus is the processing currently applied to it.
 * With nothing selected the listener hears it untouched, which is what the unprocessed stimulus
 * would sound like anyway.
 *
 * Loudness matching here is the live, slewing kind - the offline two-pass match needs the whole
 * excerpt in advance, which live audio never provides.
 */
internal class LiveBackend(
    private val live: LiveSession,
    private val loudnessMatched: Boolean,
) : StimulusBackend {

    private var question: Question? = null

    override val sourceLabel: String get() = "실시간 (USB DAC)"

    /** Live music changes from bar to bar; there is no spectrum to plan questions around. */
    override val spectrum: SpectrumProfile? get() = null

    override suspend fun open() {
        check(live.capturing) { "실시간 캡처가 돌고 있지 않다. 실시간 모드 화면에서 먼저 캡처를 시작한다." }
    }

    override suspend fun prepare(question: Question) {
        live.setArtifact(null, loudnessMatched)
        this.question = question
    }

    override fun play(stimulusId: String) {
        val stimulus = question?.stimuli?.firstOrNull { it.id == stimulusId } ?: return
        live.setArtifact(stimulus.spec, loudnessMatched)
    }

    override fun stop() = live.setArtifact(null, loudnessMatched)

    override fun release() = stop()

    /** The live session applies the earphone EQ itself, for the capture as a whole. */
    override fun setCorrection(profile: EqProfile?) = Unit
}
