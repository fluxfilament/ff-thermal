import java.util.Properties

plugins {
    id("com.android.application")
}

/*
 * Release signing. The key never enters the repository: its path and passwords come
 * from keystore.properties in the project root (git-ignored), or else from the
 * environment, which is how a CI job would pass them. With neither, assembleRelease
 * still builds, just unsigned. RELEASING.md has the full procedure.
 */
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)

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

    signingConfigs {
        val store = signingValue("storeFile", "FFT_KEYSTORE")
        if (store != null) {
            create("release") {
                storeFile = rootProject.file(store)
                storePassword = signingValue("storePassword", "FFT_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "FFT_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "FFT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // The dependency block AGP adds to APKs is encrypted with a Google key, so nobody
    // else can read it, and F-Droid's scanner rejects it. There are no dependencies
    // to report anyway.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
