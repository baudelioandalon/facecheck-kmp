# FaceCheck SDK en iOS

Guía de integración del framework `FaceCheckSDK` en una app iOS: cómo generarlo,
cómo enlazarlo con SPM o CocoaPods, qué permisos pide, y cómo sustituir el
detector facial por uno propio basado en ML Kit.

El código vive en
[`facecheck-kmp/src/iosMain/kotlin/com/borealnetwork/facecheck/camera/`](../facecheck-kmp/src/iosMain/kotlin/com/borealnetwork/facecheck/camera/).

---

## 1. Qué implementa `iosMain`

| Archivo | Responsabilidad |
|---|---|
| `CameraController.ios.kt` | `AVCaptureSession` con cámara frontal, `AVCaptureVideoDataOutput` para el análisis y `AVCapturePhotoOutput` para la foto final. |
| `FaceDetectorBridge.kt` | La interfaz que separa el SDK del detector facial. `FrameOrientation` vive aquí. |
| `VisionFaceDetector.kt` | Implementación por defecto con el framework **Vision** de Apple. Sin dependencias externas. |
| `PixelBufferStats.kt` | Nitidez (varianza del laplaciano) y brillo medio sobre el recorte del rostro. |
| `JpegEncoding.kt` | `CMSampleBuffer` / `AVCapturePhoto` → JPEG vertical, sin espejo, al tamaño y calidad de `CameraOptions`. |

Dos salidas cuelgan de una sola sesión de captura porque responden a dos
preguntas distintas:

- **El flujo de análisis** (`AVCaptureVideoDataOutput`, con
  `alwaysDiscardsLateVideoFrames = true`) alimenta la máquina de retos. Descarta
  cuadros atrasados a propósito: una prueba de vida necesita saber qué está
  haciendo el rostro *ahora*, y una cola de buffers viejos haría que el SDK
  calificara un giro que el usuario terminó hace un segundo.
- **La foto** (`AVCapturePhotoOutput`) es la única imagen que se sube. Sale de
  ahí y no del video porque un cuadro de 720p tiene más ruido y menos resolución
  que una captura real, y toda la precisión del sistema descansa en esa imagen.
  Si no hay salida de foto utilizable (por ejemplo en el simulador) el SDK cae al
  flujo de análisis, con una advertencia en el log.

### Orientación: la sesión está fijada a vertical

Ambas conexiones se fijan a `AVCaptureVideoOrientationPortrait`, así que
AVFoundation entrega los buffers ya derechos y al detector se le pasa
`FrameOrientation.UP`. Es una limitación deliberada: la UI de una prueba de vida
es vertical, y fijarla aquí evita que el cálculo de pose y la codificación de la
foto tengan que seguir la rotación del dispositivo.

**Bloquea tu view controller en vertical.** Si tu app rota, el rostro seguirá
detectándose (el buffer se rota por hardware) pero la vista previa se verá
inclinada respecto al mundo real.

### Espejeo

- La **vista previa** se espeja si `CameraOptions.mirrorPreview` es `true` (lo
  es por defecto). Un usuario que se ve sin espejo gira la cabeza al lado
  contrario cuando se le pide "gira a la izquierda".
- La **conexión de análisis** y la **de foto** nunca se espejan. Eso es lo que
  permite que `VisionFaceDetector` conozca el signo de lo que lee, y lo que
  evita que la foto de referencia guardada quede volteada.

---

## 2. Generar el framework

El módulo produce un framework **estático** (`isStatic = true`) llamado
`FaceCheckSDK`. Kotlin/Native usa el nombre del framework como prefijo de los
nombres Objective-C (`FaceCheckSDKFaceCheck`, `FaceCheckSDKVerifyResult`, ...);
en Swift se ven sin prefijo.

Para un target suelto:

```bash
./gradlew :facecheck-kmp:linkReleaseFrameworkIosArm64            # dispositivo
./gradlew :facecheck-kmp:linkDebugFrameworkIosSimulatorArm64     # simulador
```

Para distribuirlo hace falta un **XCFramework**: un solo artefacto con el
binario de dispositivo y el de simulador. El build ya lo declara
(`XCFramework("FaceCheckSDK")` en `facecheck-kmp/build.gradle.kts`), así que la
tarea existe:

