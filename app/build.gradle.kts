plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.rapido"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.rapido.autoclicker"
        minSdk = 24
        targetSdk = 34
        versionCode = 48
        versionName = "48.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Pure Android Native SDK - Zero external dependencies for guaranteed 15-second build
}
