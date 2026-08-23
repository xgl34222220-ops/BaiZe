plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystorePath = providers.environmentVariable("BAIZE_KEYSTORE_PATH")
val releaseKeystorePassword = providers.environmentVariable("BAIZE_KEYSTORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("BAIZE_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("BAIZE_KEY_PASSWORD")
val releaseSigningReady = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { provider -> provider.isPresent && provider.get().isNotBlank() }

android {
    namespace = "io.github.xgl34222220.baize"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.xgl34222220.baize"
        minSdk = 26
        targetSdk = 36
        versionCode = 26003
        versionName = "2.6.3"
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

    testOptions {
        unitTests {
            // 让未 mock 的 android.* 调用返回默认值而不是抛
            // "not mocked" 异常，纯逻辑测试无需引入 Robolectric。
            isReturnDefaultValues = true
        }
    }

    lint {
        // 先建立基线，新增问题才会让 CI 变红；存量问题逐步清理。
        baseline = file("lint-baseline.xml")
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
    }

    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = file(releaseKeystorePath.get())
                storePassword = releaseKeystorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

gradle.taskGraph.whenReady {
    val requestsReleaseArtifact = allTasks.any { task ->
        task.name.contains("Release", ignoreCase = true) &&
            (task.name.contains("assemble", ignoreCase = true) ||
                task.name.contains("bundle", ignoreCase = true) ||
                task.name.contains("package", ignoreCase = true) ||
                task.name.contains("sign", ignoreCase = true))
    }
    if (requestsReleaseArtifact && !releaseSigningReady) {
        throw GradleException(
            "正式 Release 构建缺少 BAIZE_KEYSTORE_PATH、BAIZE_KEYSTORE_PASSWORD、" +
                "BAIZE_KEY_ALIAS 或 BAIZE_KEY_PASSWORD；禁止回退到 Debug 签名。"
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.10.2")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.materialkolor:material-kolor:2.0.0")
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    implementation("dev.chrisbanes.haze:haze-materials:1.6.10")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.github.topjohnwu.libsu:service:6.0.0")

    // 单元测试。此前 42817 行 Kotlin 没有任何 JVM 测试，
    // 所有"测试"都是 shell 里 grep 源码字符串的 contract 脚本。
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
