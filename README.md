# FaceCheck KMP

SDK de **Kotlin Multiplatform** (Android + iOS) para el servicio de verificación
facial [FaceCheck](https://facecheck.borealnetwork.org).

Hace tres cosas, y solo tres:

1. **Guía al usuario** con una sesión de retos de vida en pantalla ("gira la
   cabeza a la izquierda", "mira de frente") hasta conseguir una foto frontal,
   nítida y bien iluminada.
2. **Captura esa foto** con la cámara frontal — CameraX + ML Kit en Android,
   AVFoundation + Vision en iOS.
3. **La sube** al backend de FaceCheck, que decide si corresponde a la persona
   registrada.

El SDK **no decide nada**. La comparación, el umbral y el veredicto viven en el
servidor; el dispositivo nunca ve un score ni un umbral. Un `VerifyResult` con
`verified = true` es la respuesta del backend, no una conclusión del teléfono.

| | |
|---|---|
| Artefacto | `org.borealnetwork:facecheck-kmp` |
| Paquete | `com.borealnetwork.facecheck` |
| Versión | `0.1.0` |
| Licencia | Apache 2.0 |
| Android | `minSdk 24`, `compileSdk 36` |
| iOS | framework estático `FaceCheckSDK` (`iosArm64`, `iosSimulatorArm64`, `iosX64`) |

> ¿Tu app es solo de Android y no quieres Kotlin Multiplatform en tu build?
> Usa [`facecheck-android`](https://github.com/baudelioandalon/facecheck-android):
> misma API, misma versión, una AAR normal sin metadatos de KMP.

---

## Instalación

### Android / Kotlin Multiplatform

En `gradle/libs.versions.toml`:

```toml
[versions]
facecheck = "0.1.0"

[libraries]
facecheck-kmp = { module = "org.borealnetwork:facecheck-kmp", version.ref = "facecheck" }
```

En el módulo:

```kotlin
dependencies {
    implementation(libs.facecheck.kmp)
}
```

O directo, sin catálogo:

```kotlin
implementation("org.borealnetwork:facecheck-kmp:0.1.0")
```

Un consumidor de **solo Android** puede depender del variante Android
publicado, `org.borealnetwork:facecheck-kmp-android:0.1.0`, aunque normalmente
Gradle lo resuelve solo desde la coordenada de arriba.

El SDK arrastra CameraX y el detector facial de ML Kit **empaquetado** (no el de
Play Services): no necesita cuenta de Google en el dispositivo ni descarga el
modelo en el primer arranque, a cambio de ~3 MB de APK.

El permiso de cámara ya viene declarado en el manifest de la librería; pedírselo
al usuario en tiempo de ejecución sigue siendo trabajo de tu app.

### iOS

El framework se genera desde este repo y se enlaza con Swift Package Manager o
CocoaPods. El procedimiento completo, incluidos el XCFramework, los permisos de
`Info.plist` y un ejemplo en Swift, está en **[`docs/IOS.md`](docs/IOS.md)**.

```bash
./gradlew :facecheck-kmp:linkReleaseFrameworkIosArm64
```

---

## Ejemplo

Android, con vistas. La versión completa y compilable está en
[`samples/android-quickstart`](samples/android-quickstart) — se construye en CI
justamente para que este fragmento no pueda quedarse mintiendo.

```kotlin
// Una sola vez, al arrancar la app.
FaceCheck.initialize(
    FaceCheckConfig(
        apiKey = "lk_test_…",                                   // del portal
        baseUrl = "https://us-central1-facecheck-mx.cloudfunctions.net",
    ),
)

// Por sesión.
val camera = AndroidCameraController(host = CameraHost(activity))
camera.attachPreview(previewView)

// Se construye antes de arrancar para poder pintar la primera instrucción
// de inmediato, en vez de una pantalla vacía hasta el primer cuadro.
val machine = FaceCheck.newChallengeMachine()
lifecycleScope.launch {
    machine.state.collect { statusView.text = it.instructionEs }
}

lifecycleScope.launch {
    try {
        val result = FaceCheck.verify(
            email = "persona@ejemplo.com",
            camera = camera,
            machine = machine,
        )
        statusView.text = if (result.verified) {
            "Verificado"
        } else {
            result.messageEs ?: "No coincide"
        }
    } catch (e: FaceCheckException) {
        statusView.text = "${e.code}: ${e.message}"
    } finally {
        camera.close()
    }
}
```

Un rostro que simplemente **no coincide** no es una excepción: regresa como
`VerifyResult(verified = false)` con una razón. `FaceCheckException` es para
sesiones de vida fallidas, problemas de red y peticiones rechazadas.

Para registrar el rostro de referencia de alguien se usa `FaceCheck.enroll(...)`.
Con una llave `lk_live_` hace falta además un **grant** firmado por tu propio
backend: la llave de API viaja dentro del APK y por lo tanto no prueba nada
sobre quién está llamando. Ver
[Grants de registro](https://facecheck.borealnetwork.org/docs/grants).

---

## Documentación

- **[Documentación de FaceCheck](https://facecheck.borealnetwork.org/docs)** — la
  fuente principal.
- [Instalación del SDK](https://facecheck.borealnetwork.org/docs/sdk)
- [Referencia de la API del SDK](https://facecheck.borealnetwork.org/docs/sdk/referencia)
- [Retos de vida](https://facecheck.borealnetwork.org/docs/sdk/retos)
- [Grants de registro](https://facecheck.borealnetwork.org/docs/grants)
- [Umbrales y modelo](https://facecheck.borealnetwork.org/docs/umbrales)
- [Códigos de error](https://facecheck.borealnetwork.org/docs/errores)
- En este repo: [`docs/IOS.md`](docs/IOS.md) para la integración en iOS.

---

## Limitaciones

Esta sección no es una lista de pendientes. Es lo que el SDK **no** hace, dicho
antes de que alguien construya encima algo que dependa de que sí lo hiciera.

### No hay anti-spoofing pasivo

No existe ningún modelo que mire la foto y dictamine si vino de una cara real o
de una pantalla. El modelo de referencia que se evaluó puntúa "ataque" para
prácticamente cualquier entrada, caras vivas incluidas, así que el backend lo
registra como telemetría y **nunca** decide con él.

En la práctica eso quiere decir:

- `VerifyChecks.livenessEnforced` es `false` en todos los despliegues actuales.
- `VerifyResult.spoofScore` y `EnrollResult.spoofScore` viajan en `null`.
- `VerifyChecks.liveness` es informativo. No lo uses como condición.

Un video en reproducción, una máscara 3D o un deepfake en tiempo real **no** son
detectados por este SDK.

### Los retos de vida corren en el dispositivo y no son un control de seguridad

`ChallengeMachine` se ejecuta en un teléfono que el atacante controla. Todo lo
que ve son unos cuantos números (`yaw`, `pitch`, nitidez, brillo) producidos por
código que corre en ese mismo teléfono. Quien tenga el dispositivo rooteado,
enganche el detector o alimente una cámara virtual no necesita girar la cabeza:
emite `yaw = -30f` y el reto pasa. Ni más retos ni umbrales más estrictos
cambian eso, porque el problema no es qué valores se exigen sino **quién los
calcula**.

Para lo que sí sirve:

- **Guiar.** Lleva a un usuario cooperativo a producir una buena foto frontal.
  Es de lo que depende la precisión de todo el sistema, y es lo que la mayoría
  de la gente falla sin instrucciones.
- **Subir el piso.** Derrota el ataque casual — una foto impresa o una imagen en
  otro teléfono — porque una foto no gira la cabeza.

Un `LivenessState.Done` significa "capturamos una foto usable", nunca "esta
persona es real". El SDK no manda al servidor ninguna afirmación sobre la
sesión de vida, y el servidor no la aceptaría: tomarla como autorización sería
poner la frontera de seguridad dentro del proceso del atacante.

**Si estás autorizando algo con consecuencias** (un movimiento de dinero, un
cambio de credenciales), la verificación facial es una señal más, no la única.
Combínala con controles que no vivan en el dispositivo.

### La comparación contra INE es experimental

`CompareWith.INE` y `CompareWith.BOTH` comparan la selfie contra el retrato de
la credencial registrada. Funciona, pero:

- El umbral del canal INE se calibró contra **una** credencial real. Ese margen
  no es una medición estadística; es una observación.
- Una INE fotografiada es un caso difícil: retrato pequeño, impreso, con
  hologramas y reflejos. Cuando el detector no encuentra el rostro en la
  credencial, la comparación no corre.
- El canal solo se comporta con el modelo ArcFace. Con los modelos ligeros que
  se evaluaron el margen se colapsa casi a cero, es decir, no distingue una
  coincidencia legítima de un falso positivo.
- `CompareWith.INE` a secas **siempre** se amplía a `BOTH` en el backend:
  resolver una verificación con el umbral más flojo de los dos, por sí solo,
  sería una regresión de seguridad. Lo que pides es un mínimo, no un máximo; el
  servidor puede endurecerlo y nunca lo suaviza.

Trátalo como una señal adicional en un flujo supervisado, no como comprobación
documental automática.

### Otras cosas que conviene saber

- **La llave de API no es un secreto.** Va dentro de un APK o un IPA. El backend
  está diseñado sobre esa premisa: reemplazar un registro exige además una
  selfie que ya coincida con la plantilla guardada, y `/verify` no regresa
  score. No agregues controles que supongan que la llave es confidencial.
- **`/verify` no devuelve similitud, y no la va a devolver.** Un score junto con
  su umbral convierte el endpoint en un oráculo de distancia: quien tenga la
  llave puede optimizar un morph contra ese número hasta llegar a
  `verified = true` contra una plantilla que nunca vio — y la imagen resultante
  reconstruye aproximadamente el rostro registrado. Los scores sí quedan en el
  dashboard del tenant, donde la persona a la que se está sondeando no los lee.
- **La sesión está fijada en vertical.** Bloquea tu pantalla en portrait.
- **Los textos para el usuario final están en español** (`instructionEs`,
  `messageEs`, `hintEs`). No hay localización todavía.
- **`explicitApi()` está apagado** mientras aterrizan los módulos de cámara. Se
  encenderá antes de la 1.0, lo que puede cambiar la visibilidad de algunas
  declaraciones.

---

## Compilar

Requiere **JDK 21** y el SDK de Android con `platforms;android-36` y
`build-tools;36.1.0`.

```bash
./gradlew build -x test      # compila librería y sample
./gradlew test               # pruebas de commonMain en Android y en el simulador de iOS
```

Las versiones de build-tools y de plataforma están fijadas en
`gradle/libs.versions.toml` a propósito: en una máquina nueva el build falla
diciendo qué instalar, en vez de bajarse solo un componente bajo una licencia
que nadie aceptó.

Los targets de iOS solo compilan en macOS. En Linux se saltan
(`kotlin.native.ignoreDisabledTargets=true`) y `./gradlew build` sigue pasando;
por eso CI tiene un job aparte en macOS.

---

## Publicar

El artefacto va a Maven Central como `org.borealnetwork:facecheck-kmp`. **CI no
publica**: subir es una acción manual desde una máquina que tenga la llave GPG.

Las credenciales son *placeholders comentados* en `gradle.properties` y el build
funciona sin ellas — sin credenciales el repositorio remoto ni siquiera se
declara, y sin llave GPG no se firma y tampoco falla. Eso es lo que permite que
una clonación recién hecha compile y publique en local sin configurar nada.

Ponlas en `~/.gradle/gradle.properties` (fuera del repo) o en variables
`ORG_GRADLE_PROJECT_*`:

```properties
mavenCentralUsername=…
mavenCentralPassword=…
signingInMemoryKey=-----BEGIN PGP PRIVATE KEY BLOCK-----\n…
signingInMemoryKeyPassword=…
```

La llave se exporta en una sola línea con `\n` literales:

```bash
gpg --armor --export-secret-keys <KEYID> | awk 'NR>1{printf "\\n"} {printf "%s", $0}'
```

Luego:

```bash
./gradlew publishAllPublicationsToLocalStagingRepository   # ensayo: revisa el POM en build/staging-repo
./gradlew publishToMavenLocal                              # ~/.m2, para probar contra otra app
./gradlew publishAllPublicationsToMavenCentralRepository   # sube de verdad
```

La última tarea **solo existe si hay credenciales**: sin ellas el repositorio
remoto no se declara, y `./gradlew tasks` no ofrece una tarea que fallaría de
todos modos. Si no aparece, es que Gradle no está leyendo tus propiedades.

Después hay que cerrar y liberar el deployment en
[central.sonatype.com](https://central.sonatype.com).

Al subir de versión: cambia `VERSION_NAME` en `gradle.properties`, actualiza el
`CHANGELOG.md`, y **sube la misma versión en
[`facecheck-android`](https://github.com/baudelioandalon/facecheck-android)**
después de resincronizar su espejo. Los dos artefactos comparten numeración
justamente para que `0.1.0` signifique el mismo código en los dos.

---

## Contribuir

Ver [`CONTRIBUTING.md`](CONTRIBUTING.md). Lo más importante: cualquier cambio al
código común hay que replicarlo en `facecheck-android`, y hay un script que lo
hace — no se copia a mano.

## Licencia

Apache License 2.0 — ver [`LICENSE`](LICENSE).

Copyright 2026 Boreal Network.
