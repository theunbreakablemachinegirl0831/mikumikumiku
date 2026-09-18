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
    // Only modules that actually exist on disk: the trainer's UI module is still being built, and
    // listing it before it is there would fail configuration for everything else - including the
    // diagnostics APK, which is the one artifact that has to keep building.
    listOf(
        ":core:audio",
        ":source:file",
        ":source:capture",
        ":app",
        // Standalone measurement build. It installs alongside the trainer and depends only on the
        // capture stack, so the live-audio questions can be settled on real hardware without
        // waiting for the rest of the app to be finished.
        ":tools:diagnostics",
    ).forEach { path ->
        val directory = file(path.removePrefix(":").replace(':', '/'))
        if (directory.resolve("build.gradle.kts").exists()) {
            include(path)
        } else {
            logger.lifecycle("[mikumikumiku] Skipping $path - not present yet.")
        }
    }
} else {
    logger.lifecycle(
        "[mikumikumiku] No Android SDK found - configuring pure-JVM modules only " +
            "(:core:dsp, :core:training). Set ANDROID_HOME or create local.properties for the full build."
    )
}
