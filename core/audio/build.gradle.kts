plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "mmm.audio"
    compileSdk = 35

    defaultConfig {
        // AudioPlaybackCapture arrived in Android 10; everything below that can only run file mode,
        // and supporting it would mean shipping a second, misleading feature set.
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":core:dsp"))
    api(project(":core:training"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
