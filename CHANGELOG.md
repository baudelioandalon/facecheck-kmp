# Changelog

Este archivo sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/)
y el proyecto usa [versionado semántico](https://semver.org/lang/es/).

Los cambios incompatibles se anuncian aquí y en las notas del release.

## [No publicado]

Nada todavía.

## [1.1.1] — 2026-08-29

### Corregido

- El cifrado de solicitudes en Android usa `android.util.Base64`, compatible
  con el `minSdk 24` declarado, en lugar de APIs de Java disponibles solo desde
  Android 8 (API 26).
- El quickstart declara la cámara como hardware opcional para no excluir
  ChromeOS ni fallar la validación de lint del artefacto de ejemplo.

### Cambiado

- El release ejecuta `check` y `lintDebug` antes de firmar, registra el tag
  únicamente después del cierre exitoso en Sonatype y usa ese tag para evitar
  republicaciones durante el retraso del índice de Maven Central.

## [1.1.0] — 2026-08-29

Versión aditiva que homologa el SDK móvil con los contratos actuales de
FaceCheck sin exponer operaciones administrativas en el dispositivo.

### Agregado

- Enrolamiento y verificación con `subjectId`, ubicación y metadatos del
  dispositivo, además de sesiones alojadas para completar el flujo en web.
- Captura de INE frente y reverso, consulta de su estado y verificación facial
  contra el rostro enrolado o contra la INE.
- Active Liveness server-driven con evidencias canónicas y modelos de error
  consistentes entre Android e iOS.
- Branding de compañía de solo lectura para aplicar nombre, icono, mensaje y
  color primario sin permitir que el SDK sobrescriba la configuración central.
- Identidad de compañía (`ID_COMPANY`) de solo lectura y cifrado de solicitudes
  cuando el backend lo requiere.

### Seguridad

- Borrar o bloquear personas, listar la cuenta completa, configurar webhooks y
  rotar secretos permanecen exclusivamente en servicios, CLI y portal.

## [1.0.0] — 2026-08-13

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

[No publicado]: https://github.com/baudelioandalon/facecheck-kmp/compare/v1.1.1...HEAD
[1.1.1]: https://github.com/baudelioandalon/facecheck-kmp/releases/tag/v1.1.1
[1.1.0]: https://github.com/baudelioandalon/facecheck-kmp/releases/tag/v1.1.0
[1.0.0]: https://github.com/baudelioandalon/facecheck-kmp/releases/tag/v1.0.0
[0.1.0]: https://github.com/baudelioandalon/facecheck-kmp/releases/tag/v0.1.0