```bash
./gradlew :facecheck-kmp:assembleFaceCheckSDKReleaseXCFramework
# -> facecheck-kmp/build/XCFrameworks/release/FaceCheckSDK.xcframework
```

> **Estático quiere decir "Do Not Embed".** En *Target → General → Frameworks,
> Libraries, and Embedded Content*, `FaceCheckSDK.xcframework` debe quedar en
> **Do Not Embed**. Ponerlo en *Embed & Sign* es el error de integración más
> común con frameworks de Kotlin/Native estáticos: compila, enlaza, y truena al
> arrancar con un `dyld: Library not loaded`.

---

## 3. Integración con Swift Package Manager

Copia el `.xcframework` al repositorio de la app (o publícalo en un release) y
declara un `binaryTarget`.

**Local:**

```swift
// Package.swift
// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "MiApp",
    platforms: [.iOS(.v13)],
    products: [
        .library(name: "MiApp", targets: ["MiApp"]),
    ],
    targets: [
        .binaryTarget(
            name: "FaceCheckSDK",
            path: "Frameworks/FaceCheckSDK.xcframework"
        ),
        .target(
            name: "MiApp",
            dependencies: ["FaceCheckSDK"]
        ),
    ]
)
```

**Remoto** (recomendado para terceros, porque no obliga a guardar binarios en
git):

```swift
.binaryTarget(
    name: "FaceCheckSDK",
    url: "https://tu-cdn.example.com/borealnetwork/facecheck/1.1.0/FaceCheckSDK.xcframework.zip",
    checksum: "<salida de: swift package compute-checksum FaceCheckSDK.xcframework.zip>"
)
```

El framework usa Vision, AVFoundation, CoreImage y UIKit; todos son del sistema y
SPM los enlaza solo al importarlos desde el binario.

---

## 4. Integración con CocoaPods

El módulo **no** usa el plugin `kotlin("native.cocoapods")`, así que el camino es
un podspec que envuelve el XCFramework ya generado:

```ruby
# FaceCheckSDK.podspec
Pod::Spec.new do |s|
  s.name             = 'FaceCheckSDK'
  s.version          = '1.1.0'
  s.summary          = 'Verificación facial con prueba de vida.'
  s.homepage         = 'https://tu-dominio.example.com'
  s.license          = { :type => 'Commercial' }
  s.author           = { 'FaceCheck' => 'soporte@tu-dominio.example.com' }
  s.source           = { :http => 'https://tu-cdn.example.com/borealnetwork/facecheck/1.1.0/FaceCheckSDK.xcframework.zip' }

  s.ios.deployment_target = '13.0'
  s.vendored_frameworks   = 'FaceCheckSDK.xcframework'

  # Frameworks del sistema que el binario de Kotlin/Native referencia.
  s.frameworks = 'AVFoundation', 'Vision', 'CoreMedia', 'CoreVideo',
                 'CoreImage', 'ImageIO', 'UIKit', 'Foundation'
  s.libraries  = 'c++'
end
```

En el `Podfile` de la app:

```ruby
target 'MiApp' do
  use_frameworks!
  pod 'FaceCheckSDK', :podspec => 'https://tu-cdn.example.com/borealnetwork/facecheck/1.1.0/FaceCheckSDK.podspec'
end
```

Para desarrollo local basta con `pod 'FaceCheckSDK', :path => '../ruta/al/podspec'`.

---

## 5. Permisos en `Info.plist`

La cámara es obligatoria. Sin la clave, iOS **mata la app** en cuanto el SDK
intenta abrir la sesión de captura — no es un error recuperable.

```xml
<key>NSCameraUsageDescription</key>
<string>Usamos la cámara para verificar tu identidad con una selfie. La foto se envía de forma segura para comprobar que eres tú y no se comparte con terceros.</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>Usamos tu ubicación durante la verificación como una señal adicional de seguridad.</string>
```

Alternativas según el tono de tu app:

