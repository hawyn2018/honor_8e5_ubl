plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "net.hawyn.ablefisp.feature"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    resourcePrefix = "ablefisp_"
    defaultConfig {
        minSdk = 28
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    api(project(":abl-efisp-core"))
    api("androidx.activity:activity-compose:1.13.0")
    api("androidx.core:core-ktx:1.18.0")
    api("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    api("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    api("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
