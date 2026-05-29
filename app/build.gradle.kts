import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.openrang.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.openrang.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Store native libs uncompressed and 16 KB page-aligned so they map directly
            // from the APK on 16 KB-page devices (required at targetSdk 36). Extracting them
            // (legacy packaging) is the pre-16 KB behavior. minSdk 26 supports this.
            useLegacyPackaging = false
        }
    }

    lint {
        // The pr-reviewer skill runs `:app:lintDebug` as a merge gate (engine 1 of Android
        // Studio's "Inspect Code") and parses the XML at app/build/reports/lint-results-debug.xml
        // into PR findings. See docs/STATIC_ANALYSIS.md for the full design.
        //
        //  - xmlReport / htmlReport: machine-readable (skill) + human-readable (local triage).
        //  - checkDependencies: lint included module code too, not just :app sources.
        //  - baseline: snapshot of pre-existing issues so the gate only flags NEW regressions.
        //    The repo carried ~294 pre-existing inspection items; without a baseline they'd
        //    drown the signal on every PR. Regenerate deliberately (see docs/STATIC_ANALYSIS.md),
        //    never casually — a regenerated baseline silently swallows freshly-introduced issues.
        //  - abortOnError = false: the SKILL decides the PR verdict, not the build, so lint always
        //    emits a full report instead of failing the build on the first error.
        //  - warningsAsErrors = false: warnings are surfaced by the skill at WARNING/REC severity.
        xmlReport = true
        htmlReport = true
        checkDependencies = true
        baseline = file("lint-baseline.xml")
        abortOnError = false
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Splash screen (Android 12+ system splash, back-compat to earlier APIs)
    implementation(libs.androidx.core.splashscreen)

    // CameraX
    implementation(libs.androidx.camerax.core)
    implementation(libs.androidx.camerax.camera2)
    implementation(libs.androidx.camerax.lifecycle)
    implementation(libs.androidx.camerax.video)
    implementation(libs.androidx.camerax.view)

    // Media3 (ExoPlayer & Video Reversal/Processing)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.effect)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)

    // Compose UI Testing (instrumented)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // Explicit Espresso 3.7.0: forces the fixed version over the older one pulled
    // transitively by ui-test-junit4. 3.7.0 replaced the reflective
    // InputManager.getInstance() (removed in Android 16 / API 36) with getSystemService,
    // fixing the NoSuchMethodException that broke every instrumented test on API 36.
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
