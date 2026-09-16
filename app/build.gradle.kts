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
        versionCode = 23
        versionName = "0.1.22"
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
        // libmain.so used to be left page-aligned inside the APK and mapped
        // from there, rather than unpacked into the app's data directory. The
        // driver proxy ended that: libadrenotools has to dlopen its two hook
        // libraries by real path out of nativeLibraryDir, and an APK-mapped
        // build never creates that directory at all. So the engine is unpacked
        // alongside them. It is not a free trade - the download gets smaller
        // because the library is now compressed in the APK, but the installed
        // footprint grows by roughly the size of the engine and installing
        // takes longer. DriverBridge refuses to load a driver rather than
        // silently misbehave if this is ever flipped back.
        jniLibs.useLegacyPackaging = true
        // Package each native artifact exactly as staged. build_native.sh and
        // build_driver_proxy.sh have already stripped what should be stripped,
        // and keeping their copies unstripped here is what makes ndk-stack work.
        jniLibs.keepDebugSymbols += "**/*.so"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        // This used to be abortOnError = false, which meant nothing was ever
        // gated. Build.SOC_MANUFACTURER went out that way: an API-31 field read
        // under minSdk 28, which does not fail to compile - it throws
        // NoSuchFieldError on the device, and it broke the diagnostic report on
        // every Android 9, 10 and 11 phone that tried to send one.
        //
        // Only warnings remain in our own sources, so errors can gate now. The
        // error-level findings that exist are all in SDL's Java, which comes
        // from the engine tree rather than this repository; lint.xml ignores
        // those by package rather than by a path that would differ per machine.
        abortOnError = true
        lintConfig = file("lint.xml")
        // Also fail the release assembly, not just an explicit lint run.
        fatal += "NewApi"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
