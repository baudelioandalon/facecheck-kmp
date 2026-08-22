# Demo/Quickstart Parity and Enrollment Error Spec

## Goal

FaceCheck Demo and FaceCheck Quickstart must execute the same immersive Android
implementation, while enrollment failures must expose a sanitized cause and
transition to a terminal retry/accept state without leaving the saving overlay
visible.

## Source of truth

- `facecheck-kmp/` is the only SDK implementation used by the samples.
- `samples/android-quickstart/` is the only Android sample implementation.
- `sdk/demo-android/` is only a packaging/build entry point. It may change the
  application ID and label, but it must not contain or compile a second camera,
  setup, or enrollment UI.
- `sdk/facecheck-sdk/` is not a runtime dependency of the Demo. Its pending
  local changes must remain untouched until a separate legacy-removal decision.

## Enrollment terminal-state contract

The capture screen has mutually exclusive states:

1. active liveness: camera, oval, instruction, step/progress, and attempt count;
2. finalizing: one loading overlay with “Guardando enrolamiento…”;
3. failed: no loading overlay, camera session stopped, error card with retry
   count and sanitized error code, plus “Volver a intentar” or “Aceptar”;
4. completed: no loading overlay, camera session stopped, completion dialog.

The challenge observer must not be able to make the loading overlay visible
after state 3 or 4 has been rendered.

## Error diagnostics

The sample must log only non-sensitive diagnostic fields:

- operation (`enroll` or `verify`);
- backend/SDK error code;
- HTTP status when available;
- retryable flag.

It must never log API keys, grants, subject IDs, image bytes, coordinates, or
raw request bodies. The UI may show the stable error code and a user-facing
Spanish message, but not credentials or payloads.

## Backend contract

The canonical call remains `FaceCheck.enroll(subjectId, camera, machine)` and
the multipart request remains `subjectId`, optional `grant`, optional
`overwrite`, `selfie`, and optional `ine`. The client must not invent email
aliases or change the endpoint contract while diagnosing this failure.

## Acceptance criteria

- Building the Demo and Quickstart exercises the same Kotlin source files and
  produces the same capture UI; only package/label may differ.
- The old fields “URL del servicio”, “Llave de API”, “Correo de la persona” and
  “Cargar modelos disponibles” are absent from both installed sample screens.
- A failed enrollment never shows “Volvamos a intentarlo” together with
  “Guardando enrolamiento…”.
- The failure code is available in sanitized logcat output and in the retry
  diagnostic UI.
- A valid test enrollment reaches the completion dialog; an invalid request
  reaches the retry card without a crash.
- Canonical unit tests, Demo wrapper tests, both APK builds, and Xiaomi smoke
  tests pass before publishing.
