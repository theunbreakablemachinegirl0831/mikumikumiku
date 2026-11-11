package mmm.app.ui.hub

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.HearingDisabled
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mmm.app.BuildConfig
import mmm.app.MmmApplication
import mmm.app.data.FamilyProgress
import mmm.app.ui.theme.GroupCard
import mmm.app.ui.theme.GroupDivider
import mmm.app.ui.theme.LocalPanelInset
import mmm.app.ui.theme.PillTabs
import mmm.app.ui.theme.glass
import mmm.training.Curriculum
import mmm.training.ExerciseFamily

/** One thing to open from the hub: a test, an exercise, or one of the extras. */
private class HubItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    /** How far along, 0..1, or null for things that have no level. */
    val progress: Float? = null,
    val onClick: () -> Unit,
)

/**
 * The first tab: tests on one side, training on the other, one of which is picked from a list or
 * from cards. Nothing here is a setting - choosing an exercise opens its own page, which is
 * where its settings are.
 */
@Composable
fun HubScreen(
    app: MmmApplication,
    onOpenAudiometry: () -> Unit,
    onOpenEarphones: () -> Unit,
    onOpenHearing: () -> Unit,
    onOpenTraining: (ExerciseFamily) -> Unit,
    onOpenLive: () -> Unit,
    onOpenProgress: () -> Unit,
) {
    val progress by app.progress.progress.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val earphones by app.earphones.earphones.collectAsStateWithLifecycle()
    val earphone = earphones.firstOrNull { it.id == settings.activeEarphoneId }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val tests = listOf(
        HubItem(
            Icons.Rounded.Hearing,
            "순음 청력 검사",
            "주파수마다 들리는 가장 작은 소리를 찾아 청력도를 그린다",
            onClick = onOpenAudiometry,
        ),
        HubItem(
            Icons.Rounded.Headphones,
            "이어폰 보정",
            earphone?.let { "${it.name} · " + (it.targetName ?: "타깃 없음") } ?: "아카이브에서 측정치를 받아 타깃에 맞춘다",
            onClick = onOpenEarphones,
        ),
        HubItem(
            Icons.Rounded.HearingDisabled,
            "귀 응답 보정",
            "청력 검사 결과를 처방식(NAL-R 등)에 넣어 내 귀에 맞는 EQ를 만든다",
            onClick = onOpenHearing,
        ),
    )
    val training = Curriculum.families.map { family ->
        val levels = Curriculum.levels(family)
        val own = progress[family] ?: FamilyProgress()
        val level = own.level.coerceIn(levels.indices)
        HubItem(
            iconFor(family),
            family.displayName,
            buildString {
                append("Lv ").append(level + 1).append("/").append(levels.size)
                append(" · ").append(levels[level].name)
                if (own.trials > 0) append(" · 정답률 ").append((own.accuracy * 100).toInt()).append("%")
            },
            progress = (level + 1).toFloat() / levels.size,
            onClick = { onOpenTraining(family) },
        )
    }
    val extras = listOf(
        HubItem(Icons.Rounded.Usb, "실시간 모드 (USB DAC)", "Spotify 등에서 재생 중인 음악으로 훈련한다", onClick = onOpenLive),
        HubItem(Icons.Rounded.Insights, "진행도", "과제별 레벨과 정답률", onClick = onOpenProgress),
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("검사 · 훈련", style = MaterialTheme.typography.headlineMedium)
                Text("v${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_ID}", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { app.settings.setHubCards(!settings.hubCards) }) {
                Icon(
                    if (settings.hubCards) Icons.Rounded.ViewAgenda else Icons.Rounded.GridView,
                    contentDescription = if (settings.hubCards) "목록으로 보기" else "카드로 보기",
                )
            }
        }
        PillTabs(listOf("검사", "훈련"), tab, { tab = it }, fill = true)

        AnimatedContent(
            targetState = tab to settings.hubCards,
            transitionSpec = {
                if (initialState.first != targetState.first) {
                    val direction = if (targetState.first > initialState.first) 1 else -1
                    (fadeIn(tween(240)) + slideInHorizontally(tween(300)) { direction * it / 8 }) togetherWith fadeOut(tween(140))
                } else {
                    // Switching between list and cards: the same things settle into a new shape.
                    (fadeIn(tween(260)) + scaleIn(tween(300), initialScale = 0.96f)) togetherWith fadeOut(tween(120))
                }
            },
            label = "hub",
        ) { (current, cards) ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val items = if (current == 0) tests else training
                if (cards) CardGrid(items) else ItemList(items)
                if (current == 1) ItemList(extras)
            }
        }
        Spacer(Modifier.height(LocalPanelInset.current))
    }
}

@Composable
private fun ItemList(items: List<HubItem>) {
    GroupCard {
        items.forEachIndexed { index, item ->
            if (index > 0) GroupDivider()
            Row(
                Modifier.fillMaxWidth().clickable(onClick = item.onClick).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Bubble(item.icon)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(item.subtitle, style = MaterialTheme.typography.bodySmall)
                    item.progress?.let { Level(it) }
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
}

@Composable
private fun CardGrid(items: List<HubItem>) {
    items.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pair.forEach { item ->
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .glass()
                        .clickable(onClick = item.onClick)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Bubble(item.icon)
                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(item.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    item.progress?.let { Level(it) }
                }
            }
            // An odd one out keeps its half of the row.
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Bubble(icon: ImageVector) {
    Box(Modifier.size(44.dp).glass(CircleShape, strong = true), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun Level(fraction: Float) {
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth().height(4.dp),
        color = Color.White,
        trackColor = Color.White.copy(alpha = 0.15f),
        strokeCap = StrokeCap.Round,
    )
}

internal fun iconFor(family: ExerciseFamily): ImageVector = when (family) {
    ExerciseFamily.BAND_ID -> Icons.Rounded.Equalizer
    ExerciseFamily.RESONANCE -> Icons.Rounded.Sensors
    ExerciseFamily.BANDWIDTH -> Icons.Rounded.Tune
    ExerciseFamily.DISTORTION -> Icons.Rounded.BlurOn
    ExerciseFamily.COMPRESSION -> Icons.Rounded.Compress
    ExerciseFamily.REVERB -> Icons.Rounded.SurroundSound
    ExerciseFamily.SPECTRAL_BALANCE -> Icons.Rounded.GraphicEq
}
