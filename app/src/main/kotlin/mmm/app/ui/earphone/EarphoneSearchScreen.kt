package mmm.app.ui.earphone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mmm.app.MmmApplication
import mmm.app.data.SquigClient
import mmm.app.data.SquigEntry
import mmm.app.ui.theme.ScreenHeader
import java.util.Locale

data class SearchUiState(
    val loading: Boolean = true,
    val busy: String? = null,
    val error: String? = null,
    val query: String = "",
    val results: List<SquigEntry> = emptyList(),
    val total: Int = 0,
)

/**
 * Finds an earphone in the squig.link database and sets it up in one go: the profile, its
 * measurement (both channels, averaged), and the 5128 targets if the listener has none yet.
 */
class EarphoneSearchViewModel(private val app: MmmApplication) : ViewModel() {

    private val client = SquigClient()
    private var book: List<SquigEntry> = emptyList()

    private val _ui = MutableStateFlow(SearchUiState())
    val ui: StateFlow<SearchUiState> = _ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            runCatching { client.phoneBook() }
                .onSuccess { entries ->
                    book = entries
                    _ui.value = _ui.value.copy(loading = false, total = entries.size)
                    search(_ui.value.query)
                }
                .onFailure {
                    _ui.value = _ui.value.copy(
                        loading = false,
                        error = "측정 목록을 받지 못했다: ${it.message ?: it::class.simpleName}. 인터넷 연결을 확인한다.",
                    )
                }
        }
    }

    /** Every word of the query has to appear in the brand, model or variant, in any order. */
    fun search(query: String) {
        val words = query.lowercase(Locale.ROOT).split(' ').filter { it.isNotBlank() }
        val results = if (words.isEmpty()) {
            emptyList()
        } else {
            book.filter { entry ->
                val haystack = "${entry.brand} ${entry.model} ${entry.variant.orEmpty()} ${entry.file}".lowercase(Locale.ROOT)
                words.all { it in haystack }
            }.take(MAX_RESULTS)
        }
        _ui.value = _ui.value.copy(query = query, results = results)
    }

    fun add(entry: SquigEntry, onDone: (String) -> Unit) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = "${entry.displayName} 측정치를 받는 중", error = null)
            try {
                val channels = client.measurements(entry)
                if (channels.isEmpty()) {
                    _ui.value = _ui.value.copy(busy = null, error = "이 항목의 측정 파일을 받지 못했다")
                    return@launch
                }
                val profile = app.earphones.create(entry.displayName)
                app.earphones.importMeasurement(profile.id, channels)
                app.earphones.update(
                    (app.earphones.profile(profile.id) ?: profile).copy(note = "squig.link: ${entry.file}")
                )

                if (app.earphones.targets.value.isEmpty()) {
                    _ui.value = _ui.value.copy(busy = "타깃을 받는 중")
                    for (name in SquigClient.DEFAULT_TARGETS) {
                        client.target(name)?.let { app.earphones.importTarget("$name.txt", it) }
                    }
                }
                _ui.value = _ui.value.copy(busy = null)
                onDone(profile.id)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(busy = null, error = "받지 못했다: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    companion object {
        private const val MAX_RESULTS = 100

        fun factory(app: MmmApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer { EarphoneSearchViewModel(app) }
        }
    }
}

@Composable
fun EarphoneSearchScreen(app: MmmApplication, onBack: () -> Unit, onAdded: (String) -> Unit) {
    val vm: EarphoneSearchViewModel = viewModel(factory = EarphoneSearchViewModel.factory(app))
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ScreenHeader(title = "측정치 찾기", onBack = onBack)
            Text(
                "earphonesarchive.squig.link의 B&K 5128 측정치에서 찾는다." +
                    if (ui.total > 0) " (${ui.total}개)" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = ui.query,
                onValueChange = vm::search,
                label = { Text("이어폰 이름 (예: bravo, airpods pro)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !ui.loading && ui.busy == null,
            )

            ui.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = vm::load) { Text("다시 시도") }
            }
            val busy = ui.busy ?: if (ui.loading) "측정 목록을 받는 중" else null
            if (busy != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text(busy)
                }
            }
            if (!ui.loading && ui.query.isNotBlank() && ui.results.isEmpty()) {
                Text("찾는 이어폰이 없다. 다른 철자나 브랜드 이름으로 찾아본다.", style = MaterialTheme.typography.bodySmall)
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(ui.results) { entry ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = ui.busy == null) { vm.add(entry, onAdded) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text("${entry.brand} ${entry.model}", style = MaterialTheme.typography.bodyLarge)
                        entry.variant?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
