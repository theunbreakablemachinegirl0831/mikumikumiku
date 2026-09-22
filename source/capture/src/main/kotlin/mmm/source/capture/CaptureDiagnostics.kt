package mmm.source.capture

/** What the diagnostics screen concluded, and what the user should do about it. */
public data class CaptureVerdict(
    val status: Status,
    val headline: String,
    val detail: String,
    val suggestions: List<String> = emptyList(),
) {
    public enum class Status { UNKNOWN, WAITING, HEALTHY, SILENT, DEGRADED, FAILED }
}

/**
 * Turns raw telemetry into a verdict.
 *
 * The case worth all this ceremony is the silent one. When an app sets its capture policy to
 * refuse, `AudioRecord` still opens, still reads, and still returns success - it just returns
 * zeroes. Nothing in the API says "this app said no", so the only way to report it is to watch the
 * meter for long enough to rule out a paused track and then say so plainly.
 */
public object CaptureDiagnostics {

    /** Below this the ring buffer is losing enough audio to be audible as dropouts. */
    private const val UNDERRUN_FRAME_BUDGET = 4800L

    public fun evaluate(
        state: CaptureState,
        telemetry: CaptureTelemetry,
        runningMs: Long,
        sourceAppLabel: String? = null,
    ): CaptureVerdict = when (state) {
        is CaptureState.Idle -> CaptureVerdict(
            status = CaptureVerdict.Status.UNKNOWN,
            headline = "대기 중",
            detail = "캡처를 시작하면 신호가 들어오는지 확인한다.",
        )

        is CaptureState.Starting -> CaptureVerdict(
            status = CaptureVerdict.Status.WAITING,
            headline = "시작하는 중",
            detail = "캡처 세션을 여는 중이다.",
        )

        is CaptureState.Failed -> CaptureVerdict(
            status = CaptureVerdict.Status.FAILED,
            headline = "캡처 실패",
            detail = state.reason,
            suggestions = listOf(
                "캡처 권한을 다시 허용해 본다.",
                "다른 화면 녹화/캐스팅 앱이 이미 세션을 잡고 있지 않은지 확인한다.",
            ),
        )

        is CaptureState.Running -> evaluateRunning(telemetry, runningMs, sourceAppLabel)
    }

    private fun evaluateRunning(
        telemetry: CaptureTelemetry,
        runningMs: Long,
        sourceAppLabel: String?,
    ): CaptureVerdict {
        telemetry.error?.let {
            return CaptureVerdict(
                status = CaptureVerdict.Status.FAILED,
                headline = "출력 오류",
                detail = it,
            )
        }

        if (!telemetry.sawSignal) {
            if (runningMs < CaptureTelemetry.SILENCE_VERDICT_MS) {
                return CaptureVerdict(
                    status = CaptureVerdict.Status.WAITING,
                    headline = "신호를 기다리는 중",
                    detail = "재생 중인 앱에서 소리가 나오고 있는지 확인한다.",
                )
            }
            val who = sourceAppLabel ?: "재생 중인 앱"
            return CaptureVerdict(
                status = CaptureVerdict.Status.SILENT,
                headline = "캡처는 열렸지만 소리가 없다",
                detail = "${who}이(가) 내부 오디오 캡처를 차단하고 있을 가능성이 높다. " +
                    "차단하는 앱은 오류 대신 무음을 돌려주기 때문에 이렇게 보인다.",
                suggestions = listOf(
                    "해당 앱에서 실제로 재생 중인지, 볼륨이 0이 아닌지 확인한다.",
                    "브라우저의 웹 플레이어로 재생해 본다 — 브라우저는 보통 캡처를 허용한다.",
                    "다른 앱(예: 로컬 음악 플레이어)으로 캡처가 되는지 먼저 확인해 본다.",
                ),
            )
        }

        if (telemetry.underrunFrames > UNDERRUN_FRAME_BUDGET ||
            telemetry.overrunFrames > UNDERRUN_FRAME_BUDGET
        ) {
            return CaptureVerdict(
                status = CaptureVerdict.Status.DEGRADED,
                headline = "끊김이 발생하고 있다",
                detail = "언더런 ${telemetry.underrunFrames} / 오버런 ${telemetry.overrunFrames} 프레임. " +
                    "버퍼가 모자라거나 기기가 따라오지 못하고 있다.",
                suggestions = listOf(
                    "설정에서 버퍼 크기를 키운다.",
                    "배터리 절약 모드를 끈다.",
                ),
            )
        }

        return CaptureVerdict(
            status = CaptureVerdict.Status.HEALTHY,
            headline = "정상 동작 중",
            detail = "입력 ${format(telemetry.inputRmsDb)} dBFS · 출력 ${format(telemetry.outputRmsDb)} dBFS" +
                if (telemetry.levelCorrectionDb != 0.0) {
                    " · 라우드니스 보정 ${format(telemetry.levelCorrectionDb)} dB"
                } else "",
        )
    }

    private fun format(db: Double): String =
        if (db <= -120.0) "-∞" else String.format("%.1f", db)
}
