# Demo/Quickstart Parity and Enrollment Errors Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make FaceCheck Demo and FaceCheck Quickstart use one immersive source of truth and make enrollment failures diagnosable and visually terminal.

**Architecture:** Keep `facecheck-kmp/samples/android-quickstart` as the only Android sample source. Keep `sdk/demo-android` as a thin Gradle packaging wrapper that points its Kotlin, resources, manifest, and tests at that source. In `VerifyActivity`, make terminal capture transitions explicit: stop the challenge observer and loading layer before rendering retry/completion, and preserve only sanitized error metadata.

**Tech Stack:** Kotlin Multiplatform, Kotlin/JVM tests, Android Gradle Plugin, CameraX, ML Kit, Ktor multipart API, Android `adb`/logcat.

**Spec:** `docs/superpowers/specs/2026-08-20-demo-quickstart-parity-and-enrollment-errors.md`

## Global Constraints

- Use `facecheck-kmp/samples/android-quickstart` as the only sample implementation; do not copy the legacy Compose screens into another module.
- Keep the public enrollment contract as `FaceCheck.enroll(subjectId, camera, machine)` and multipart `subjectId`/`selfie` fields.
- Keep API keys in untracked `local.properties`; never print or commit keys, grants, subject IDs, coordinates, images, or request bodies.
- Preserve unrelated pending changes in both repositories; stage only files belonging to this plan.
- Follow `CONTRIBUTING.md`: behavior changes require a failing test first, and README/sample/API examples must remain synchronized.
- Validate both the canonical Quickstart APK and the packaged Demo APK on the authorized Xiaomi before claiming completion.

---

### Task 1: Freeze and verify one Demo/Quickstart source

**Files:**
- Modify: `/Users/baudelio/Documents/facecheck-pruebas/facecheck/sdk/settings.gradle.kts:30-39`
- Modify: `/Users/baudelio/Documents/facecheck-pruebas/facecheck/sdk/demo-android/build.gradle.kts:1-98`
- Modify: `/Users/baudelio/Documents/facecheck-pruebas/facecheck/sdk/gradle/libs.versions.toml:28-60`
- Create: `/Users/baudelio/Documents/facecheck-pruebas/facecheck/tools/verify-demo-parity.sh`
- Test: `/Users/baudelio/Documents/facecheck-pruebas/facecheck/sdk/demo-android/src/test` sourced from the canonical Quickstart tests

**Documentation references:**
- `facecheck-kmp/CONTRIBUTING.md:39-60` for the KMP source layout and single-platform seam.
- `facecheck-kmp/CONTRIBUTING.md:107-124` for source synchronization rules.
- `facecheck-kmp/samples/android-quickstart/build.gradle.kts` for the canonical API-key injection pattern.
- `facecheck/sdk/demo-android/build.gradle.kts` for the existing wrapper source-set redirection.

**Interfaces:**
- Consumes: canonical `VerifyActivity.kt`, manifest, resources, and tests.
- Produces: `:demo-android` with application ID `com.borealnetwork.facecheck.demo`, namespace compatible with `VerifyActivity`, and no compiled Kotlin files under `sdk/demo-android/src/main/kotlin`.

- [ ] **Step 1: Write the failing parity check.** Create `tools/verify-demo-parity.sh` that exits nonzero unless the Demo Gradle file points `kotlin.sourceSets.main` and `android.sourceSets.main` to `facecheck-kmp/samples/android-quickstart`, depends on `:facecheck-kmp`, and contains none of `Correo de la persona`, `URL del servicio`, or `Cargar modelos disponibles` in compiled source paths.
- [ ] **Step 2: Run the check against the current checkout.** Run:

  ```bash
  cd /Users/baudelio/Documents/facecheck-pruebas/facecheck
  tools/verify-demo-parity.sh
  ```

  Expected before the guard is complete: FAIL with the exact missing source/dependency assertion.
