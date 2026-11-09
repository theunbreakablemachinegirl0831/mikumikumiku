package mmm.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mmm.eq.EqProfile
import mmm.eq.FrequencyResponse
import java.io.File
import java.util.Locale
import java.util.Properties
import java.util.UUID

/** How an earphone reaches the phone. Live mode needs USB: only a claimed DAC keeps the original out. */
enum class EarphoneConnection(val label: String) {
    USB("유선 (USB DAC)"),
    BLUETOOTH("블루투스"),
}

data class EarphoneProfile(
    val id: String,
    val name: String,
    val connection: EarphoneConnection = EarphoneConnection.USB,
    /** Anything that changes the response and has to match the measurement: ear tips, ANC mode. */
    val note: String = "",
    val targetName: String? = null,
    val tiltDbPerOctave: Double = 0.0,
    /** Detail correction up to 20 kHz instead of 10 kHz. */
    val fullRange: Boolean = false,
    val hasMeasurement: Boolean = false,
    /** The EQ in Equalizer APO text, or null before one has been made. */
    val eqText: String? = null,
    /**
     * True when [eqText] came from an imported file (AutoEq's ParametricEQ.txt, say) rather than
     * from fitting here, so changing the target does not silently replace it.
     */
    val importedEq: Boolean = false,
) {
    val eq: EqProfile? get() = eqText?.let { EqProfile.parseApo(it) }
}

/**
 * Earphones and target curves the listener has imported, kept as plain files in app storage.
 *
 * Measurements and targets come from public measurement sites and belong to them, so the app ships
 * none: the listener imports the text files (squig.link and AutoEq both serve them), and they stay
 * on the phone. Each earphone is a folder - its settings as a properties file, the measurement as
 * text, the fitted EQ as Equalizer APO text that can be copied out as is.
 */
class EarphoneRepository(context: Context, private val scope: CoroutineScope) {

    private val root = File(context.filesDir, "earphones").apply { mkdirs() }
    private val targetRoot = File(context.filesDir, "targets").apply { mkdirs() }

    private val _earphones = MutableStateFlow<List<EarphoneProfile>>(emptyList())
    val earphones: StateFlow<List<EarphoneProfile>> = _earphones.asStateFlow()

    private val _targets = MutableStateFlow<List<String>>(emptyList())
    val targets: StateFlow<List<String>> = _targets.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { reload() }
    }

    fun profile(id: String): EarphoneProfile? = _earphones.value.firstOrNull { it.id == id }

    suspend fun create(name: String): EarphoneProfile = withContext(Dispatchers.IO) {
        val profile = EarphoneProfile(id = UUID.randomUUID().toString().take(8), name = name)
        writeMeta(profile)
        reload()
        profile
    }

    suspend fun update(profile: EarphoneProfile) = withContext(Dispatchers.IO) {
        writeMeta(profile)
        reload()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        reload()
    }

    /**
     * Stores the average of [texts] as the earphone's measurement - squig.link serves left and
     * right channels as separate files, and either alone carries that unit's channel imbalance.
     * @return how many of the files could be read
     */
    suspend fun importMeasurement(id: String, texts: List<String>): Int = withContext(Dispatchers.IO) {
        val responses = texts.mapNotNull { runCatching { FrequencyResponse.parse(it) }.getOrNull() }
        if (responses.isEmpty()) return@withContext 0
        val average = FrequencyResponse.average(responses)
        File(folder(id), MEASUREMENT).writeText(toText(average))
        profile(id)?.let { if (!it.importedEq) writeMeta(it.copy(eqText = null)) }
        reload()
        responses.size
    }

    suspend fun measurement(id: String): FrequencyResponse? = withContext(Dispatchers.IO) {
        File(folder(id), MEASUREMENT).takeIf { it.exists() }?.let { FrequencyResponse.parse(it.readText()) }
    }

    /** @return the name the target was stored under, or null if the text held no curve */
    suspend fun importTarget(fileName: String, text: String): String? = withContext(Dispatchers.IO) {
        val response = runCatching { FrequencyResponse.parse(text) }.getOrNull() ?: return@withContext null
        val name = fileName.substringBeforeLast('.').removeSuffix(" Target").trim().ifEmpty { "타깃" }
        File(targetRoot, "$name.txt").writeText(toText(response))
        reload()
        name
    }

    suspend fun target(name: String): FrequencyResponse? = withContext(Dispatchers.IO) {
        File(targetRoot, "$name.txt").takeIf { it.exists() }?.let { FrequencyResponse.parse(it.readText()) }
    }

    suspend fun deleteTarget(name: String) = withContext(Dispatchers.IO) {
        File(targetRoot, "$name.txt").delete()
        reload()
    }

    private fun folder(id: String) = File(root, id).apply { mkdirs() }

    private fun writeMeta(profile: EarphoneProfile) {
        val folder = folder(profile.id)
        val properties = Properties().apply {
            setProperty("name", profile.name)
            setProperty("connection", profile.connection.name)
            setProperty("note", profile.note)
            profile.targetName?.let { setProperty("target", it) }
            setProperty("tilt", profile.tiltDbPerOctave.toString())
            setProperty("fullRange", profile.fullRange.toString())
            setProperty("importedEq", profile.importedEq.toString())
        }
        File(folder, META).outputStream().use { properties.store(it, null) }
        val eqFile = File(folder, EQ)
        if (profile.eqText == null) eqFile.delete() else eqFile.writeText(profile.eqText)
    }

    private fun reload() {
        val profiles = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { folder ->
            val metaFile = File(folder, META)
            if (!metaFile.exists()) return@mapNotNull null
            val p = Properties().apply { metaFile.inputStream().use { load(it) } }
            EarphoneProfile(
                id = folder.name,
                name = p.getProperty("name") ?: folder.name,
                connection = runCatching { EarphoneConnection.valueOf(p.getProperty("connection")) }
                    .getOrDefault(EarphoneConnection.USB),
                note = p.getProperty("note").orEmpty(),
                targetName = p.getProperty("target"),
                tiltDbPerOctave = p.getProperty("tilt")?.toDoubleOrNull() ?: 0.0,
                fullRange = p.getProperty("fullRange")?.toBoolean() ?: false,
                importedEq = p.getProperty("importedEq")?.toBoolean() ?: false,
                hasMeasurement = File(folder, MEASUREMENT).exists(),
                eqText = File(folder, EQ).takeIf { it.exists() }?.readText(),
            )
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
        _earphones.value = profiles
        _targets.value = targetRoot.listFiles().orEmpty()
            .filter { it.extension == "txt" }
            .map { it.nameWithoutExtension }
            .sorted()
    }

    private fun toText(response: FrequencyResponse): String = buildString {
        for (i in 0 until response.size) {
            append(String.format(Locale.ROOT, "%.3f\t%.4f\n", response.frequencies[i], response.decibels[i]))
        }
    }

    private companion object {
        const val META = "earphone.properties"
        const val MEASUREMENT = "measurement.txt"
        const val EQ = "eq.txt"
    }
}
