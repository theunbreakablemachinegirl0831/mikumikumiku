plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "mmm.diagnostics"
    compileSdk = 35

    defaultConfig {
        applicationId = "mmm.diagnostics"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "0.5"

        // The version goes in the launcher label as well as on screen. Builds reach the phone as
        // CI artifacts rather than through a store, so "which one is installed" is otherwise a
        // guess - and a stale build has already cost us one round of wrong conclusions.
        resValue("string", "app_name", "청음 진단 v0.5")

        // CI sets this to the short commit; a local build says so instead of pretending.
        buildConfigField(
            "String",
            "BUILD_ID",
            "\"${System.getenv("MMM_BUILD_ID") ?: "local"}\"",
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
    implementation(project(":source:capture"))
    implementation(project(":source:usb"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
}