- [ ] **Step 3: Keep the wrapper-only implementation.** Point the Demo module at the canonical Quickstart Kotlin, manifest, resources, and tests; use `implementation(project(":facecheck-kmp"))`; inject `FACECHECK_API_KEY` from `/Users/baudelio/Documents/facecheck-pruebas/facecheck-kmp/local.properties`; do not restore the legacy Compose dependencies or `:facecheck-sdk` dependency.
- [ ] **Step 4: Make packaging deterministic.** Keep the canonical app namespace for `VerifyActivity`, set the Demo application ID and label only in the wrapper, and keep the Quickstart application ID/label in its own build. Do not make runtime UI behavior conditional on the package name.
- [ ] **Step 5: Run the check green.** Run the parity script plus:

  ```bash
  cd /Users/baudelio/Documents/facecheck-pruebas/facecheck/sdk
  ./gradlew :demo-android:testDebugUnitTest :demo-android:assembleDebug
  ```

  Expected: the wrapper tests and APK build pass, and the old UI source is not compiled.
- [ ] **Step 6: Commit only this task.** Stage the wrapper/settings/catalog/parity files; do not stage pending changes in `sdk/facecheck-sdk` or unrelated backend/UI files.

**Anti-pattern guards:**

- Do not copy `CameraScreen.kt`, `SettingsScreen.kt`, `DemoSettings.kt`, or `MainActivity.kt` into the canonical sample.
- Do not leave a second `FaceCheck.enroll(email = ...)` path reachable from the Demo build.
- Do not compare APK byte-for-byte; package metadata may differ. Compare source paths and sanitized UI text instead.

---

### Task 2: Model and test terminal enrollment states

**Files:**
- Modify: `samples/android-quickstart/src/main/kotlin/com/borealnetwork/facecheck/sample/EnrollmentCaptureFeedback.kt:26-35`
- Create: `samples/android-quickstart/src/main/kotlin/com/borealnetwork/facecheck/sample/EnrollmentTerminalPresentation.kt`
- Modify: `samples/android-quickstart/src/test/kotlin/com/borealnetwork/facecheck/sample/ImmersiveSampleFlowTest.kt:76-93`
- Create: `samples/android-quickstart/src/test/kotlin/com/borealnetwork/facecheck/sample/EnrollmentTerminalPresentationTest.kt`

**Documentation references:**
- `VerifyActivity.kt:407-439` for capture-state rendering and the challenge observer.
- `VerifyActivity.kt:489-550` for retry/completion rendering.
- `CapturePresentation.kt:14-40` for the distinction between active, finalizing, and done states.
- `EnrollmentCaptureFeedback.kt:26-35` for the retry-count contract.

**Interfaces:**
- Consumes: `EnrollmentAttempt`, `FaceCheckErrorCode`, and `FaceCheckException` metadata.
- Produces: a pure terminal presentation whose `showsLoading` is always `false` for retry and completion; a stable diagnostic code/message for the UI.

- [ ] **Step 1: Write failing pure tests.** Add tests that assert:

  ```kotlin
  val failure = EnrollmentTerminalPresentation.failure(
      attempt = EnrollmentAttempt.first,
      code = "INVALID_IMAGE",
      message = "No se pudo leer la imagen.",
  )
  assertFalse(failure.showsLoading)
  assertEquals("INVALID_IMAGE", failure.code)
  assertEquals("Intento 1 de 3", failure.attempt.label)
  ```

  Add a completion assertion with `showsLoading == false` and preserve the two retry transitions already covered by `ImmersiveSampleFlowTest`.
- [ ] **Step 2: Run the focused test red.** Run:

  ```bash
  cd /Users/baudelio/Documents/facecheck-pruebas/facecheck-kmp
  ./gradlew :samples:android-quickstart:testDebugUnitTest --tests '*EnrollmentTerminalPresentationTest'
  ```

  Expected: FAIL because the terminal presentation type/factory does not yet exist.
- [ ] **Step 3: Implement the smallest pure state model.** Store only `attempt`, `code`, `message`, `retryable`, and `showsLoading = false`. Derive the next attempt from `attempt.retry()`. Keep the user-facing fallback in Mexican Spanish and never include request data.
- [ ] **Step 4: Run the focused test green.** Re-run the exact Gradle command and then the full Quickstart unit-test task.
- [ ] **Step 5: Commit the state model and tests.** Use a behavior-only commit, separate from packaging changes.

**Anti-pattern guards:**

- Do not put a `View` or Android lifecycle object in the pure state model.
- Do not make `showsLoading` true for a retry card; the loading layer is controlled by the Activity transition.
- Do not use a raw backend body as the UI message.

---

### Task 3: Fix the Activity race and preserve the enrollment cause

