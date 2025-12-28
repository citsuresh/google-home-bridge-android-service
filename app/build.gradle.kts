import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    alias(libs.plugins.errorprone)
}

android {
  namespace = "com.example.googlehomeapisampleapp"
  compileSdk = 36

  defaultConfig {
        applicationId = "com.example.googlehomeapisampleapp"
        minSdk = 29
        targetSdk = 34
        versionCode = 37
        versionName = "1.6.0"

        // Store your GCP project web client ID in local.properties and access it via project properties.
        // If local.properties doesn't exist in your app root folder, just create it
        // e.g. add this line to your local.properties
        // WEB_CLIENT_ID_DEV={ProjectNumber}....apps.googleusercontent.com
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { localProperties.load(it) }
        }
        val webClientIdDevRaw = localProperties.getProperty("WEB_CLIENT_ID_DEV")
            ?: project.findProperty("WEB_CLIENT_ID_DEV") as? String
            ?: "YOUR_DEFAULT_WEB_CLIENT_ID"
        val webClientIdDev = webClientIdDevRaw.replace("\"", "")
        buildConfigField("String", "DEFAULT_WEB_CLIENT_ID", "\"$webClientIdDev\"")
    }
    lint {
        disable += "NullSafeMutableLiveData"
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    // Library dependencies:
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended:1.7.7")
    implementation(libs.androidx.navigation.compose)
    // Home API SDK dependency:
    implementation("com.google.android.gms:play-services-home:17.1.0")
    implementation("com.google.android.gms:play-services-home-types:17.1.0")
    // Matter Android Demo SDK
    implementation(libs.matter.android.demo.sdk)

    // Camera
    implementation(libs.dagger.hilt.android)
    implementation(libs.googleid)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.stream.webrtc.android)
    implementation(libs.errorprone.annotations)

    // Camera Commissioning
    implementation(libs.androidx.camerax.core)
    implementation(libs.androidx.camerax.camera2)
    implementation(libs.androidx.camerax.lifecycle)
    implementation(libs.androidx.camerax.view)
    implementation(libs.mlkit.barcode.scanning)

    // Ktor for embedded web server
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-cio:2.3.12")
    implementation("io.ktor:ktor-server-websockets:2.3.12")

    // Timber for logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Gson for JSON serialization
    implementation("com.google.code.gson:gson:2.11.0")

    // LocalBroadcastManager
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
}
