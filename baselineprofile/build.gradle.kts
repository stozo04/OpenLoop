plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "io.github.stozo04.openloop.baselineprofile"
    compileSdk = 37 // keep in step with :app

    defaultConfig {
        minSdk = 26
        // Mirrors :app (one behind latest; each target bump is its own reviewed project).
        //noinspection OldTargetApi
        targetSdk = 36

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.test.ext.junit)
}