**Files:**
- Modify: `samples/android-quickstart/src/main/kotlin/com/borealnetwork/facecheck/sample/VerifyActivity.kt:351-404`
- Modify: `samples/android-quickstart/src/main/kotlin/com/borealnetwork/facecheck/sample/VerifyActivity.kt:407-441`
- Modify: `samples/android-quickstart/src/main/kotlin/com/borealnetwork/facecheck/sample/VerifyActivity.kt:489-537`
- Modify: `samples/android-quickstart/src/test/kotlin/com/borealnetwork/facecheck/sample/ImmersiveSampleFlowTest.kt`

**Documentation references:**
- Copy the call pattern from `VerifyActivity.kt:351-365` and keep the canonical signatures from `FaceCheck.kt:137-179`.
- Use `FaceCheckException.code`, `.httpStatus`, `.isRetryable`, and `.details` from `FaceCheckException.kt:15-53`; do not invent error fields.
- Keep `FaceCheckApi` multipart behavior from `FaceCheckApi.kt:86-128` unchanged.

**Interfaces:**
- Consumes: `EnrollmentTerminalPresentation.failure(...)` from Task 2.
- Produces: terminal Activity behavior where a challenge observer cannot re-show the loading overlay after an error or completion.

- [ ] **Step 1: Add a failing Activity-state regression test.** Extend the sample presentation tests with the exact invariant: once enrollment enters failure or completion, `showsLoading == false`, and the terminal diagnostic retains the stable error code.
- [ ] **Step 2: Run the regression test red.** Run the focused Quickstart test task and confirm the new invariant fails against the current race-prone implementation.
- [ ] **Step 3: Capture the enrollment failure.** In the `FaceCheckException` catch for enrollment, retain `code.wire`, `httpStatus`, `isRetryable`, and a sanitized user-facing message instead of reducing the error to a boolean. For a `false` enrollment result with no exception, use a generic `ENROLLMENT_INCOMPLETE` presentation.
- [ ] **Step 4: Stop competing UI updates before rendering terminal state.** Before `renderEnrollmentRetry` or `renderEnrollmentComplete`:

  ```kotlin
  challengeJob?.cancel()
  loading.visibility = View.GONE
  progress.isIndeterminate = false
  cancelButton.visibility = View.INVISIBLE
  releaseCamera()
  ```

  Pass the terminal presentation into the retry card. Keep the camera frame/card screen, but do not keep an active camera session or collector behind it.
- [ ] **Step 5: Make retry start from a clean session.** The retry button must call `releaseCamera()` and create a new `sessionId`, `ChallengeMachine`, observer, loading view, and camera through `renderCapture(screen, next)`. The first “Empezar”/capture state must not inherit the old error or loading visibility.
- [ ] **Step 6: Run tests green and inspect the diff.** Run the focused test, the full Quickstart test task, and `git diff --check`.
- [ ] **Step 7: Commit the Activity transition fix separately.** Do not combine it with backend deployment or portal documentation.

**Anti-pattern guards:**

- Do not solve the race with arbitrary `delay()` calls.
- Do not swallow enrollment exceptions into the generic message only.
- Do not render the retry card while `observeChallenge` remains able to mutate the same `loading` view.
- Do not change retry count from three total attempts.

---

### Task 4: Add sanitized logcat diagnostics and diagnose the real backend rejection

**Files:**
- Modify: `samples/android-quickstart/src/main/kotlin/com/borealnetwork/facecheck/sample/VerifyActivity.kt:55-98`
- Modify: `samples/android-quickstart/src/main/kotlin/com/borealnetwork/facecheck/sample/VerifyActivity.kt:379-381`
- Test: `facecheck-kmp/src/commonTest/kotlin/com/borealnetwork/facecheck/FaceCheckLoggerTest.kt`

**Documentation references:**
- `FaceCheckLogger.kt:11-49` for redaction rules and `describeBytes`.
- `README.md:136-152` for exception semantics and the live-key grant rule.
- `FaceCheckApi.kt:150-277` for 4xx/5xx/transport retry behavior and error-envelope parsing.
- `FaceCheckApiTest.kt:210-358` for typed error and retry expectations.

**Interfaces:**
- Consumes: `FaceCheckLogSink`, `FaceCheckLogLevel`, and `FaceCheckException` public fields.
- Produces: logcat lines such as `enroll failed code=... httpStatus=... retryable=...`, with no key, grant, subject ID, image, URL query, or body.

