plugins {
    id("com.android.application") version "8.12.2" apply false
    id("com.android.test") version "8.12.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
    // 静态检查。此前 42k 行 Kotlin 完全没有 lint 之外的把关。
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("org.jlleitschuh.gradle.ktlint") version "12.3.0"
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    detekt {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        baseline = rootProject.file("config/detekt/baseline.xml").takeIf { it.exists() }
        parallel = true
    }

    ktlint {
        version.set("1.5.0")
        android.set(true)
        // 存量代码先只报告不阻断；新代码由 CI 的 detekt 把关。
        ignoreFailures.set(true)
        filter {
            exclude { it.file.path.contains("/build/") }
        }
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "17"
        reports {
            html.required.set(true)
            sarif.required.set(true)
            txt.required.set(false)
            xml.required.set(false)
        }
    }
}
