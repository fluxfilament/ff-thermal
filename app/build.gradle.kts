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

    testOptions {
        unitTests {
            // Robolectric reads the merged manifest and resources from here.
            isIncludeAndroidResources = true
            all {
                // Robolectric's Android 17 image reaches into FileDescriptor through
                // jdk.internal.access, which JDK 17+ keeps closed by default.
                it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Real android.graphics for the JVM tests: ViewTransform and SpotMeter do their
    // geometry through Matrix, and the stub android.jar only throws.
    testImplementation("org.robolectric:robolectric:4.17")
}
