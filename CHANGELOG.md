# Changelog

Este archivo sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/)
y el proyecto usa [versionado semántico](https://semver.org/lang/es/).

Los cambios incompatibles se anuncian aquí y en las notas del release.

## [No publicado]

### Agregado

- `FaceCheck.enrollmentModelProfiles()` consulta el catálogo seguro del backend,
  incluyendo el perfil default y los pesos exactos de artefactos.
- `FaceCheck.prepareEnrollment(...)` y `FaceCheck.prepareVerification(...)`
  crean sesiones de Active Liveness server-driven antes de abrir captura.
- `EnrollmentSession` y `VerificationSession` exponen
  `StateFlow<ActiveLivenessState>` y ejecutan una sola vez la captura canónica
  de cinco evidencias.

## [1.0.0] — 2026-08-13

Candidato preparado localmente; todavía no fue publicado ni etiquetado.

### Cambiado

- **Ruptura de API:** `FaceCheck.enroll` y `FaceCheck.verify` reemplazan el
  parámetro `email` por `subjectId`; el multipart usa exclusivamente
  `subjectId` y los errores son `MISSING_SUBJECT_ID` / `INVALID_SUBJECT_ID`.
- No existen sobrecargas, alias ni compatibilidad para `email`: las
  integraciones deben migrar de forma explícita.

### Agregado

- `SubjectId.generate(apiKey)` crea exactamente
  `sub_<huella>_<aleatorio>` (`^sub_[A-Z2-7]{10}_[A-Za-z0-9_-]{22}$`): la
  huella son los primeros 10 caracteres Base32 de SHA-256 de la llave y el
  sufijo son 16 bytes criptográficamente seguros en Base64URL sin relleno.

### Orden de lanzamiento

1. Desplegar Functions TypeScript y Python con el contrato `subjectId`.
2. Validar `/enroll` y `/verify` en un entorno autorizado con datos sintéticos.
3. Publicar KMP, Swift, Android y CLI 1.0.0.
4. Desplegar el portal con la documentación y el directorio compatibles.
5. Antes de etiquetar y hacer push, inspeccionar de nuevo `git status`, el diff
   y el historial local frente al remoto; etiquetar y hacer push solo con
   autorización explícita.

Este orden es una lista de ejecución; este cambio no despliega ni publica nada.

## [0.1.0] — 2026-08-11

Primera versión pública. Publicada como `org.borealnetwork:facecheck-kmp`.

### Agregado

- `FaceCheck`: punto de entrada con `initialize`, `enroll`, `verify`,
  `newChallengeMachine` y `shutdown`.
- `FaceCheckConfig`: llave de API, URL base, número de retos, timeouts,
  reintentos y nivel de log. El modo (`test` / `live`) se deduce del prefijo de
  la llave y no lo elige quien integra.
- `ChallengeMachine`: máquina de retos de vida sin reloj, sin cámara y sin
  código de plataforma. Emite `LivenessState` como `StateFlow` para pintar la
  instrucción en pantalla.
- Retos `TurnLeft`, `TurnRight` y `Center`, con plan aleatorio
  (`ChallengePlan.random`).
- `CameraController` con implementación de Android (CameraX + ML Kit con modelo
  empaquetado, sin Play Services) y de iOS (AVFoundation + Vision).
- Cliente HTTP sobre Ktor con reintentos con backoff y jitter para 5xx y errores
  de red.
- Modelos de respuesta: `EnrollResult`, `VerifyResult`, `VerifyChecks`,
  `FaceQuality`, `CompareWith` y `FaceCheckException` con `FaceCheckErrorCode`.
- Framework estático `FaceCheckSDK` para `iosArm64`, `iosSimulatorArm64` e
  `iosX64`. Guía de integración en [`docs/IOS.md`](docs/IOS.md).
- `samples/android-quickstart`: app mínima que se compila en CI, para que el
  ejemplo del README no pueda quedarse mintiendo tras un cambio de API.

### Limitaciones conocidas

Son de diseño, no pendientes: están explicadas en la sección **Limitaciones**
del [README](README.md#limitaciones).

- No hay anti-spoofing pasivo. `VerifyChecks.livenessEnforced` es `false` en
  todos los despliegues actuales y `spoofScore` viaja en `null`.
- Los retos de vida corren en el dispositivo y no son un control de seguridad.
- La comparación contra la INE (`CompareWith.INE` / `BOTH`) es experimental.

[No publicado]: https://github.com/baudelioandalon/facecheck-kmp/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/baudelioandalon/facecheck-kmp/releases/tag/v1.0.0
[0.1.0]: https://github.com/baudelioandalon/facecheck-kmp/releases/tag/v0.1.0
