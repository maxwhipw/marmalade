buildscript {
    configurations.all {
        resolutionStrategy {
            // Offline-cache: javapoet 1.10.0 jar not cached; force to 1.13.0 which is cached.
            force("com.squareup:javapoet:1.13.0")
        }
    }
}

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.1.0" apply false
    // Plain Kotlin/JVM — :desktopApp (the CMP desktop client) has exactly one
    // target, so it uses this rather than a second KMP module.
    id("org.jetbrains.kotlin.jvm") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
    // Compose Multiplatform for the :shared UI slices (desktop-client plan
    // Phase 1). 1.7.3 = the Kotlin-2.1.0-compatible stable. On androidTarget it
    // redirects to androidx Compose 1.7.6 — which is NOT what the APK ships; see
    // the androidx Compose pin below, which is what actually keeps the two
    // aligned. Do NOT bump independently of the locked Kotlin/AGP toolchain.
    id("org.jetbrains.compose") version "1.7.3" apply false
    // Room KMP gradle plugin (schema directory + per-target KSP wiring). Applied
    // in :shared (ADR 0011, increment 3b). Version tracks the room-runtime bump.
    id("androidx.room") version "2.7.2" apply false
}

// ---------------------------------------------------------------------------
// androidx version pin — compile/runtime alignment across :app + :shared
// ---------------------------------------------------------------------------
// Fixes a NoSuchMethodError class of crash (first seen 2026-07-26: FlowRow on the
// Models settings screen). The two modules were resolving DIFFERENT androidx
// Compose versions:
//
//   :shared androidDebugCompileClasspath -> 1.7.6  (CMP 1.7.3's androidx redirect)
//   :app    debugRuntimeClasspath        -> 1.9.4  (what the APK actually dexes)
//
// :app's graph drags Compose past its own BOM (2024.12.01 = 1.7.6):
// coil-compose 3.4.0 -> org.jetbrains.compose.foundation 1.9.3, and
// richtext-ui-material3 1.0.0-alpha03 -> org.jetbrains.compose.material3 1.8.2;
// both redirect to androidx 1.9.x, and because androidx.compose.foundation is an
// atomic group, conflict resolution lifts the whole group to 1.9.4. :shared never
// sees those deps, so it compiled against 1.7.6 signatures and then ran against
// the 1.9.4 classes in the APK. `FlowRow` gained an `Alignment.Vertical`
// parameter in 1.9, so its 1.7.6 signature simply isn't there at runtime — and
// that applies to every experimental Compose API :shared touches, not just this
// one. (Only :shared crashed; :app compiles against 1.9.4 already.)
//
// So: pin explicitly, for all modules, to the versions the APK ships. This is a
// structural guard, not a patch — the two classpaths can no longer diverge for
// these groups.
//
// androidx.lifecycle is pinned for the same reason (:shared was compiling 2.8.7
// against an APK shipping 2.9.4). It has to move UP, never down: Compose UI
// 1.9.4 itself calls lifecycle 2.9 APIs, so pinning lifecycle back to 2.8.x to
// match :shared would break Compose at runtime instead. androidx.core and
// kotlin-stdlib moved here from :app's force block so both modules get one
// source of truth rather than :app-scoped values that :shared never sees.
//
// Skew deliberately NOT pinned: okio (:shared 3.6.0 vs APK 3.16.4) and
// versionedparcelable (1.1.0 vs 1.1.1) — both compile OLDER than they run,
// which is the safe direction for a backwards-compatible ABI, and neither has
// the experimental-API churn that bit Compose here.
//
// Whenever the :app Compose BOM moves, re-check alignment with:
//   ./gradlew :app:dependencies --configuration debugRuntimeClasspath
//   ./gradlew :shared:dependencies --configuration androidDebugCompileClasspath
// Android-only by construction — the :shared desktop target uses the
// org.jetbrains.* jars, which these rules don't match. (Those JB modules still
// show a version skew on Android; harmless, as their androidJvm variants
// publish zero classes and only redirect to the androidx artifacts pinned here.)
val androidxComposeVersion = "1.9.4"   // foundation / ui / runtime / animation
val androidxMaterial3Version = "1.3.2" // material3 is versioned separately
val androidxRippleVersion = "1.8.2"    // material-ripple, pulled by material3
val androidxLifecycleVersion = "2.9.4" // required by Compose 1.9.x
val androidxCoreVersion = "1.15.0"
val kotlinStdlibVersion = "2.1.0"      // matches the locked Kotlin toolchain

subprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            when {
                requested.group in setOf(
                    "androidx.compose.foundation",
                    "androidx.compose.ui",
                    "androidx.compose.runtime",
                    "androidx.compose.animation",
                ) -> useVersion(androidxComposeVersion)

                requested.group == "androidx.compose.material3" ->
                    useVersion(androidxMaterial3Version)

                // Same group as material-icons-*, which is frozen at 1.7.6 and
                // must NOT be moved — match the ripple artifact by name only.
                requested.group == "androidx.compose.material" &&
                    requested.name.startsWith("material-ripple") ->
                    useVersion(androidxRippleVersion)

                requested.group == "androidx.lifecycle" ->
                    useVersion(androidxLifecycleVersion)

                // Exact names only: core-remoteviews / core-viewtree live in the
                // same group but have their own, unrelated version lines.
                requested.group == "androidx.core" &&
                    requested.name in setOf("core", "core-ktx") ->
                    useVersion(androidxCoreVersion)

                requested.group == "org.jetbrains.kotlin" &&
                    requested.name == "kotlin-stdlib" ->
                    useVersion(kotlinStdlibVersion)
            }
        }
    }
}
