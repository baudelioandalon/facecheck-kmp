# Changelog

Este archivo sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/)
y el proyecto usa [versionado semántico](https://semver.org/lang/es/).

Antes de 1.0.0 la API pública puede cambiar en cualquier versión menor. Los
cambios incompatibles se anuncian aquí y en las notas del release.

## [No publicado]

Nada todavía.

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

[No publicado]: https://github.com/baudelioandalon/facecheck-kmp/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/baudelioandalon/facecheck-kmp/releases/tag/v0.1.0
