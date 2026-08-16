# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }
# (Stale upstream com.openclaw.assistant.api/GithubRelease keep rules removed
# 2026-08-15 — those classes don't exist in this package tree; the rules were
# silently dead. Gson-deserialized models here live under
# app.marmalade.android and use kotlinx.serialization instead.)


# Google Error Prone Annotations
-dontwarn com.google.errorprone.annotations.**

# Tink (Security Crypto)
-dontwarn com.google.crypto.tink.**

# Vosk speech recognition
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**

# JNA (used by Vosk) — JNA uses reflection to access the 'peer' field
# in com.sun.jna.Pointer and native method registration in com.sun.jna.Native.
# Without these rules, R8 strips/renames fields causing UnsatisfiedLinkError.
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# Markdown renderer (JetBrains markdown parser)
-keep class org.intellij.markdown.** { *; }
-dontwarn org.intellij.markdown.**

# dnsjava — uses Java SE / Sun APIs not available on Android
-dontwarn javax.naming.**
-dontwarn sun.net.spi.nameservice.**
-dontwarn lombok.**
-dontwarn org.slf4j.impl.**
-dontwarn org.xbill.DNS.spi.**
-keep class org.xbill.DNS.** { *; }

# BouncyCastle — required for BKS KeyStore provider registration.
# Without these rules, R8 strips the security provider registration code,
# causing java.security.KeyStoreException: BKS not found at runtime.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# VOICEVOX Core
-keep class jp.hiroshiba.voicevoxcore.** { *; }
-dontwarn jp.hiroshiba.voicevoxcore.**
-dontwarn jakarta.annotation.**

# TTS Providers (multi-provider architecture)
# These classes are instantiated directly (not via reflection), but keeping them
# ensures their inner classes and data classes survive R8 in release builds.
# Repointed 2026-08-15 from stale com.openclaw.assistant.speech.* names
# (dead since the package rename) to the classes that actually exist here.
-keep class app.marmalade.android.speech.AndroidTTSProvider { *; }
-keep class app.marmalade.android.speech.AndroidTTSProvider$* { *; }
-keep class app.marmalade.android.speech.TTSManager { *; }
