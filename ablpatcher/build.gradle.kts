plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "net.hawyn.ablefisp"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "net.hawyn.ablefisp"
        minSdk = 28
        targetSdk = 37
        versionCode = 5
        versionName = "1.2.1"
    }
    flavorDimensions += "patchMode"
    productFlavors {
        create("freedom") {
            dimension = "patchMode"
            applicationId = "net.hawyn.ablefisp"
            versionCode = 30
            versionName = "1.3.7"
            resValue("string", "app_name", "ABL-EFISP")
            buildConfigField("String", "PATCH_MODE", "\"FREEDOM\"")
        }
        create("fakebl") {
            dimension = "patchMode"
            applicationId = "net.hawyn.ablefispfakebl"
            versionCode = 35
            versionName = "1.4.1"
            resValue("string", "app_name", "ABL-EFISP fakebl")
            buildConfigField("String", "PATCH_MODE", "\"FREEDOM_FAKE_LOCK\"")
        }
    }
    buildFeatures { compose = true; buildConfig = true; resValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":abl-efisp-feature"))
}
