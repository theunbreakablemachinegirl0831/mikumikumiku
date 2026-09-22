plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// One place for the version, because it appears in two: the launcher label and the screen header.
// Builds reach the phone as CI artifacts rather than through a store, so which one is installed is
// otherwise a guess.
val appVersionName = "0.3"
val appVersionCode = 3

android {
    namespace = "mmm.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "mmm.listen"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        resValue("string", "app_name", "청음 훈련 v$appVersionName")

        // CI sets this to the commit it built from; a local build says so instead of pretending.
        buildConfigField(
            "String",
            "BUILD_ID",
            "\"${(System.getenv("MMM_BUILD_ID") ?: "local").take(7)}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation(project(":source:file"))
    implementation(project(":source:capture"))
    implementation(project(":source:usb"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
