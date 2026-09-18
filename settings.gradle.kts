pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mikumikumiku"

// Platform-independent core: pure Kotlin/JVM, no Android SDK required.
// These are the modules that hold the listening-test DSP and the training engine,
// so they can be unit-tested on any machine (and on CI without an Android SDK).
include(":core:dsp")
include(":core:training")

// The Android side is only configured when an SDK is actually present, so that
// `gradle :core:dsp:test` works in a bare JDK container.
val androidSdkPresent =
    System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

if (androidSdkPresent) {
    include(":core:audio")
    include(":source:file")
    include(":source:capture")
    include(":app")
} else {
    logger.lifecycle(
        "[mikumikumiku] No Android SDK found - configuring pure-JVM modules only " +
            "(:core:dsp, :core:training). Set ANDROID_HOME or create local.properties for the full build."
    )
}
