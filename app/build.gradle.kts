plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tactilelens.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.tactilelens.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    // .tflite files must remain uncompressed in the APK so LiteRT can
    // mmap them directly. Compressed assets force a full RAM load and
    // break the QNN delegate's zero-copy path.
    androidResources {
        noCompress += "tflite"
    }

    // QnnDelegate dlopen()s libQnnHtp.so by absolute path at runtime.
    // Modern Android defaults (extractNativeLibs=false) keep the libs
    // mmap'd inside the APK with no on-disk path, which breaks the
    // dlopen call with "library ... not found". Force extraction so
    // the .so files exist under nativeLibraryDir.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // LiteRT Dependencies
    implementation(libs.litert)
    implementation(libs.litert.support)
    implementation(libs.litert.metadata)
    
    // CameraX Dependencies
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.extensions)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.kotlinx.coroutines.android)

    // LiteRT runtime + Qualcomm QNN delegate (Hexagon NPU). Locked decision Q3-A.
    implementation(libs.litert)
    implementation(libs.qnn.runtime)
    implementation(libs.qnn.litert.delegate)

    // ML Kit object detection (bundled — no Firebase, no network required).
    // Replaces U2Net for bounding-box detection; ~50ms vs ~380ms.
    implementation("com.google.mlkit:object-detection:17.0.2")
}
