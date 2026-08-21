import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
}

val localProperties = Properties().also { properties ->
    val localFile = rootProject.file("local.properties")
    if (localFile.isFile) localFile.inputStream().use(properties::load)
}
val facecheckApiKey = localProperties.getProperty("FACECHECK_API_KEY").orEmpty()

android {
    namespace = "com.borealnetwork.facecheck.sample"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.android.buildTools.get()

    defaultConfig {
        applicationId = "com.borealnetwork.facecheck.sample"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.compileSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "FACECHECK_API_KEY", "\"$facecheckApiKey\"")
        resValue("string", "app_name", "FaceCheck quickstart")
    }

    buildFeatures {
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

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.datetime)

    testImplementation(kotlin("test-junit"))
}
