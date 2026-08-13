# Immersive Android Sample Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the technical quickstart form with guided subject selection and immersive liveness capture.

**Architecture:** Pure Kotlin models own navigation, local-subject normalization and liveness presentation. `VerifyActivity` renders those models with Android Views, persists only successful-enrolment emails, and layers a canvas guide over `PreviewView`.

**Tech Stack:** Kotlin, Android Views, CameraX, coroutines/Flow, Kotlin test.

**Spec:** `docs/superpowers/specs/2026-08-13-immersive-android-sample-design.md`

## Global Constraints

- Require camera, media-image and coarse-or-fine location permissions before attaching CameraX or starting FaceCheck.
- Never send coordinates, persist biometric material, or read the owner directory from the client API key.
- Use `LivenessState.instructionEs` and `LivenessState.progress` as the source of truth.
- Keep credentials only in ignored `local.properties`; do not print or commit them.

---

### Task 1: Pure flow and liveness presentation

**Files:**
- Create: `samples/android-quickstart/src/main/kotlin/com/borealnetwork/facecheck/sample/ImmersiveSampleFlow.kt`
- Create: `samples/android-quickstart/src/main/kotlin/com/borealnetwork/facecheck/sample/CapturePresentation.kt`
- Create: `samples/android-quickstart/src/test/kotlin/com/borealnetwork/facecheck/sample/ImmersiveSampleFlowTest.kt`
- Create: `samples/android-quickstart/src/test/kotlin/com/borealnetwork/facecheck/sample/CapturePresentationTest.kt`

**Interfaces:** Produces `SampleOperation`, `ImmersiveScreen`, `ImmersiveSampleFlow.begin(operation, email)`, `LocalSubjectDirectory.remember(existing, email)`, and `CapturePresentation.from(state)`.

- [ ] Write tests that assert a trimmed valid email creates `ImmersiveScreen.Capture`, invalid email stays in setup, remembered subjects are newest-first and deduplicated, and challenge index 1 of 3 shows `Reto 2 de 3`.
- [ ] Run `./gradlew :samples:android-quickstart:testDebugUnitTest --tests '*ImmersiveSampleFlowTest' --tests '*CapturePresentationTest'` and observe RED for missing types.
- [ ] Implement the minimal pure models and rerun the same command to GREEN.
- [ ] Commit with `feat: model immersive sample flow`.

### Task 2: Face guide overlay

**Files:**
- Create: `samples/android-quickstart/src/main/kotlin/com/borealnetwork/facecheck/sample/FaceGuideOverlay.kt`
- Modify: `samples/android-quickstart/src/main/kotlin/com/borealnetwork/facecheck/sample/CapturePresentation.kt`
- Modify: `samples/android-quickstart/src/test/kotlin/com/borealnetwork/facecheck/sample/CapturePresentationTest.kt`

**Interfaces:** Consumes `CapturePresentation`; produces `FaceGuideOverlay.render(presentation)`.

- [ ] Add a failing test that clamps ring progress to `0f..1f`.
- [ ] Run the focused presentation test and observe RED.
- [ ] Implement `ringProgress` and an Android View that draws a dim layer, transparent portrait oval, neutral border and accent arc with `ringProgress * 360f` sweep.
- [ ] Rerun the test to GREEN and commit `feat: add immersive face guide overlay`.

### Task 3: Render the sample screens

**Files:**
- Modify: `samples/android-quickstart/src/main/kotlin/com/borealnetwork/facecheck/sample/VerifyActivity.kt`
- Modify: `README.md`

**Interfaces:** Consumes Tasks 1-2 and existing `SessionPreconditions`, `RequiredPermissions`, `FaceCheck.enroll`, and `FaceCheck.verify`; produces runnable home, subject setup, capture and outcome screens.

- [ ] Render home and prerequisite copy before mode selection; render a separate setup screen with local-device choices for verification.
- [ ] Attach camera only inside capture; update overlay/instruction from `CapturePresentation`; close camera on cancellation, result and destruction.
- [ ] Persist only local directory emails after a successful enrolment and document the local-directory boundary in README.
- [ ] Run `./gradlew :samples:android-quickstart:testDebugUnitTest :samples:android-quickstart:assembleDebug`, install with `adb -s 3d9337d1 install -r samples/android-quickstart/build/outputs/apk/debug/android-quickstart-debug.apk`, then visually verify all screens on Xiaomi.
- [ ] Commit with `feat: redesign Android quickstart capture flow`.
