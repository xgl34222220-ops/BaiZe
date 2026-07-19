plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.xgl34222220.baize"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.xgl34222220.baize"
        minSdk = 26
        targetSdk = 36
        versionCode = 22280
        versionName = "2.0.0-alpha42.8"
    }

    buildFeatures {
        viewBinding = true
        compose = true
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
