plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.nakas.skate3.drivertests"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.nakas.skate3.drivertests"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    sourceSets["main"].java.srcDir("src/production/java")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
