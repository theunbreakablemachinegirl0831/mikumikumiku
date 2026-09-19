package mmm.training

/**
 * The listening skills the trainer covers, mirroring the task families of Harman's
 * "How to Listen": where a spectral change sits, how narrow it is, where the band edges are,
 * how non-linear, how squashed, how reverberant, and how the overall balance is tilted.
 */
public enum class ExerciseFamily(
    public val id: String,
    public val displayName: String,
    public val skill: String,
) {
    BAND_ID("band_id", "Band identification", "Naming the frequency region of a spectral change"),
    RESONANCE("resonance", "Resonance detection", "Hearing narrow, ringing peaks"),
    BANDWIDTH("bandwidth", "Bandwidth", "Judging where the response rolls off"),
    DISTORTION("distortion", "Distortion", "Detecting non-linearity"),
    COMPRESSION("compression", "Dynamics", "Hearing dynamic-range reduction"),
    REVERB("reverb", "Reverberation", "Judging decay time and wet level"),
    SPECTRAL_BALANCE("spectral_balance", "Spectral balance", "Judging broad tonal tilt"),
}

/** How a question is put to the learner. */
public enum class QuestionFormat {
    /** One processed stimulus, many labelled answers: "which band was boosted?" */
    IDENTIFY,

    /** Several stimuli, one of which is processed: "which one is not clean?" */
    DETECT,

    /** A reference plus candidates: "which one matches the reference?" */
    MATCH,

    /** Several stimuli to be put in order of artifact strength. */
    RANK,
}

/**
 * Something the learner can play.
 *
 * It carries only the *processing*; where the audio itself comes from - a bundled clip, the
 * learner's own file, or the live captured stream - is decided by the playback layer.
 */
public data class Stimulus(
    val id: String,
    val label: String,
    val spec: ArtifactSpec,
)

/** One selectable answer. */
public data class Choice(
    val id: String,
    val label: String,
    /**
     * Where this choice sits on the underlying continuum (band index, cutoff in Hz, amount...).
     * Used to give partial credit for near misses instead of a flat right/wrong.
     */
    val ordinal: Double? = null,
)

/** A generated trial. */
public data class Question(
    val id: String,
    val family: ExerciseFamily,
    val format: QuestionFormat,
    val prompt: String,
    val stimuli: List<Stimulus>,
    val choices: List<Choice>,
    val correctChoiceIds: List<String>,
    val level: Int,
    /** Only set for [QuestionFormat.MATCH]: the stimulus the candidates are compared against. */
    val reference: Stimulus? = null,
    /** Shown after answering, e.g. "that was +9 dB at 500 Hz". */
    val explanation: String = "",
) {
    /** True when the order of [correctChoiceIds] matters, i.e. ranking questions. */
    public val ordered: Boolean get() = format == QuestionFormat.RANK
}

/** What the learner picked. */
public data class Answer(
    val questionId: String,
    val chosenChoiceIds: List<String>,
    val elapsedMs: Long = 0,
    /** How many times they played any stimulus before committing. */
    val playCount: Int = 0,
)

/** The verdict on one trial. */
public data class Grade(
    val correct: Boolean,
    /** 0..1. Near misses on an ordered scale score above zero. */
    val credit: Double,
    val explanation: String,
    /** For ordinal families, how far off the answer was, in choice steps. */
    val distance: Double? = null,
)
