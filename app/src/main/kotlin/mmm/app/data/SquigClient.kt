package mmm.app.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/** One measurement on a squig.link site: a model, or one variant of it (ear tips, filters). */
data class SquigEntry(
    val brand: String,
    val model: String,
    /** The variant's file stem; "<stem> L.txt" and "<stem> R.txt" are the two channels. */
    val file: String,
    /** What tells this variant apart from its siblings, or null for a model with one measurement. */
    val variant: String?,
) {
    val displayName: String get() = "$brand $model" + (variant?.let { " ($it)" } ?: "")
}

/**
 * Reads the measurement database behind a squig.link graph site.
 *
 * Every squig site serves the same layout: `data/phone_book.json` lists brands and models with
 * their file stems, the channels are `data/<stem> L.txt` and `R.txt`, targets live under
 * `data/targets/`, and `assets/js/config.js` names the targets the site offers. Only
 * earphonesarchive is used by default, because it measures on the B&K 5128 - mixing rigs would
 * make the target and the measurement disagree about what flat is.
 */
class SquigClient(private val baseUrl: String = DEFAULT_SITE) {

    suspend fun phoneBook(): List<SquigEntry> = withContext(Dispatchers.IO) {
        val brands = JSONArray(get("data/phone_book.json") ?: error("측정 목록을 받지 못했다"))
        buildList {
            for (b in 0 until brands.length()) {
                val brand = brands.getJSONObject(b)
                val brandName = brand.optString("name")
                val phones = brand.optJSONArray("phones") ?: continue
                for (p in 0 until phones.length()) {
                    val phone = phones.getJSONObject(p)
                    val model = phone.optString("name")
                    val files = phone.optJSONArray("file")
                    if (files == null) {
                        add(SquigEntry(brandName, model, phone.optString("file"), null))
                    } else {
                        val prefix = phone.optString("prefix", "")
                        for (f in 0 until files.length()) {
                            val file = files.getString(f)
                            val variant = file.removePrefix(prefix).trim().removeSurrounding("(", ")").ifBlank { null }
                            add(SquigEntry(brandName, model, file, variant ?: if (files.length() > 1) "기본" else null))
                        }
                    }
                }
            }
        }
    }

    /** The channel files that exist for [entry] - usually both, sometimes only one. */
    suspend fun measurements(entry: SquigEntry): List<String> = withContext(Dispatchers.IO) {
        listOf("L", "R").mapNotNull { channel -> get("data/" + Uri.encode("${entry.file} $channel.txt")) }
    }

    /** Names of the targets the site offers, from its config; a known list if that cannot be read. */
    suspend fun targetNames(): List<String> = withContext(Dispatchers.IO) {
        val config = get("assets/js/config.js")
        val listed = config?.let { text ->
            TARGET_LISTS.findAll(text)
                .flatMap { QUOTED.findAll(it.groupValues[1]).map { q -> q.groupValues[1] } }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
        }
        listed?.takeIf { it.isNotEmpty() } ?: FALLBACK_TARGETS
    }

    /** A target's curve as text, or null if the site does not have it. */
    suspend fun target(name: String): String? = withContext(Dispatchers.IO) {
        get("data/targets/" + Uri.encode("$name Target.txt")) ?: get("data/targets/" + Uri.encode("$name.txt"))
    }

    private fun get(path: String): String? {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: FileNotFoundException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val DEFAULT_SITE = "https://earphonesarchive.squig.link/"
        private const val TIMEOUT_MS = 15000

        private val TARGET_LISTS = Regex("""files\s*:\s*\[([^\]]*)\]""")
        private val QUOTED = Regex(""""([^"]+)"""")

        /** What earphonesarchive offered when this was written, in case its config moves. */
        val FALLBACK_TARGETS = listOf("Harman IE 2019 for 5128", "JM-1 (5128)", "5128 DF")

        /** Fetched for a new earphone when the listener has no targets yet. */
        val DEFAULT_TARGETS = listOf("Harman IE 2019 for 5128", "JM-1 (5128)")
    }
}