- [ ] **Step 1: Write the logging regression test.** Add a logger test input containing a fake key, grant, subject ID, URL, and image description; assert the emitted diagnostic contains only the safe code/status/retryable fields.
- [ ] **Step 2: Run the logger test red.** Run `./gradlew :facecheck-kmp:testDebugUnitTest --tests '*FaceCheckLoggerTest*'` and confirm the new diagnostic assertion fails before the bridge is added.
- [ ] **Step 3: Configure the sample logger.** Before `FaceCheck.initialize`, install an Android `FaceCheckLogSink` with tag `FaceCheck` and set sample `FaceCheckConfig.logLevel` to `DEBUG`. Keep this configuration in the canonical Quickstart source so Demo receives it automatically.
- [ ] **Step 4: Log the caught failure safely.** Log operation, wire code, HTTP status, and retryable flag only. Keep the user-facing card readable and do not log `error.message` if it can contain backend-provided identifiers.
- [ ] **Step 5: Run the logger and full SDK tests green.** Include `FaceCheckLoggerTest`, API tests, sample tests, and `git diff --check`.
- [ ] **Step 6: Reproduce on Xiaomi with a clean log.** Run:

  ```bash
  adb -s 3d9337d1 logcat -c
  adb -s 3d9337d1 shell am force-stop com.borealnetwork.facecheck.demo
  adb -s 3d9337d1 shell monkey -p com.borealnetwork.facecheck.demo 1
  adb -s 3d9337d1 logcat -v threadtime -s FaceCheck:V AndroidRuntime:E
  ```

  Perform one enrollment using a newly generated ID. Record only the operation, error code, HTTP status, and retryable flag.
- [ ] **Step 7: Apply the contract-specific fix.** Use the observed code, not guesswork:
  - `INVALID_API_KEY`, `MISSING_API_KEY`, or `APP_DISABLED`: stop and refresh the local test configuration from the portal; do not change UI or backend behavior.
  - `INVALID_SUBJECT_ID` or `MISSING_SUBJECT_ID`: fix only the generated-ID/request contract and add a request test.
  - `ENROLLMENT_GRANT_REQUIRED` on a live key: configure the documented backend grant flow; never put a private grant in the APK. On a test key, verify the portal/backend test policy before changing it.
  - `INVALID_IMAGE`, `EMPTY_FILE`, `NO_FACE`, or `LIVENESS_FAILED`: retain the request contract and fix capture quality/gating, then reproduce with the oval/three-step UI.
  - `NETWORK_ERROR`, `TIMEOUT`, `KEY_SERVICE_UNAVAILABLE`, or `INTERNAL`: inspect the deployed Functions logs for the same timestamp and request stage before changing the client; deploy backend changes only if the server evidence identifies a defect.
- [ ] **Step 8: Commit diagnostics separately from any backend fix.** Never include local properties or raw logs.

**Anti-pattern guards:**

- Do not print the API key or paste it into a source file, log, commit, or plan.
- Do not retry a 4xx from the client; `FaceCheckApi` explicitly avoids that.
- Do not claim the enrollment is a backend bug until the sanitized error code and server log agree.

---

### Task 5: Align public documentation and remove the misleading legacy entry point

**Files:**
- Modify: `portal/src/pages/docs/QuickStart.tsx:276-377`
- Modify: `facecheck-kmp/README.md:86-172`
- Modify: `facecheck/sdk/demo-android/README.md`
- Review: `facecheck/sdk/facecheck-sdk` pending local changes before any removal

**Documentation references:**
- Copy the canonical setup/enrollment/verification snippets from `facecheck-kmp/README.md:92-159`.
- Keep the published portal QuickStart snippet synchronized with the KMP sample; the portal is not a second implementation.
- Follow `CONTRIBUTING.md:126-139` for version and sample documentation parity.

