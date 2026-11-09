package mmm.app.audiometry

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mmm.training.audiometry.AudiogramResult
import mmm.training.audiometry.Ear
import mmm.training.audiometry.ThresholdPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Keeps the most recent hearing test, as JSON in app storage. */
class AudiogramStore(context: Context) {

    private val file = File(context.filesDir, "audiogram.json")

    suspend fun save(result: AudiogramResult) = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("falseAlarms", result.falseAlarms)
            put("catchTrials", result.catchTrials)
            put("earphone", result.earphoneName ?: JSONObject.NULL)
            put("timestamp", result.timestampMillis)
            put("retest", JSONObject().apply { result.retestDifferenceDb.forEach { (ear, db) -> put(ear.name, db) } })
            put("points", JSONArray().apply {
                result.points.forEach { p ->
                    put(JSONObject().apply {
                        put("ear", p.ear.name)
                        put("hz", p.frequencyHz)
                        put("dbfs", p.thresholdDbfs ?: JSONObject.NULL)
                        put("n", p.presentations)
                    })
                }
            })
        }
        file.writeText(json.toString())
    }

    suspend fun load(): AudiogramResult? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        runCatching {
            val json = JSONObject(file.readText())
            val points = json.getJSONArray("points")
            val retest = json.getJSONObject("retest")
            AudiogramResult(
                points = (0 until points.length()).map { i ->
                    val p = points.getJSONObject(i)
                    ThresholdPoint(
                        ear = Ear.valueOf(p.getString("ear")),
                        frequencyHz = p.getDouble("hz"),
                        thresholdDbfs = if (p.isNull("dbfs")) null else p.getDouble("dbfs"),
                        presentations = p.getInt("n"),
                    )
                },
                falseAlarms = json.getInt("falseAlarms"),
                catchTrials = json.getInt("catchTrials"),
                retestDifferenceDb = Ear.entries.filter { retest.has(it.name) }.associateWith { retest.getDouble(it.name) },
                earphoneName = if (json.isNull("earphone")) null else json.getString("earphone"),
                timestampMillis = json.getLong("timestamp"),
            )
        }.getOrNull()
    }
}
