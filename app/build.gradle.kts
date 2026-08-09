import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Secrets come from local.properties (gitignored) or the environment — never from
 * source control. An absent key is not a build error: CLUTCH runs without it and
 * falls back to the on-device detector's own ranking.
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(name: String, fallback: String = ""): String =
    localProperties.getProperty(name) ?: System.getenv(name) ?: fallback

android {
    namespace = "com.iqoo.clutch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.iqoo.clutch"
        minSdk = 29 // AudioPlaybackCaptureConfiguration requires API 29+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Read from local.properties: OPENROUTER_API_KEY=sk-or-v1-...
        buildConfigField("String", "OPENROUTER_API_KEY", "\"${secret("OPENROUTER_API_KEY")}\"")
        // minimax-m3 is the event's rank-1 recommended model AND accepts images, which
        // the highlight review needs. Note DeepSeek V4 (ranks 2-3) is text-only — it
        // cannot do this job at all, however cheap it is.
        buildConfigField(
            "String",
            "OPENROUTER_MODEL",
            "\"${secret("OPENROUTER_MODEL", "minimax/minimax-m3")}\""
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true // required for the generated BuildConfig fields above
    }
    composeOptions {
        // Must match the Kotlin version in the root build.gradle.kts (1.9.24 -> 1.5.14).
        // The Compose compiler hard-fails on a mismatch.
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // PINNED TO 2.7.0 ON PURPOSE — do not bump without also bumping the Compose BOM.
    // Lifecycle 2.8.x moved LocalLifecycleOwner into androidx.lifecycle.compose and
    // relies on Compose UI 1.7+ to provide it. Compose BOM 2024.05.00 ships UI 1.6.7,
    // which only provides the old androidx.compose.ui.platform.LocalLifecycleOwner.
    // The mismatch compiles cleanly and then crashes at runtime on the first
    // collectAsStateWithLifecycle call:
    //   IllegalStateException: CompositionLocal LocalLifecycleOwner not present
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Media3 - clip trimming (Transformer)
    implementation("androidx.media3:media3-transformer:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")
    implementation("androidx.media3:media3-effect:1.3.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
