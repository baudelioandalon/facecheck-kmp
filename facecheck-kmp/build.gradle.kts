import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    `maven-publish`
    signing
}

// Both come from gradle.properties, with fallbacks so a clone with a stripped
// gradle.properties still builds.
group = (findProperty("GROUP") as String?) ?: "org.borealnetwork"
version = (findProperty("VERSION_NAME") as String?) ?: "0.1.0"

kotlin {
    // explicitApi() would be the right default for a library other people
    // compile against, but it is off while the platform camera modules are
    // still landing; turn it on before 1.0.

    compilerOptions {
        // CameraHost is deliberately an expect/actual class: it is the one thing
        // in the API whose *type* genuinely differs per platform. The feature is
        // marked Beta, and the warning would otherwise fire on every compile.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        // Produces org.borealnetwork:facecheck-kmp-android, a plain AAR. That
        // coordinate is what an Android-only consumer should depend on unless
        // they specifically want the non-KMP build; see the README.
        publishLibraryVariants("release")
    }

    // One artifact holding device and both simulator slices. Without it the
    // only outputs are the per-target `link*Framework*` tasks, and there is
    // nothing an integrator can put in a `binaryTarget` or a podspec — which is
    // exactly what docs/IOS.md and the portal's installation page ask for.
    // Produces `assembleFaceCheckSDKReleaseXCFramework` ->
    // build/XCFrameworks/release/FaceCheckSDK.xcframework
    val xcf = XCFramework("FaceCheckSDK")

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        iosX64(),
    ).forEach { target ->
        target.binaries.framework {
            // Kept as FaceCheckSDK rather than renamed to match the product: it
            // is the symbol Swift code writes in `import`, docs/IOS.md is
            // written against it, and renaming buys nothing but a migration.
            baseName = "FaceCheckSDK"
            // Static: a biometric SDK that ships as a dynamic framework has to be
            // code-signed and embedded by every consumer, and Xcode's "embed &
            // sign" step is the single most common integration failure.
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)

            // `api`, not `implementation`: AndroidCameraController.attachPreview
            // takes a PreviewView and createCameraController's result is bound to
            // a LifecycleOwner, so both types are part of the surface an
            // integrator compiles against. Hiding them would only mean every
            // consumer has to re-declare CameraX at a version we do not control.
            api(libs.androidx.camera.core)
            api(libs.androidx.camera.view)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)

            // Bundled model: no Play Services dependency, no first-run download.
            implementation(libs.mlkit.face.detection)
        }

        androidUnitTest.dependencies {
            implementation(kotlin("test-junit"))
            implementation(libs.robolectric)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "com.borealnetwork.facecheck"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.android.buildTools.get()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// -----------------------------------------------------------------------------
// Publicación
//
// Todo lo de aquí abajo degrada a "no hace nada" cuando faltan las credenciales:
// `./gradlew build` no toca ninguna de estas tareas y `publishToMavenLocal`
// funciona sin firmar. Ver la sección "Publicar" del README.
// -----------------------------------------------------------------------------

// Maven Central exige un javadoc.jar por publicación. Kotlin/Native no genera
// javadoc, así que se publica uno vacío — es lo que hace todo el ecosistema KMP.
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(javadocJar)

        pom {
            name.set("FaceCheck KMP")
            description.set(
                "SDK multiplataforma (Android + iOS) de verificación facial con retos " +
                    "de vida en el dispositivo para FaceCheck.",
            )
            url.set("https://facecheck.borealnetwork.org")
            inceptionYear.set("2026")

            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("borealnetwork")
                    name.set("Boreal Network")
                    url.set("https://borealnetwork.org")
                }
            }
            scm {
                url.set("https://github.com/baudelioandalon/facecheck-kmp")
                connection.set("scm:git:https://github.com/baudelioandalon/facecheck-kmp.git")
                developerConnection.set("scm:git:ssh://git@github.com/baudelioandalon/facecheck-kmp.git")
            }
        }
    }

    repositories {
        val centralUser = findProperty("mavenCentralUsername") as String?
        val centralPassword = findProperty("mavenCentralPassword") as String?

        // Solo se declara si hay credenciales. Sin ellas el repositorio ni
        // siquiera existe, y `./gradlew tasks` no ofrece una tarea que fallaría.
        if (!centralUser.isNullOrBlank() && !centralPassword.isNullOrBlank()) {
            maven {
                name = "mavenCentral"
                url = uri(
                    "https://ossrh-staging-api.central.sonatype.com" +
                        "/service/local/staging/deploy/maven2/",
                )
                credentials {
                    username = centralUser
                    password = centralPassword
                }
            }
        }

        // Siempre disponible: sirve para revisar el POM y los artefactos antes
        // de subir nada. `./gradlew publishAllPublicationsToLocalStagingRepository`
        maven {
            name = "localStaging"
            url = layout.buildDirectory.dir("staging-repo").get().asFile.toURI()
        }
    }
}

signing {
    val signingKey = (findProperty("signingInMemoryKey") as String?)
        ?.replace("\\n", "\n")
    val signingPassword = (findProperty("signingInMemoryKeyPassword") as String?) ?: ""

    // Sin llave no se firma y no se falla: es lo que permite que `build` y
    // `publishToMavenLocal` funcionen en una clonación recién hecha.
    isRequired = !signingKey.isNullOrBlank()
    if (!signingKey.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}

// Gradle 8 se queja de que una tarea de publicación consume la salida de una
// tarea Sign sin declarar la dependencia. Con KMP hay una publicación por
// target, así que la relación se declara en bloque.
tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(tasks.withType<Sign>())
}
