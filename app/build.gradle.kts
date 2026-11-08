import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Built-in Dropbox app key (public; PKCE needs no secret). Set dropbox.appKey in local.properties
// or the DROPBOX_APP_KEY env var. When empty, the app asks the user for their own key.
val dropboxAppKey: String = run {
    val props = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(props::load)
    props.getProperty("dropbox.appKey") ?: System.getenv("DROPBOX_APP_KEY") ?: ""
}

android {
    namespace = "dev.rubybox.diet"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.rubybox.diet"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "DROPBOX_APP_KEY", "\"${dropboxAppKey.trim()}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug-signed so the release APK can be sideloaded without a keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.health.connect:connect-client:1.1.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.charleskorn.kaml:kaml:0.66.0")
}