```xml
<!-- Más corta -->
<string>Necesitamos la cámara para tomar la selfie con la que verificamos tu identidad.</string>

<!-- Cuando además se captura la INE -->
<string>Usamos la cámara para tomar tu selfie y la foto de tu identificación, y así verificar que eres tú.</string>
```

El texto se muestra tal cual al usuario, así que va en español y sin jerga
técnica. Apple rechaza en revisión las descripciones genéricas del tipo "esta app
necesita acceso a la cámara".

Si además dejas elegir la INE desde la galería:

```xml
<key>NSPhotoLibraryUsageDescription</key>
<string>Te permitimos elegir la foto de tu identificación desde tus fotos.</string>
```

El SDK requiere una ubicación exacta reciente para crear la sesión de
verificación. La app host debe declarar `NSLocationWhenInUseUsageDescription`,
pedir el permiso antes de presentar la cámara e implementar
`LocationContextProvider`. No requiere micrófono. Si además dejas elegir la INE
desde la galería, declara también `NSPhotoLibraryUsageDescription`.

### Cuándo pedir el permiso

Pide cámara y ubicación antes de presentar la pantalla de verificación, y crea
un `LocationContextProvider` que devuelva una ubicación exacta reciente. Así el
SDK puede crear la sesión antes de arrancar el flujo de cámara:

```swift
AVCaptureDevice.requestAccess(for: .video) { granted in
    // Presenta la pantalla de verificación solo si granted == true;
    // si no, explica por qué hace falta y ofrece abrir Ajustes.
}
```

---

## 6. Uso desde Swift

```swift
import UIKit
import AVFoundation
import FaceCheckSDK

final class LivenessViewController: UIViewController {

    private var camera: IosCameraController?
    private var machine: ChallengeMachine?
    private let locationProvider = MyLocationContextProvider()

    @IBOutlet private weak var instructionLabel: UILabel!

    override func viewDidLoad() {
        super.viewDidLoad()

        // Once per app launch. Calling it twice with a different config throws.
        if !FaceCheck.shared.isInitialized {
            FaceCheck.shared.initialize(config: FaceCheckConfig(
                apiKey: "lk_test_xxxxxxxxxxxx",
                baseUrl: "https://us-central1-facecheck-mx.cloudfunctions.net",
                challengeCount: 2,
                connectTimeoutMs: 15_000,
                requestTimeoutMs: 60_000,
                socketTimeoutMs: 60_000,
                maxRetries: 2,
                retryBaseDelayMs: 500,
                livenessTimeoutMs: 90_000,
                liveness: LivenessConfig(
                    minFaceRatio: 0.25, maxFaceRatio: 0.90,
                    turnThresholdDeg: 25, centerToleranceDeg: 10, maxRollDeg: 20,
                    positioningHoldMs: 700, centerHoldMs: 600,
                    positioningTimeoutMs: 20_000, challengeTimeoutMs: 10_000,
                    captureTimeoutMs: 8_000, faceLostGraceMs: 1_500,
                    minDetectorScore: 0.85, minSharpness: 25,
                    minBrightness: 50, maxBrightness: 220
                ),
                logLevel: .none
            ))
        }

        let controller = CameraController_iosKt.createCameraController(
            host: CameraHost(viewController: self),
            options: CameraOptions(
                targetStillSize: 1080,
                jpegQuality: 92,
                useFrontCamera: true,
                mirrorPreview: true
            )
        ) as! IosCameraController
        camera = controller
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        // A CALayer does not participate in Auto Layout: resize it by hand or it
        // will be the wrong size on every device but the simulator you wrote it on.
        camera?.previewLayer.frame = view.bounds
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        Task { await runVerification() }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        machine?.cancel()
        camera?.stop()
    }

    private func runVerification() async {
        guard let camera else { return }

        // Built before the session so the UI can render the first instruction
        // instead of an empty screen while the camera warms up.
        //
        // `doNewChallengeMachine`, not `newChallengeMachine`: Objective-C reserves
        // the `new` prefix, so the exporter renames anything that starts with it.
        let machine = FaceCheck.shared.doNewChallengeMachine(random: KotlinRandom.companion)
        self.machine = machine
        observe(machine)

        do {
            let result = try await FaceCheck.shared.verify(
                subjectId: "persona_demo_01",
                camera: camera,
                locationProvider: locationProvider,
                machine: machine,
                compareWith: .enrollment
            )
            // `verified` is the backend's answer. Nothing the on-device machine
            // concluded is an authorisation.
            print("verificado: \(result.verified)")
        } catch let error as NSError {
            // FaceCheckException surfaces as NSError; `message` is Spanish prose
            // that can be shown to the user verbatim.
            instructionLabel.text = error.localizedDescription
        }
    }
}
```

