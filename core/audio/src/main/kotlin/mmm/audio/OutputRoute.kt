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
     * Strategy A: play out on the voice-call stream so the media stream can be turned down to zero.
     * Whether this works hinges on whether capture happens before or after the media stream volume
     * is applied - if it is after, muting the source also mutes what we capture.
     */
    VOICE_CALL(
        id = "voice_call",
        displayName = "볼륨 분리 (음성통화 스트림)",
        explanation = "미디어 볼륨을 0으로 내리고 처리음만 통화 스트림으로 내보낸다.",
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
        VOICE_CALL -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        else -> AudioAttributes.USAGE_MEDIA
    }

    internal fun contentType(): Int = when (this) {
        VOICE_CALL -> AudioAttributes.CONTENT_TYPE_SPEECH
        else -> AudioAttributes.CONTENT_TYPE_MUSIC
    }

    /** The stream whose volume slider governs this route, for the UI to drive. */
    public fun volumeStream(): Int = when (this) {
        VOICE_CALL -> AudioManager.STREAM_VOICE_CALL
        else -> AudioManager.STREAM_MUSIC
    }

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
