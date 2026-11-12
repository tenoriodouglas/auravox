plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.auravox"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.auravox"
        minSdk = 26
        targetSdk = 35
        // CI passes the run number so every build is distinguishable and the
        // self-updater can tell whether what is published is newer than this
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "1.1"

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }

        ndk {
            // Phones only. 32-bit ARM is gone because no current device needs
            // it, and x86_64 is gone because the separation runtime is 40 MB
            // of native code for that architecture alone — carried by every
            // download, run by an emulator nobody ships to.
            abiFilters += "arm64-v8a"
        }

    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true   // the updater reads BuildConfig.VERSION_CODE
        prefab = true   // required to consume Oboe as a prefab package
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Native debug symbols make the audio thread inspectable in perfetto
            isJniDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("com.google.oboe:oboe:1.9.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Runs the separation models. ONNX rather than TFLite because the graph
    // needs no FFT operators at all: the transform stays in our own C++, which
    // is exactly what sank the TFLite ports of this model
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // The palette's contrast is asserted on the JVM, so it runs in CI without
    // a device
    testImplementation("junit:junit:4.13.2")
    // O org.json do android.jar e um esqueleto que lanca "not mocked", entao o
    // formato dos presets so da para testar com a implementacao de verdade
    testImplementation("org.json:json:20240303")
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-graphics")
}