### Observar el estado para pintar la UI

`ChallengeMachine.state` es un `StateFlow`. Desde Swift se consume con un
`FlowCollector`:

```swift
private final class StateCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    private let onState: (LivenessState) -> Void

    init(onState: @escaping (LivenessState) -> Void) {
        self.onState = onState
    }

    func emit(value: Any?) async throws {
        guard let state = value as? LivenessState else { return }
        await MainActor.run { self.onState(state) }
    }
}

private func observe(_ machine: ChallengeMachine) {
    let collector = StateCollector { [weak self] state in
        // Every state carries the exact Spanish line to show. Do not build one
        // from the state's type: `instructionEs` already handles the hints
        // ("acércate", "hay demasiada luz") that the raw type does not express.
        self?.instructionLabel.text = state.instructionEs
    }
    Task { try? await machine.state.collect(collector: collector) }
}
```

> **Los valores por defecto de Kotlin no cruzan a Swift.** `FaceCheckConfig`,
> `LivenessConfig` y `CameraOptions` tienen defaults en Kotlin, pero el
> exportador de Objective-C genera un único inicializador con **todos** los
> parámetros. Por eso los ejemplos de arriba los enumeran completos. Si te
> molesta, envuélvelos en un `extension` con tus propios defaults del lado Swift.

---

## 7. Inyectar tu propio `FaceDetectorBridge` (ML Kit)

El SDK **no** enlaza ML Kit. Hacerlo por cinterop ataría el SDK a una versión de
ML Kit, a un grafo transitivo de Firebase y a una configuración de linker
concretas, y cualquier consumidor que discrepe de una sola de esas cosas no
podría ni compilar. En su lugar, el SDK declara `FaceDetectorBridge`, trae una
implementación con Vision (que no necesita nada) y deja que tú inyectes la tuya.

Úsalo cuando quieras **paridad exacta con Android**: probabilidades de ojo
abierto ya calibradas y un `trackingId` real (ver §9).

```swift
import FaceCheckSDK
import MLKitVision
import MLKitFaceDetection

final class MLKitDetectorBridge: NSObject, FaceDetectorBridge {

    private let detector: FaceDetector

    override init() {
        let options = FaceDetectorOptions()
        options.performanceMode = .fast
        options.landmarkMode = .none
        options.classificationMode = .all      // eye-open probabilities
        options.isTrackingEnabled = true       // stable tracking ids
        options.minFaceSize = 0.15
        detector = FaceDetector.faceDetector(options: options)
        super.init()
    }

    func analyze(
        sampleBuffer: UnsafeMutableRawPointer,
        orientation: FrameOrientation,
        timestampMs: Int64
    ) -> FaceFrame? {
        // The buffer is only valid for the duration of this call.
        let buffer = Unmanaged<CMSampleBuffer>
            .fromOpaque(sampleBuffer)
            .takeUnretainedValue()

        let image = VisionImage(buffer: buffer)
        image.orientation = Self.uiOrientation(for: orientation)

        guard let faces = try? detector.results(in: image) else { return nil }

        guard let face = faces.max(by: {
            $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height
        }) else {
            // "Looked, found nobody" is a real observation the session reacts to.
            // Returning nil instead would mean "could not look", which it ignores.
            return FaceFrame(
                yaw: 0, pitch: 0, roll: 0,
                leftEyeOpen: nil, rightEyeOpen: nil,
                faceRatio: 0, trackingId: nil,
                timestampMs: timestampMs,
                quality: FrameQuality(sharpness: 120, brightness: 128, detectorScore: 0.99),
                faceCount: 0
            )
        }

        let frameWidth = CGFloat(CVPixelBufferGetWidth(
            CMSampleBufferGetImageBuffer(buffer)!
        ))

        return FaceFrame(
            // ML Kit's headEulerAngleY already matches FaceCheck's convention:
            // negative yaw is the subject turning to their own left. Do not flip.
            yaw: Float(face.headEulerAngleY),
            pitch: Float(face.headEulerAngleX),
            roll: Float(face.headEulerAngleZ),
            leftEyeOpen: face.hasLeftEyeOpenProbability
                ? KotlinFloat(value: Float(face.leftEyeOpenProbability)) : nil,
            rightEyeOpen: face.hasRightEyeOpenProbability
                ? KotlinFloat(value: Float(face.rightEyeOpenProbability)) : nil,
            faceRatio: Float(face.frame.width / frameWidth),
            trackingId: face.hasTrackingID ? KotlinInt(value: Int32(face.trackingID)) : nil,
            timestampMs: timestampMs,
            quality: FrameQuality(sharpness: 120, brightness: 128, detectorScore: 0.99),
            faceCount: Int32(faces.count)
        )
    }

    // Required: Kotlin interface defaults export as @required in Objective-C,
    // so Swift has to implement this even though Kotlin gives it a body.
    func close() {}

    private static func uiOrientation(for orientation: FrameOrientation) -> UIImage.Orientation {
        switch orientation {
        case .right: return .right
        case .left:  return .left
        case .down:  return .down
        default:     return .up
        }
    }
}
```

