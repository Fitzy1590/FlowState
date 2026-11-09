import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.sqldelight.runtime)
            implementation(libs.coil.compose)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.tv.material)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.coil.network.okhttp)
            implementation(libs.androidx.work.runtime)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.sqldelight.sqlite.driver)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.jna.platform)
                implementation(libs.coil.network.okhttp)
            }
        }
    }
}

// Version comes from the release tag in CI (-PversionName / -PversionCode); defaults suit local builds.
val appVersionName = (findProperty("versionName") as String?) ?: "0.1.0"
val appVersionCode = ((findProperty("versionCode") as String?) ?: "1").toInt()

android {
    namespace = "com.fitzy.flowstate"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.fitzy.flowstate"
        minSdk = 24
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Release signing key comes from the environment (CI secrets). Without one, release builds use the debug key.
    val keystorePath = System.getenv("FLOWSTATE_KEYSTORE")
    if (keystorePath != null) {
        signingConfigs.create("release") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("FLOWSTATE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("FLOWSTATE_KEY_ALIAS")
            keyPassword = System.getenv("FLOWSTATE_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
}

sqldelight {
    databases {
        create("FlowDatabase") {
            packageName.set("com.fitzy.flowstate.db")
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.fitzy.flowstate.MainKt"
        jvmArgs += "-Dflowstate.version=$appVersionName"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "FlowState"
            // MSI needs a major version of at least 1, so 0.x.y is released as 1.x.y.
            packageVersion = appVersionName.split(".").let { v ->
                if (v.size == 3 && v.all { it.toIntOrNull() != null }) "${maxOf(v[0].toInt(), 1)}.${v[1]}.${v[2]}" else "1.0.0"
            }
        }
    }
}
