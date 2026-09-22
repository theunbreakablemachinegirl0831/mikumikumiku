package mmm.app.ui.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mmm.app.MmmApplication
import mmm.app.ui.theme.ScreenHeader
import mmm.training.Curriculum
import mmm.training.ExerciseFamily
import mmm.training.Question
import mmm.training.TrialResult

@Composable
fun ExerciseScreen(app: MmmApplication, family: ExerciseFamily, live: Boolean, onBack: () -> Unit) {
    val vm: ExerciseViewModel = viewModel(
        key = "exercise-${family.id}-${if (live) "live" else "file"}",
        factory = ExerciseViewModel.factory(app, family, live),
    )
    val state by vm.ui.collectAsStateWithLifecycle()

    // Audio must not carry on after the learner has left the screen or put the phone down.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.stopPlayback() }
    DisposableEffect(Unit) { onDispose { vm.stopPlayback() } }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader(
                title = family.displayName,
                onBack = onBack,
                subtitle = if (state.question != null) {
                    "Lv ${state.level + 1}/${state.levelCount} · ${state.levelName} · 음원: ${state.sourceLabel}"
                } else {
                    null
                },
            )

            val error = state.error
            val question = state.question
            when {
                error != null -> ErrorCard(error)
                question == null -> BusyCard(state.busy ?: "준비 중")
                else -> QuestionContent(state, question, vm)
            }
        }
    }
}

@Composable
private fun QuestionContent(state: ExerciseUiState, question: Question, vm: ExerciseViewModel) {
    if (state.busy != null) {
        BusyCard(state.busy)
        return
    }

    Text(state.levelDescription, style = MaterialTheme.typography.bodySmall)
    state.filterNote?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    Text(question.prompt, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

    StimulusRow(question, state.playingId, state.live, vm)
    ChoiceGrid(question, state, vm)

    val result = state.result
    if (result == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::submit, enabled = state.canSubmit) { Text("제출") }
            if (question.ordered) {
                OutlinedButton(onClick = vm::undo, enabled = state.picks.isNotEmpty()) { Text("되돌리기") }
            }
        }
        if (question.ordered) {
            Text(
                "누른 순서가 곧 답이다. 다시 누르면 순서에서 빠진다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    } else {
        ResultCard(question, result)
        Button(onClick = vm::next, modifier = Modifier.fillMaxWidth()) { Text("다음 문제") }
    }

    SessionLine(state)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StimulusRow(question: Question, playingId: String?, live: Boolean, vm: ExerciseViewModel) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("듣기", style = MaterialTheme.typography.titleSmall)
            // Wraps rather than scrolls: ranking questions have four stimuli plus the stop button,
            // which does not fit one row on a narrow phone.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                question.stimuli.forEach { stimulus ->
                    val playing = stimulus.id == playingId
                    if (playing) {
                        Button(onClick = { vm.toggleStimulus(stimulus.id) }) { Text("▶ ${stimulus.label}") }
                    } else {
                        FilledTonalButton(onClick = { vm.toggleStimulus(stimulus.id) }) { Text(stimulus.label) }
                    }
                }
                if (playingId != null) {
                    TextButton(onClick = vm::stopPlayback) { Text(if (live) "■ 원음으로" else "■ 정지") }
                }
            }
            Text(
                if (live) {
                    "지금 흐르는 음악에 바로 걸린다. 아무것도 누르지 않은 상태가 원음이다."
                } else {
                    "재생 중에 다른 버튼을 누르면 같은 위치에서 바로 바뀐다. 같은 마디끼리 비교하면 된다."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceGrid(question: Question, state: ExerciseUiState, vm: ExerciseViewModel) {
    val result = state.result
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        question.choices.forEach { choice ->
            val picked = choice.id in state.picks
            val rank = if (question.ordered) vm.rankOf(choice.id) else null
            val label = if (rank != null) "$rank. ${choice.label}" else choice.label

            val colors = when {
                result == null && picked -> ButtonDefaults.buttonColors()
                result == null -> null
                // After grading: the right answer is always shown, and a wrong pick is marked so
                // the learner can see how far off it was rather than just that it was wrong.
                !question.ordered && choice.id in question.correctChoiceIds ->
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    )
                !question.ordered && picked ->
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                picked -> ButtonDefaults.buttonColors()
                else -> null
            }

            if (colors != null) {
                Button(onClick = { vm.tapChoice(choice.id) }, colors = colors) { Text(label) }
            } else {
                OutlinedButton(onClick = { vm.tapChoice(choice.id) }, enabled = result == null) { Text(label) }
            }
        }
    }
}

@Composable
private fun ResultCard(question: Question, result: TrialResult) {
    val grade = result.grade
    val (headline, tint) = when {
        grade.correct -> "정답!" to MaterialTheme.colorScheme.tertiaryContainer
        grade.credit > 0.0 -> "근접 - 부분 점수 ${(grade.credit * 100).toInt()}%" to
            MaterialTheme.colorScheme.secondaryContainer
        else -> "오답" to MaterialTheme.colorScheme.errorContainer
    }

    Card(colors = CardDefaults.cardColors(containerColor = tint)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            val labels = question.choices.associate { it.id to it.label }
            val correct = question.correctChoiceIds.mapNotNull(labels::get)
            Text(
                if (question.ordered) "정답 순서: ${correct.joinToString(" → ")}"
                else "정답: ${correct.joinToString()}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(question.explanation, style = MaterialTheme.typography.bodyMedium)

            val levels = Curriculum.levels(question.family)
            when {
                result.leveledUp -> Text(
                    "레벨 업! 이제 Lv ${result.levelAfter + 1}: ${levels[result.levelAfter].name}",
                    fontWeight = FontWeight.Bold,
                )
                result.leveledDown -> Text(
                    "한 단계 내려왔다: Lv ${result.levelAfter + 1} ${levels[result.levelAfter].name}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SessionLine(state: ExerciseUiState) {
    val stats = state.stats
    if (stats.trials == 0) return
    Text(
        "이번 세션: 정답 ${stats.correct}/${stats.trials} · 연속 ${stats.streak} (최고 ${stats.bestStreak})",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun BusyCard(message: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Text(message)
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(message, modifier = Modifier.padding(16.dp))
    }
}
