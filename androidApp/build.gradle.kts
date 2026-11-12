plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

android {
    namespace = "com.fitzy.flowstate.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.fitzy.flowstate"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.material3)
    implementation(libs.androidx.activity.compose)
}
