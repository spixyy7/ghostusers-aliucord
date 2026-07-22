@file:Suppress("UnstableApiUsage")

import com.aliucord.gradle.AliucordExtension
import com.android.build.gradle.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

subprojects {
    val libs = rootProject.libs

    apply {
        plugin(libs.plugins.android.library.get().pluginId)
        plugin(libs.plugins.aliucord.plugin.get().pluginId)
        plugin(libs.plugins.kotlin.android.get().pluginId)
        plugin(libs.plugins.ktlint.get().pluginId)
    }

    configure<LibraryExtension> {
        namespace = "com.spixyy.ghostusers"
        compileSdk = 36

        defaultConfig {
            minSdk = 21
        }

        buildFeatures {
            buildConfig = true
            resValues = true
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }

    configure<AliucordExtension> {
        author("spixyy7", 0L, hyperlink = false)
        github("https://github.com/spixyy7/ghostusers-aliucord")
    }

    configure<KtlintExtension> {
        version.set(libs.versions.ktlint.asProvider())

        coloredOutput.set(true)
        outputColorName.set("RED")
        ignoreFailures.set(true)
    }

    configure<KotlinAndroidExtension> {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
            optIn.add("kotlin.RequiresOptIn")
        }
    }

    @Suppress("unused")
    dependencies {
        val compileOnly by configurations
        val implementation by configurations

        compileOnly(libs.discord)
        compileOnly(libs.aliucord)
        compileOnly(libs.kotlin.stdlib)
    }
}
