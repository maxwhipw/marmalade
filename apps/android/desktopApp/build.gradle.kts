// :desktopApp — the Compose Multiplatform DESKTOP client (desktop-client plan
// Phase 2, walking-skeleton spike). A plain Kotlin/JVM module, not a second KMP
// module: there is exactly one target here, and Gradle module metadata resolves
// `project(":shared")` to that module's `desktop` jvm target automatically
// (androidTarget carries platform.type=androidJvm, so there is no ambiguity).
//
// Toolchain is the repo's locked one — Kotlin 2.1.0 / CMP 1.7.3 — declared
// without versions because the root build's `plugins {}` block already pins
// them. Do NOT bump independently; :shared compiles against the same pair.
plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// ---------------------------------------------------------------------------
// skiko native/JVM alignment — the desktop twin of the root androidx pin
// ---------------------------------------------------------------------------
// Compose desktop's Skia backend ships as TWO coordinates: `skiko-awt` (the JVM
// classes) and `skiko-awt-runtime-<os>-<arch>` (the JNI natives). Only the
// former is on anyone's dependency graph as a plain `skiko` requirement, so
// conflict resolution lifts it — :shared's coil/richtext deps drag JB Compose to
// 1.9.3, which wants skiko 0.9.22.2 — while the per-OS native artifact keeps the
// version CMP 1.7.3 asked for (0.8.18) because nothing else ever names it.
//
// Result: 0.9.22.2 Java classes calling 0.8.18 natives, which dies at window
// creation with `UnsatisfiedLinkError: RenderNodeContext_nMake`. Pinning the
// whole group to one version is what keeps the two halves in step; do not
// hardcode a number here, take whatever `skiko-awt` resolved to.
val skikoVersion = "0.9.22.2"

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.skiko") useVersion(skikoVersion)
    }
}

dependencies {
    implementation(project(":shared"))

    // CMP desktop runtime. `compose.desktop.currentOs` brings the Skia backend
    // for the host platform; the rest mirror :shared's own `api(compose.*)`
    // declarations so this module can name the types directly.
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation(compose.material3)

    // Transport + serialization. Both are already `api` on :shared, but naming
    // them here keeps this module's own source honest about what it uses.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // Swing dispatcher for Dispatchers.Main on desktop (Compose UI thread).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // System tray — ComposeNativeTray (kdroidFilter), MIT (verified against the
    // repo's LICENSE and the published POM). On Linux it drives libappindicator,
    // i.e. a StatusNotifierItem over D-Bus, which is what KDE Plasma consumes;
    // AWT's java.awt.SystemTray draws nothing under Wayland, so this dependency
    // is the whole reason a tray is possible at all.
    //
    // PINNED, and NOT at the latest release, for two reasons:
    //  - single-maintainer library, so we take exactly the version we tested
    //    and wrap it behind our own TrayHost interface (one file names it);
    //  - it is the last release built against THIS repo's locked toolchain.
    //    0.6.7+ is compiled by Kotlin 2.2/2.3, whose metadata a Kotlin 2.1
    //    compiler refuses to read; 0.6.6 is Kotlin 2.1.21 / CMP 1.8.0, both of
    //    which the 2.1.0 compiler and the 1.9.3 runtime here accept. Bumping it
    //    means bumping the toolchain, not the other way round.
    //
    // Transitive licenses: kmplog + platformtools (MIT), kotlinx-coroutines
    // (Apache-2.0), JNA (dual LGPL-2.1-or-later OR Apache-2.0 — taken under
    // Apache-2.0). The libappindicator it dlopen()s is a system library the
    // desktop already ships; we neither link nor redistribute it.
    implementation("io.github.kdroidfilter:composenativetray:0.6.6")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

compose.desktop {
    application {
        mainClass = "app.marmalade.desktop.MainKt"
        // Set through the CMP DSL rather than the run task's `javaLauncher`:
        // the plugin drives the task's `executable` from this, and Gradle
        // rejects a build that sets both. Silently left at the build JDK when
        // no 21 toolchain is installed — the module still compiles and tests.
        java21Home?.let { javaHome = it }
    }
}

// The app RUNS on 21 even though it COMPILES to 17 bytecode.
//
// compose-richtext 1.0.0-alpha03 — the markdown renderer behind every chat
// bubble (ADR 0006) — publishes its JVM variant as class file version 65, i.e.
// Java 21. Android never notices (D8 desugars it); a desktop JVM does, and a
// 17 runtime throws UnsupportedClassVersionError the moment the first markdown
// message renders. Compiling to 17 keeps this module's own bytecode aligned
// with :shared, which :app also consumes.
//
// Toolchain-resolved rather than a hardcoded path, so this doesn't depend on
// one box's JDK layout.
val java21Home: String?
    get() = runCatching {
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
            .get().metadata.installationPath.asFile.absolutePath
    }.getOrNull()

// The daemon smoke test talks to a LIVE local marmaladed over loopback, so it
// is never up to date and must not be cached.
tasks.named<Test>("test") {
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
