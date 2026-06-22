import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
}

// Release signing is driven by a gitignored keystore.properties at the repo root (never commit
// the keystore or its passwords — see keystore.properties.template + docs/play-store/release-signing-and-aab.md).
// When the file is absent (CI, a fresh clone, debug-only work) the release build stays unsigned-by-config
// so every other task still runs; you sign for Play by providing the upload keystore locally.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

android {
    namespace = "io.github.stozo04.openloop"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.stozo04.openloop"
        minSdk = 26
        targetSdk = 36
        versionCode = 23
        versionName = "1.0.23"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            // Populated only when keystore.properties exists, so configuring the project without
            // the keystore (CI / fresh clone) doesn't fail. Use the Play *upload* key here; Google
            // re-signs with the app key under Play App Signing.
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
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
            // Embed native debug symbols in the AAB so Play Console can de-obfuscate native crashes/ANRs
            // (CameraX, Media3, etc. ship .so libs). SYMBOL_TABLE is enough for Play; no separate upload.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            // Sign the release (APK/AAB) only when the keystore is present; otherwise leave it
            // unsigned so non-publishing tasks still build.
            signingConfig = if (keystorePropertiesFile.exists()) signingConfigs.getByName("release") else null
        }
    }
    compileOptions {
        // Java 17 per https://developer.android.com/build/jdks — JDK 21 deprecated compiling
        // to source/target 8. D8 dexes the bytecode, so this is independent of minSdk.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Robolectric needs the merged manifest + resources on the unit-test classpath so a test
            // can build real framework objects (notifications, ForegroundInfo, etc.) on the JVM.
            isIncludeAndroidResources = true
        }
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
    jvmToolchain(17)
    compilerOptions {
        // Must match compileOptions source/targetCompatibility above.
        jvmTarget = JvmTarget.JVM_17
    }
}

// App code targets/compiles to Java 17 (above), but the JVM that *runs* the unit tests is pinned to
// JDK 21. Robolectric must run on JDK 21 to load the API-36 android-all jar (Android's SDK 36 jars
// are compiled with Java 21), and Robolectric defaults to the project's targetSdk (36). Running
// Java-17 test bytecode on a JDK-21 launcher is fully supported, so this unblocks device-free,
// per-API-level testing (e.g. @Config(sdk=[34]) FGS-type regression) without disturbing the
// documented Java-17 app toolchain.
tasks.withType<Test>().configureEach {
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

dependencies {
    constraints {
        // AGP 9 no longer aligns the compile classpath to runtime versions
        // (android.dependency.useConstraints now defaults to false). Without this pin the
        // *compile* classpath resolves transitive androidx.fragment to 1.1.0 while runtime
        // gets 1.5.4, tripping lint's InvalidFragmentVersionForActivityResult on every
        // registerForActivityResult call site. Pin compile to the runtime-resolved version.
        // OpenLoop never uses Fragments directly — this is a constraint, not a dependency.
        implementation(libs.androidx.fragment)
    }

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

    // WorkManager — long-running Loopifying export survives backgrounding (Issue #40)
    implementation(libs.androidx.work.runtime.ktx)

    // Firebase Crashlytics (non-fatal reverse preview failures) — requires app/google-services.json
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.analytics)

    // Media3 (ExoPlayer & Video Reversal/Processing)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.effect)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric — run real Android framework code on the JVM at a chosen API level
    // (@Config(sdk=[...])), so version-gated platform behavior is verifiable without a device.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)

    // Compose UI Testing (instrumented)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.rules)
    // Explicit Espresso 3.7.0: forces the fixed version over the older one pulled
    // transitively by ui-test-junit4. 3.7.0 replaced the reflective
    // InputManager.getInstance() (removed in Android 16 / API 36) with getSystemService,
    // fixing the NoSuchMethodException that broke every instrumented test on API 36.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.work.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    baselineProfile(project(":baselineprofile"))
}

// Crashlytics mapping upload + Firebase config only when the console JSON is present locally.
// See docs/diagnostics/firebase-crashlytics-trimming.md and app/google-services.json.README.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}
