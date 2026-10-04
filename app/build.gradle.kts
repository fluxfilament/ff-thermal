plugins {
    id("com.android.application")
}

android {
    namespace = "com.fluxfilament.thermalcam"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fluxfilament.thermalcam"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
