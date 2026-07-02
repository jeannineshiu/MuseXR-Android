import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Meta Wearables DAT application credentials, registered at
// https://wearables.developer.meta.com. Left blank, the app can still be
// used against glasses with "Developer Mode" enabled in the Meta AI app.
val localProperties =
    Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            load(localPropertiesFile.inputStream())
        }
    }

android {
    namespace = "com.hypdescape.musexr"
    // AGP 8.7.3 doesn't support the newer `compileSdk { version = release(36) }` block DSL.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hypdescape.musexr"
        // mwdat-camera requires API 31+
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["mwdatApplicationId"] =
            localProperties.getProperty("mwdat_application_id", "")
        manifestPlaceholders["mwdatClientToken"] =
            localProperties.getProperty("mwdat_client_token", "")
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
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}