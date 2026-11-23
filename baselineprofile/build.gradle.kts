plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.hans.aura.baselineprofile"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.junit)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
}
