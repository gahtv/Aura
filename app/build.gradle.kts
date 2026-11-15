import java.util.Properties

// Secrets (API keys etc.) live in a root-level .env (gitignored, KEY=VALUE lines)
// and are surfaced as BuildConfig fields. See .env.example.
val envFile = rootProject.file(".env")
val envProps = Properties().apply {
    if (envFile.exists()) envFile.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.hans.aura"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hans.aura"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.5.0-alpha.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal project: sign release with the debug key so R8-optimized
            // builds are installable directly (debug builds run markedly slower
            // on low-power TV hardware).
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    defaultConfig {
        // Each .env entry becomes e.g. BuildConfig.MY_API_KEY
        for ((key, value) in envProps) {
            buildConfigField("String", key.toString(), "\"$value\"")
        }
        // A checkout without a .env must still compile: the LRCLIB client id is
        // optional (Lrclib simply omits the header when it is blank).
        if (!envProps.containsKey("LRCLIB_APP_ID")) {
            buildConfigField("String", "LRCLIB_APP_ID", "\"\"")
        }
    }

    // JVM unit tests: Robolectric needs the merged resources, and the Android
    // framework stubs (Log, Build, Settings) must return defaults instead of
    // throwing on a plain JVM.
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Existing findings are frozen in the baseline so only NEW issues fail
        // the build (see scripts/check.sh). Release lint is skipped: the R8
        // release build is the real release gate.
        baseline = file("lint-baseline.xml")
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    // Compose for TV
    implementation(libs.tv.material)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    // Media3 playback
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.kotlinx.serialization.converter)

    // Lifecycle / coroutines
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Images
    implementation(libs.coil.compose)

    // Palette extraction for dynamic per-album theming
    implementation(libs.androidx.palette)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // JVM unit tests (fast, no device)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
