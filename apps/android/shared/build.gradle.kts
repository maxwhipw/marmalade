// :shared — the cross-platform KMP library (ADR 0011). Targets Android + desktop
// JVM. commonMain = pure Kotlin (protocol types). jvmSharedMain = the JVM-coupled
// core (JsonRpcClient/OkHttp, java.*), shared by both targets since both are JVM
// (no expect/actual needed). Compose Multiplatform arrives with the UI slices.
plugins {
    kotlin("multiplatform")
    id("com.android.library")
    kotlin("plugin.serialization")
    // Room KMP (ADR 0011, increment 3b): the DB/DAO/entities move here. KSP
    // runs Room's compiler per target (kspAndroid/kspDesktop, wired below); the
    // androidx.room plugin sets the shared schema output directory.
    id("com.google.devtools.ksp")
    id("androidx.room")
    // Compose Multiplatform (UI slices, desktop-client plan Phase 1). The CMP
    // plugin provides the `compose.*` dependency accessors; the Kotlin compose
    // plugin is the compiler half (same 2.1.0 as :app).
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    // The Room-KMP construction seam is an `expect object` (AppDatabaseConstructor)
    // whose `actual` the Room compiler generates per target. expect/actual classes
    // are still flagged Beta (KT-61573); this opt-in silences that warning — the
    // pattern is Room's own documented KMP approach.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // `api`, not `implementation`: kotlinx-serialization types are in :shared's
                // public ABI (e.g. GatewayEvent.payload: JsonElement?, every @Serializable).
                // Keep this version aligned with :app (app/build.gradle.kts kotlinx-serialization-json).
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                // `api`: coroutines types are in :shared's public ABI —
                // RunStateMachine.states: StateFlow<..> (increment 3d), and
                // downstream JsonRpcClient.connectionState/.events in jvmSharedMain.
                // Lifted to commonMain (from jvmSharedMain) because the pure
                // run-state slice now lives in commonMain; jvmSharedMain inherits
                // it via dependsOn(commonMain). 1.9.0 is the version with binaries
                // in the offline Gradle cache and the one :app force-pins; declare
                // it explicitly (that force block is scoped to :app, not :shared).
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                // Compose Multiplatform (UI slices). `api`: the moved theme
                // tokens put ColorScheme/Shapes/Color in :shared's public ABI.
                // On androidTarget these resolve (via CMP's redirect metadata)
                // to androidx artifacts — at CMP 1.7.3's own 1.7.6, which is
                // NOT what the APK ships. The root build's androidx Compose pin
                // lifts them to the shipped versions; without it this module
                // compiles against signatures that aren't there at runtime
                // (NoSuchMethodError). See that block before touching versions.
                api(compose.runtime)
                api(compose.foundation)
                api(compose.ui)
                api(compose.material3)
                // Material icons — EXTENDED, not just the core set material3
                // pulls in. The curated core set covers ArrowBack / Add /
                // Delete / PlayArrow / KeyboardArrowRight, but the shared
                // settings error state uses `Icons.Outlined.CloudOff`, which
                // only exists in extended. Coordinate spelled out rather than
                // the `compose.materialIconsExtended` accessor, which CMP 1.7
                // deprecates. Version tracks the CMP plugin (1.7.3) — do not
                // bump independently; the toolchain is locked.
                // On Android this redirects to
                // `androidx.compose.material:material-icons-extended`, which
                // :app already has via the Compose BOM (same classes, higher
                // version wins — no skew).
                implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
                // The three libraries the moved chat renderers draw on. All
                // are multiplatform and all resolve for the desktop (jvm)
                // target under CMP 1.7.3 — verified by
                // :shared:compileKotlinDesktop. Versions are copied from :app
                // (app/build.gradle.kts), which still declares its own for
                // the screens that stayed behind; keep the pairs in step.
                //
                // Coil 3 — inline images in MessageParts / ToolDetailSheet.
                // Just coil-compose here: the OkHttp network fetcher is
                // installed by the host (:app keeps coil-network-okhttp).
                implementation("io.coil-kt.coil3:coil-compose:3.4.0")
                // compose-richtext (on commonmark-java) — ChatMarkdownContent,
                // the one markdown renderer for both streaming and final text
                // (ADR 0006).
                implementation("com.halilibo.compose-richtext:richtext-commonmark:1.0.0-alpha03")
                implementation("com.halilibo.compose-richtext:richtext-ui-material3:1.0.0-alpha03")
                // Syntax highlighting for ChatCodeBlock.
                implementation("dev.snipme:highlights:0.9.3")
            }
        }
        // Intermediate source set shared by androidMain + desktopMain (both JVM):
        // holds java.*/OkHttp-coupled code as-is, no expect/actual. The KGP notice
        // about the default hierarchy template being disabled by these manual
        // dependsOn edges is expected and harmless with two JVM targets.
        val jvmSharedMain by creating {
            dependsOn(commonMain)
            dependencies {
                // `api`: OkHttpClient is in JsonRpcClient's public constructor.
                // Version aligned with :app (app/build.gradle.kts okhttp).
                api("com.squareup.okhttp3:okhttp:4.12.0")
                // (kotlinx-coroutines-core moved up to commonMain in increment 3d;
                //  jvmSharedMain inherits it through dependsOn(commonMain).)
                // Multiplatform lifecycle-viewmodel (desktop-client plan Phase 1,
                // the UI slices). `api`: ViewModel is the supertype of the shared
                // ViewModels and ViewModelProvider.Factory is in their companions'
                // return types, so both are in :shared's public ABI.
                //
                // 2.8.4 is the version CMP 1.7.3 pairs with — do NOT bump with the
                // toolchain locked. NOT an artifact-skew risk despite the different
                // group: JB's androidJvm variants publish ZERO files and simply
                // depend on `androidx.lifecycle:lifecycle-viewmodel` (2.8.5+), so on
                // Android this resolves to the same androidx artifact :app already
                // forces to 2.8.7 (force wins — the JB dep is `requires`, not
                // strict). Only the desktop target gets JB's own classes. Verified
                // by `:app:dependencies` + a clean assembleDebug (no duplicate
                // classes, no androidx version movement).
                //
                // Declared here rather than commonMain because the only ViewModel
                // on it (UsageViewModel) needs MarmaladeRpc, which is OkHttp-coupled.
                // Lift to commonMain when the first pure-commonMain ViewModel lands.
                api("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.8.4")
                // The Compose halves of the same multiplatform lifecycle
                // artifact set, for the moved settings *screens*:
                // `viewModel(factory = …)` from -viewmodel-compose, and
                // `collectAsStateWithLifecycle` / `LifecycleEventEffect` from
                // -runtime-compose. Same 2.8.4 pin and the same zero-file
                // androidJvm redirect as -viewmodel above: on Android they
                // resolve to `androidx.lifecycle:lifecycle-viewmodel-compose`
                // / `-runtime-compose`, which :app already forces to 2.8.7.
                // `implementation`, not `api` — neither type appears in a
                // shared declaration's signature; the screens only call them.
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
                // `api`: room-runtime is in :shared's public ABI — AppDatabase
                // extends RoomDatabase and :app holds AppDatabase/ChatDao
                // references + entity types. Resolves per-target via Gradle
                // metadata (room-runtime-android for android, room-runtime-jvm
                // for desktop). Version aligned with the :app 2.7.2 bump (3a).
                api("androidx.room:room-runtime:2.7.2")
            }
        }
        val androidMain by getting {
            dependsOn(jvmSharedMain)
            dependencies {
                // EncryptedSharedPreferences for the Android SettingsStore
                // (ADR 0011, increment 3c). Android-only; desktop uses a plain
                // file store. Version aligned with :app's own security-crypto
                // (its SecurePrefs/DeviceIdentity users keep their copy).
                implementation("androidx.security:security-crypto:1.1.0-alpha06")
                // ONNX Runtime for the wake-word adapters in jvmSharedMain
                // (OnnxWakeModels/SileroVad, moved from :app in increment 3f).
                // The ai.onnxruntime API is identical across the two artifacts,
                // but the binaries are per-platform, so this is a per-target
                // pair (the Room kspAndroid/kspDesktop pattern): android gets
                // onnxruntime-android, desktop gets the JVM jar below. PINNED
                // 1.18.0 (ADR 0010) — the version proven to coexist with
                // sherpa-onnx's statically-linked ORT (:app pickFirsts handles
                // the libonnxruntime.so overlap). Do NOT bump independently.
                implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
            }
        }
        // Plain-JVM unit tests for the code in commonMain/jvmSharedMain. This is
        // where the protocol-fixture conformance suite lives: it must run in CI
        // from a bare checkout, and `:app:testDebugUnitTest` cannot (that module
        // links the prebuilt .aar/.so binaries kept out of git). Run it with
        // `./gradlew :shared:desktopTest`.
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val desktopMain by getting {
            dependsOn(jvmSharedMain)
            dependencies {
                // BundledSQLiteDriver for the desktop DB builder only. Android
                // keeps the framework SQLite via the compat path (no driver), so
                // this stays off the android classpath.
                implementation("androidx.sqlite:sqlite-bundled:2.5.2")
                // JVM ONNX Runtime — desktop half of the per-target ORT pair
                // (see androidMain). Same 1.18.0 pin.
                implementation("com.microsoft.onnxruntime:onnxruntime:1.18.0")
            }
        }
    }
}

android {
    namespace = "app.marmalade.android.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 31
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Exported Room schema JSONs (moved here from app/schemas with the DB). The
// androidx.room plugin points every target's KSP task at this one directory.
room {
    schemaDirectory("$projectDir/schemas")
}

// Room's KSP compiler runs once per KMP target so each gets its own generated
// AppDatabase implementation + @ConstructedBy actual (android → framework
// SQLite, desktop → bundled driver).
dependencies {
    add("kspAndroid", "androidx.room:room-compiler:2.7.2")
    add("kspDesktop", "androidx.room:room-compiler:2.7.2")
}
