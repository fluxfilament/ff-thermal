plugins {
    id("com.android.application")
}

android {
    namespace = "com.bikbovdamir.flironeviewer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bikbovdamir.flironeviewer"
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
