plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.airtouch.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.airtouch.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
    }

    val uploadStore = providers.environmentVariable("AIRTOUCH_UPLOAD_KEYSTORE").orNull
    val uploadStorePassword = providers.environmentVariable("AIRTOUCH_UPLOAD_STORE_PASSWORD").orNull
    val uploadKeyAlias = providers.environmentVariable("AIRTOUCH_UPLOAD_KEY_ALIAS").orNull
    val uploadKeyPassword = providers.environmentVariable("AIRTOUCH_UPLOAD_KEY_PASSWORD").orNull
    val uploadSigningValues = listOf(uploadStore, uploadStorePassword, uploadKeyAlias, uploadKeyPassword)
    require(uploadSigningValues.all { it.isNullOrBlank() } || uploadSigningValues.all { !it.isNullOrBlank() }) {
        "Set all four AIRTOUCH_UPLOAD_* environment variables to sign a release bundle."
    }

    if (uploadSigningValues.all { !it.isNullOrBlank() }) {
        signingConfigs {
            create("upload") {
                storeFile = file(uploadStore!!)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
        buildTypes.getByName("release") {
            signingConfig = signingConfigs.getByName("upload")
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-service:2.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    val cameraX = "1.5.2"
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("com.google.mediapipe:tasks-vision:1.1.1")
    testImplementation("junit:junit:4.13.2")
}
