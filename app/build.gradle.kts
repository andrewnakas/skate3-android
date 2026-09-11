import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The engine tree. Gradle never builds it: 7.7 million lines of recompiled
// PowerPC take a couple of hours and want the memory throttling in
// scripts/build_native.sh, which an externalNativeBuild block would bypass and
// hide. That script drops the finished libmain.so into jniLibs; Gradle only
// packages it. The SDL Java classes below come from the same tree so they stay
// in lockstep with the statically linked SDL3 (SDLActivity checks its own
// version against the native library at startup and refuses a mismatch).
val engineDir: String = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("skate3.engineDir")
    ?: throw GradleException("Set skate3.engineDir in local.properties")

val sdlJavaDir = "$engineDir/third_party/rexglue-sdk/thirdparty/sdl3/android-project/app/src/main/java"

android {
    namespace = "com.nakas.skate3"

    // The diagnostic report names the version it was gathered from; without
    // that a report from a stranger cannot be matched to a build.
    buildFeatures {
        buildConfig = true
    }
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.nakas.skate3"
        // 28 covers ASharedMemory (26) for the guest memory mapping and AAudio
        // (27) for the audio backend, both of which the runtime needs.
        minSdk = 28
        targetSdk = 35
        versionCode = 21
        versionName = "0.1.20"
        ndk { abiFilters += "arm64-v8a" }
    }

    sourceSets["main"].java.srcDirs("src/main/java", sdlJavaDir)

    signingConfigs {
        // The debug key signs both variants: a release build is for measuring
        // frame times on the phone in the next room, not for distribution.
        create("local") {
            storeFile = File(System.getProperty("user.home"), ".android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("local")
        }
        debug {
            signingConfig = signingConfigs.getByName("local")
        }
    }

    packaging {
        // Leave libmain.so page-aligned inside the APK and map it from there
        // rather than unpacking 150 MB into the app's data directory.
        jniLibs.useLegacyPackaging = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
