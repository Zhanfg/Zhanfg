plugins {
    id("com.android.application")
}

android {
    namespace = "cc.axymorrsen.appleprovidercompat"
    compileSdk = 37

    defaultConfig {
        applicationId = "cc.axymorrsen.appleprovidercompat"
        minSdk = 30
        targetSdk = 37
        versionCode = 2000004
        versionName = "2.0.0-alpha4"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    testImplementation("junit:junit:4.13.2")
}