Y al construir la cámara:

```swift
let camera = CameraController_iosKt.createCameraController(
    host: CameraHost(viewController: self),
    detector: MLKitDetectorBridge(),
    options: CameraOptions(targetStillSize: 1080, jpegQuality: 92,
                           useFrontCamera: true, mirrorPreview: true)
)
```

`FrameQuality` con los valores por defecto (120 / 128 / 0.99) le dice a la
máquina "la calidad no es problema". Si quieres que las pistas de "acércate" y
"busca más luz" funcionen igual que con el detector de Vision, calcula nitidez y
brillo sobre el recorte del rostro como hace `PixelBufferStats.kt`.

> Si ya tienes tu propia `AVCaptureSession`, implementar `CameraController`
> directamente (son tres métodos) suele ser más limpio que usar el puente: te
> ahorra que el SDK abra una segunda sesión de captura.

---

## 8. Convención de signos: lo más importante de este documento

`FaceFrame` fija **una** convención para las dos plataformas, desde el punto de
vista del sujeto:

| Campo | Negativo | Positivo |
|---|---|---|
| `yaw` | gira a **su izquierda** | gira a su derecha |
| `pitch` | barbilla abajo | barbilla arriba |
| `roll` | inclina hacia su hombro izquierdo | hacia su hombro derecho |

La máquina de retos es código compartido. Si iOS difiere de Android en un signo,
los retos pasan en una plataforma y fallan en la otra mientras **todos los
umbrales se ven correctos en las dos**: el SDK le pediría al usuario de iOS girar
a la izquierda y luego lo reprobaría por obedecer. No hay test que atrape eso;
solo un dispositivo.

En `VisionFaceDetector` el signo se **deriva**, no se adivina:

1. El espejeo se pasa por constructor (`VisionFaceDetector(mirrored:)`) porque es
   una propiedad de la conexión que produjo el buffer, no una constante. El
   controlador fuerza `isVideoMirrored = false` justo para que esa entrada sea
   conocida.
2. La dirección del eje de Vision vive en `YAW_SIGN`, `PITCH_SIGN` y `ROLL_SIGN`.
   Son el único lugar del pipeline de pose donde hay un signo escrito a mano.

**Verificación en dispositivo (un minuto):** inicializa con
`logLevel: .debug`, corre una sesión y gira la cabeza a **tu propia izquierda**.
El `yaw` registrado debe ser **negativo**. Si sale positivo, invierte `YAW_SIGN`
en `VisionFaceDetector.kt`. Nunca lo compenses intercambiando `Challenge.TurnLeft`
con `TurnRight`, ni ensanchando un umbral: eso esconde el error en lugar de
arreglarlo, y reaparece en el siguiente reto que se agregue.

