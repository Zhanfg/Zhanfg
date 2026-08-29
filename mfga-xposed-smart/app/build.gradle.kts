plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mfga.xposed.smart"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mfga.xposed.smart"
        minSdk = 28
        targetSdk = 36
        versionCode = 14
        versionName = "1.4-smart"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].resources.srcDirs("src/main/resources")
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("io.github.libxposed:api:102.0.0")
}
