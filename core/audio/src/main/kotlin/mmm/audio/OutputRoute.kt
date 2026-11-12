package mmm.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Where the processed audio is sent, and therefore how we stop the listener hearing the *source*
 * app at the same time.
 *
 * `AudioPlaybackCapture` is a tap, not a redirect: capturing Spotify does not stop Spotify playing.
 * Without one of these strategies the learner hears the raw track and the processed track at once,
 * which makes every exercise meaningless. UAPP sidesteps this by taking exclusive control of a USB
 * DAC through its own driver, so Android's mixer is pushed to the phone speaker - that is [USB_DAC]
 * below, and it needs a native USB audio stack we do not have yet.
 *
 * Which of the others actually works is device-dependent, so the choice is exposed in settings and
 * the diagnostics screen measures it rather than assuming.
 */
public enum class OutputRoute(
    public val id: String,
    public val displayName: String,
    public val explanation: String,
) {
    /**
     * Plain media output. The source app is still audible, so this is only honest as a
     * "no separation" baseline - useful in the diagnostics build for comparison.
     */
    MEDIA(
        id = "media",
        displayName = "미디어 출력 (분리 없음)",
        explanation = "원음과 처리음이 함께 들린다. 비교용 기준선.",
    ),

    /**
     * Strategy A on the alarm stream.
     *
     * This is the one that survives Bluetooth. Alarms carry over A2DP like music does and have
     * their own volume slider, so the media stream can go to zero - silencing the source app -
     * while our output keeps playing at full quality on the same LDAC link.
     */
    ALARM(
        id = "alarm",
        displayName = "볼륨 분리 (알람 스트림) - 블루투스 권장",
        explanation = "미디어 볼륨을 0으로 내리고 처리음은 알람 스트림으로 내보낸다. " +
            "알람은 A2DP를 그대로 타므로 블루투스 DAC에서도 음질 손실이 없다.",
    ),

    /**
     * Strategy A on the system-sonification stream - the fallback for devices that force alarms
     * out of the speaker regardless of what is connected.
     */
    SYSTEM(
        id = "system",
        displayName = "볼륨 분리 (시스템 스트림)",
        explanation = "알람 스트림이 스피커로 새는 기기를 위한 대안. 역시 A2DP를 탄다.",
    ),

    /**
     * Strategy A on the voice-call stream.
     *
     * Kept for wired and speaker listening only. Over Bluetooth the call stream leaves A2DP for
     * HFP/SCO, which is narrowband mono - unusable for a listening test, and often silent unless
     * SCO has been started explicitly.
     */
    VOICE_CALL(
        id = "voice_call",
        displayName = "볼륨 분리 (음성통화 스트림) - 블루투스 비권장",
        explanation = "블루투스에서는 A2DP를 벗어나 HFP/SCO로 떨어져 협대역 모노가 된다. " +
            "유선/스피커 청취에서만 쓸 만하다.",
    ),

    /**
     * Strategy B: pin our output to a specific device (USB DAC, Bluetooth) and leave the source on
     * the built-in path.
     */
    PREFERRED_DEVICE(
        id = "preferred_device",
        displayName = "출력 장치 분리",
        explanation = "처리음만 USB DAC / 블루투스로 보내고 원음은 내장 출력에 남긴다.",
    ),

    /** Strategy C, not implemented yet: exclusive USB audio, the way UAPP does it. */
    USB_DAC(
        id = "usb_dac",
        displayName = "USB DAC 배타 점유 (미구현)",
        explanation = "UAPP과 같은 방식. 네이티브 USB 오디오 드라이버가 필요하다.",
    );

    public val implemented: Boolean get() = this != USB_DAC

    internal fun usage(): Int = when (this) {
        ALARM -> AudioAttributes.USAGE_ALARM
        SYSTEM -> AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
        VOICE_CALL -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        else -> AudioAttributes.USAGE_MEDIA
    }

    /**
     * Content type stays MUSIC even on the alarm and system routes. The usage is what picks the
     * stream and therefore the volume slider; the content type is a hint about the material, and
     * lying about it would invite unwanted processing on a listening-test signal.
     */
    internal fun contentType(): Int = when (this) {
        VOICE_CALL -> AudioAttributes.CONTENT_TYPE_SPEECH
        else -> AudioAttributes.CONTENT_TYPE_MUSIC
    }

    /** The stream whose volume slider governs this route, for the UI to drive. */
    public fun volumeStream(): Int = when (this) {
        ALARM -> AudioManager.STREAM_ALARM
        SYSTEM -> AudioManager.STREAM_SYSTEM
        VOICE_CALL -> AudioManager.STREAM_VOICE_CALL
        else -> AudioManager.STREAM_MUSIC
    }

    /**
     * True when this route puts our output on a stream other than the source's, which is what
     * makes muting the media stream silence the source alone.
     */
    public val separatesByStream: Boolean
        get() = this == ALARM || this == SYSTEM || this == VOICE_CALL

    /** Device types worth pinning to for [PREFERRED_DEVICE], best first. */
    public fun preferredDeviceTypes(): IntArray = when (this) {
        PREFERRED_DEVICE -> intArrayOf(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        )
        else -> IntArray(0)
    }

    public companion object {
        public fun fromId(id: String?): OutputRoute =
            entries.firstOrNull { it.id == id } ?: MEDIA
    }
}
