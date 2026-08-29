plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
}

import java.util.Properties

private val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}

private fun localProperty(name: String): String =
    localProperties.getProperty(name).orEmpty().trim()

private fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""

android {
    namespace = "com.borealnetwork.facecheck.sample"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.android.buildTools.get()

    defaultConfig {
        applicationId = "com.borealnetwork.facecheck.sample"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.compileSdk.get().toInt()
        versionCode = 1
        versionName = "1.1.0"

        buildConfigField("String", "FACECHECK_BASE_URL", buildConfigString(localProperty("FACECHECK_BASE_URL")))
        buildConfigField("String", "FACECHECK_API_KEY", buildConfigString(localProperty("FACECHECK_API_KEY")))
        buildConfigField("String", "FACECHECK_SUBJECT_ID", buildConfigString(localProperty("FACECHECK_SUBJECT_ID")))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            // Sin firma y sin minificar: el sample existe para compilar, no
            // para distribuirse. `assembleRelease` produce un APK sin firmar.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":facecheck-kmp"))
    implementation(project(":samples:immersive-ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.datetime)

    testImplementation(kotlin("test-junit"))
}