- [ ] **Step 1: Add a documentation regression check.** Search the public QuickStart docs for `correo`, `Cargar modelos`, and `email` in the enrollment example; make the check fail while the old snippet remains.
- [ ] **Step 2: Replace the old snippet with the canonical `subjectId` flow.** Show `FaceCheck.initialize`, `SubjectId.generate(apiKey)`, `AndroidCameraController`, `ChallengeMachine`, `FaceCheck.enroll(subjectId = ...)`, and `FaceCheck.verify(subjectId = ...)` using the exact signatures already present in the SDK.
- [ ] **Step 3: Document the packaging rule.** State that `sdk/demo-android` packages the canonical Quickstart source and must not be edited as a second UI. State that API keys remain local-only.
- [ ] **Step 4: Decide the legacy SDK disposition from current diff evidence.** Either remove the unused `sdk/facecheck-sdk` module in a dedicated cleanup change after its local edits are reconciled, or mark it explicitly as legacy and keep it out of Demo dependencies. Do not silently delete pending user changes.
- [ ] **Step 5: Run documentation checks and both builds.** Verify the portal compile/test command used by the repo, then run both sample Gradle builds.
- [ ] **Step 6: Commit documentation separately.** Do not mix portal copy changes with Android state-machine changes.

**Anti-pattern guards:**

- Do not document an email alias that the current KMP API does not accept.
- Do not claim client-side liveness is a security control; the README explicitly limits it to guidance.
- Do not describe the test API key as a secret stored in the APK.

---

### Task 6: Final parity, error, and Xiaomi verification

**Files:**
- Test: canonical Quickstart and Demo APKs
- Review: changed files in both repositories with `git status` and `git diff --check`

**Documentation references:**
- `README.md:92-102` for the expected immersive flow.
- `ImmersiveSampleFlowTest.kt:68-92` for three challenges and three total attempts.
- `CapturePresentationTest.kt:12-69` for active/finalizing presentation.
- `CONTRIBUTING.md:73-87` for required test discipline.

- [ ] **Step 1: Run canonical tests/build.**

  ```bash
  cd /Users/baudelio/Documents/facecheck-pruebas/facecheck-kmp
  ./gradlew :facecheck-kmp:testDebugUnitTest \
      :samples:android-quickstart:testDebugUnitTest \
      :samples:android-quickstart:assembleDebug
  ```

- [ ] **Step 2: Run Demo wrapper tests/build.**

  ```bash
  cd /Users/baudelio/Documents/facecheck-pruebas/facecheck/sdk
  ./gradlew :demo-android:testDebugUnitTest :demo-android:assembleDebug
  ```

- [ ] **Step 3: Install both variants explicitly.** Install the Quickstart APK as `com.borealnetwork.facecheck.sample` and the Demo APK as `com.borealnetwork.facecheck.demo`; launch each explicit Activity, not whichever icon Android happens to resolve.
- [ ] **Step 4: Compare sanitized setup UI.** Use `uiautomator dump` and assert both show the same setup strings: `Enrolar una persona`, `ID de persona`, `GENERAR ID ALEATORIO`, and `CONTINUAR A LA CÁMARA`; assert neither contains `Correo de la persona`, `URL del servicio`, `Llave de API`, or `Cargar modelos disponibles`.
- [ ] **Step 5: Exercise the error path on Xiaomi.** Generate a fresh ID, complete or intentionally fail capture, and assert the retry dialog contains no `Guardando enrolamiento…` text at the same time. Confirm `Volver a intentar` resets to `Intento 2 de 3` and `Aceptar` returns home.
- [ ] **Step 6: Exercise the success path.** With the authorized test key and valid backend response, complete left/right/front and assert direct transition to the completion dialog, then verify the ID appears in the local verification directory.
- [ ] **Step 7: Capture sanitized evidence.** Save only package, Activity, visible state, test command result, error code/status, and crash status. Do not save raw XML containing identifiers or raw logcat.
- [ ] **Step 8: Review Git state and publish only after approval.** Confirm `local.properties` is ignored/untracked, stage explicit feature files, verify remote refs, and push the canonical KMP and monorepo changes separately.

**Anti-pattern guards:**

- Do not declare PASS from a unit test alone; require fresh Xiaomi UI/log evidence.
- Do not test the wrong installed package or an old APK path.
- Do not publish a build whose UI source differs between Demo and Quickstart.

---

## Final review checklist

- [ ] The spec acceptance criteria are all covered by Tasks 1–6.
- [ ] No task invents an API or parameter absent from the KMP README/source.
- [ ] `git diff --check` passes in both repositories.
- [ ] All pending unrelated changes remain uncommitted and unstaged.
- [ ] The final report distinguishes code fix, backend diagnosis, build/install, and Xiaomi E2E evidence.
