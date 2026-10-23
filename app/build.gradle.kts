plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersion = (findProperty("appVersion") as String?) ?: "1.0"
require(Regex("\\d+\\.\\d").matches(appVersion)) { "appVersion must look like 1.0" }
val appVersionCode = appVersion.split(".").let { it[0].toInt() * 10 + it[1].toInt() }

android {
    namespace = "com.speedtester.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.speedtester.app"
        minSdk = 29
        targetSdk = 35
        versionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: appVersionCode
        versionName = appVersion + ((findProperty("appVersionSuffix") as String?) ?: "")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            // CI signs the unsigned release APK using the encrypted MRadio signing material.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.8")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.navigation:navigation-compose:2.8.8")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.8")
}

dependencies {
    if (providers.gradleProperty("useN2K02Libraries").orNull == "true") {
        implementation("dev.n2k02:theme:0.1.4")
        implementation("dev.n2k02:updater:0.1.11")
    }
}
