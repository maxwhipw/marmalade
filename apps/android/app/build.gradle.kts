plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
}

android {
    namespace = "app.marmalade.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.marmalade.android"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
        // Resolve libonnxruntime.so conflict: our own onnxruntime-android dependency
        // (app.marmalade.android.speech.wake — the in-repo wake-word pipeline, see
        // docs/decisions/0010) and sherpa-onnx (local AAR) both bundle it. Keep the
        // Maven dependency's version (1.18.0, pinned) over sherpa's bundled copy.
        jniLibs {
            pickFirsts += "lib/*/libonnxruntime.so"
            pickFirsts += "lib/*/libonnxruntime4j_jni.so"
        }
    }

    sourceSets {
        getByName("main") {
            // libmarmalade_term.so — the libghostty-vt terminal core (native/,
            // ADR 0015-revisit). The .so is built by Zig and *committed* under
            // native/prebuilt/jniLibs/, so a Gradle build never needs the Zig
            // toolchain or the NDK. Rebuild + re-copy per native/README.md.
            jniLibs.srcDir("../native/prebuilt/jniLibs")
        }
    }

    lint {
        lintConfig = file("lint.xml")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Phase 9 MCP-02: JVM unit tests exercise ChatController.handleChatEvent
        // which calls android.util.Log.d. Without this flag, unmocked framework
        // methods throw and drown the real assertion. returnDefaultValues lets
        // Log.d() return 0 (the int default) silently — no behavior change in
        // production, only in JVM-side unit tests.
        unitTests.isReturnDefaultValues = true
        // Bound the Robolectric fork heap (Fable-reviewed, 2026-07-21). Without
        // this the fork ran at Gradle's 512m default — 942 tests passing under
        // GC pressure; 2g makes the ceiling explicit and keeps peak RAM bounded.
        unitTests.all { it.maxHeapSize = "2g" }
    }
}