---

## 9. Diferencias conocidas contra Android

| Tema | Android (ML Kit) | iOS (Vision, por defecto) |
|---|---|---|
| Apertura de ojos | Probabilidad de un clasificador entrenado, ya calibrada 0..1 | Estimada del **EAR** (eye aspect ratio) y mapeada a 0..1 entre dos constantes geométricas |
| `trackingId` | Id estable en modo stream | **Siempre `null`** |
| `pitch` | Siempre disponible | Requiere **iOS 15+**; antes se reporta 0 |

### Por qué los umbrales de parpadeo son por plataforma

ML Kit devuelve una probabilidad de un modelo entrenado. Vision devuelve puntos
de landmarks y nada más, así que `VisionFaceDetector` deriva la apertura
dividiendo el alto del ojo entre su ancho y la mapea linealmente entre
`EAR_CLOSED = 0.10` y `EAR_OPEN = 0.30`. Eso es **geometría, no un modelo**: varía
con la forma del párpado, los lentes y el ángulo de la cámara mucho más que un
clasificador.

Consecuencia práctica: **un umbral medido contra ML Kit no se transfiere a iOS**.
Tratarlos como intercambiables produce un SDK que lee a la mitad de sus usuarios
como permanentemente dormidos. Por eso el SDK no usa el parpadeo como reto —
`FaceFrame.eyesClosed` existe solo para que la app evite capturar la foto
justo en un parpadeo — y por eso `EAR_CLOSED`/`EAR_OPEN` están documentadas como
lo que son: valores a calibrar contra usuarios reales, no constantes universales.

### Por qué `trackingId` es `null`

`VNDetectFaceLandmarksRequest` no hace seguimiento: cada cuadro genera un UUID
nuevo para el mismo rostro. Reportar ese UUID reprobaría la sesión en cada cuadro
por "cambio de persona"; sintetizar un id haría que el chequeo de sustitución de
`ChallengeMachine` se viera vivo sin probar nada. `null` es la respuesta honesta,
y la máquina lo acepta explícitamente.

**El chequeo de sustitución de rostro está inactivo en iOS con el detector por
defecto.** Un puente de ML Kit lo restaura. Recuerda de todos modos que ese
chequeo es UX, no seguridad: la decisión real la toma el backend sobre la foto.

### `pitch` antes de iOS 15

`VNFaceObservation.pitch` existe desde iOS 15. En versiones anteriores se reporta
`0`, que es el valor seguro: la máquina solo compara `abs(pitch)` contra una
tolerancia, así que degrada a "el pitch no se revisa" en lugar de bloquear a un
usuario que tiene la cabeza perfectamente bien puesta.

---

## 10. Diagnóstico

| Síntoma | Causa habitual |
|---|---|
| `dyld: Library not loaded` al arrancar | El XCFramework está en *Embed & Sign*. Cámbialo a **Do Not Embed** (§2). |
| La app muere sin log al abrir la cámara | Falta `NSCameraUsageDescription` (§5). |
| Pantalla negra, sin cuadros | Permiso denegado. Con `logLevel: .debug` verás `camera permission is denied or restricted`. |
| `no front camera on this device` | Estás en el simulador. La captura de foto cae al flujo de análisis y también falla; prueba en dispositivo. |
| Nunca detecta un rostro | La orientación que recibe el detector no corresponde al buffer. Con el pipeline del SDK siempre es `UP`; si implementaste tu propio `CameraController`, revisa qué `FrameOrientation` estás pasando. |
| La vista previa se ve estirada o corrida | No estás actualizando `previewLayer.frame` en `viewDidLayoutSubviews` (§6). |
| El reto "gira a la izquierda" nunca pasa | Signo de `yaw` invertido. Haz la verificación de §8. |

Para habilitar los logs:

```swift
FaceCheckLogger.shared.level = .debug
FaceCheckLogger.shared.sink = MiSink()   // opcional: puentea a tu propio logger
```

El logger enmascara llaves de API y correos en cada línea, y no acepta
`ByteArray`: no hay forma de escribir una selfie en la consola.