// Force transitive dependencies to versions available in the offline Gradle cache.
//
// :app-scoped only — these artifacts aren't on :shared's classpath, so a local
// force is enough. The cross-module ones (androidx.lifecycle, androidx.core,
// kotlin-stdlib) moved to the root build's version-pin block: forcing them here
// left :shared resolving different versions than the APK ships, which is what
// caused the 2026-07-26 FlowRow NoSuchMethodError. Don't re-add them here —
// a :app-scoped force cannot keep :shared in step.
configurations.all {
    resolutionStrategy {
        // Coroutines: 1.7.3 requested by various deps; only 1.9.0 is cached with binaries.
        force("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        // Fragment: 1.1.0 requested by camera-view via appcompat:1.1.0; 1.5.4 is cached.
        force("androidx.fragment:fragment:1.5.4")
        // AppCompat: camera-view requests 1.1.0; 1.7.0 is cached.
        force("androidx.appcompat:appcompat:1.7.0")
        // Guava: older deps request 31.1-jre; 33.0.0-jre is cached.
        force("com.google.guava:guava:33.0.0-jre")
        // KSP: older deps may request 1.9.0-1.0.13; only 2.1.0-1.0.29 JAR is cached.
        force("com.google.devtools.ksp:symbol-processing-api:2.1.0-1.0.29")
    }
}

dependencies {
    // Shared KMP library (ADR 0011) — protocol types (rpc/types) now live here in
    // commonMain, reused by the desktop client. Package namespace is unchanged, so
    // app import sites are untouched.
    implementation(project(":shared"))

    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    // ProcessLifecycleOwner — observed in MarmaladeRuntime for app
    // foreground (ON_START) hooks. The other lifecycle artifacts above
    // are activity/composable-scoped; this one is process-scoped.
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Encrypted SharedPreferences (used by SettingsRepository, SecurePrefs,
    // and DeviceIdentity — pulls in Tink transitively for AES-GCM/SIV)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // No emoji picker dep — custom built-in picker (AndroidX emojipicker has
    // Guava ListenableFuture conflicts with CameraX that aren't worth resolving)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // CameraX
    val cameraXVersion = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-video:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    // EXIF
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Testing (JVM unit tests). Compose UI tests run via Robolectric so
    // they live in :app:testDebugUnitTest alongside the rest — the
    // androidTest source set below is NOT for UI, it exists only for the
    // one thing a JVM twin physically cannot cover (see below).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // Compile-only: espresso-core is ALREADY on the unit-test runtime classpath
    // (ui-test-junit4 drives Robolectric idling through it). This just makes
    // IdlingPolicies / AppNotIdleException visible to test sources, so
    // ChatMessageListScrollTest can assert on "composition never went idle"
    // without waiting out Espresso's 60-second default master timeout.
    testCompileOnly("androidx.test.espresso:espresso-core:3.5.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Instrumented tests. Narrow charter: the libghostty JNI boundary
    // (app.marmalade.android.terminal) is native code in an .so — a JVM twin
    // cannot load it, so the only way to prove the bridge actually answers
    // is to run it on a device. Everything else stays in testDebugUnitTest.
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")

    // Room (2.7.2, KMP) now lives in :shared (ADR 0011, increment 3b) — the
    // @Database/DAO/entities moved to shared/jvmSharedMain and room-runtime is
    // exported `api` from there, so :app gets AppDatabase + ChatDao + entity
    // types transitively via project(":shared") above. No Room dep or KSP
    // processor is declared here any more (Room was :app's only KSP consumer).

    // Markdown rendering — compose-richtext, backed by commonmark-java.
    // Replaces mikepenz/multiplatform-markdown-renderer 0.29.0 which used
    // intellij-markdown as its parser; that parser misparses lists nested
    // in blockquotes (lazy paragraph continuation absorbs `> 1. item` into
    // the preceding paragraph) and also required custom AST workarounds
    // for tables and code-block language extraction. commonmark-java is
    // the spec reference implementation and handles all these cases natively.
    implementation("com.halilibo.compose-richtext:richtext-commonmark:1.0.0-alpha03")
    implementation("com.halilibo.compose-richtext:richtext-ui-material3:1.0.0-alpha03")
    // Note: GFM tables + LaTeX + mermaid would need either a fork of
    // richtext-commonmark (which hides its Parser.builder() inside the
    // Markdown composable and doesn't expose an extensions hook in
    // alpha03) or a switch to a different renderer. ChatMarkdownPreprocessor
    // handles GFM task lists via Unicode glyph substitution; a similar
    // glyph-substitution approach for tables (pre-rendering pipe-tables
    // as fixed-width text inside a fenced block) is the cheapest next
    // step. Tracked under parity row 2.
    // Syntax highlighting for ChatCodeBlock — was pulled transitively by
    // the prior `multiplatform-markdown-renderer-code` package, now made
    // explicit since compose-richtext doesn't ship it.
    implementation("dev.snipme:highlights:0.9.3")

    // Image loading (Coil 3)
    implementation("io.coil-kt.coil3:coil-compose:3.4.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.4.0")

    // QR code scanning (ZXing)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Glance (Jetpack home-screen widgets) - Phase 6 Plan 3
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // ONNX Runtime (wake-word pipeline, ADR 0010) now arrives transitively
    // via :shared — the session adapters (OnnxWakeModels/SileroVad) moved to
    // shared/jvmSharedMain in KMP increment 3f, and :shared/androidMain
    // declares onnxruntime-android 1.18.0 (pinned; see shared/build.gradle.kts).
    // libonnxruntime.so still lands in the APK through that path, so the
    // packaging pickFirsts above remain load-bearing against sherpa's copy.

    // Sherpa-ONNX (streaming STT for patient listening mode)
    // Static-link variant avoids ONNX Runtime .so conflict with our own
    // onnxruntime-android dependency above.
    implementation(files("../libs/sherpa-onnx-static-link-onnxruntime-1.12.32.aar"))
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
