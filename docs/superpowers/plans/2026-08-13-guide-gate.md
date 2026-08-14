# Face-guide gate and stability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent enrollment or verification from progressing unless the entire detected face remains inside the visible guide, and require a three-second stable frontal start.

**Architecture:** Extend the KMP camera-frame contract with a normalized rectangle and guide-compliance bit. A pure common `ChallengeMachine` treats guide non-compliance as a blocking state; Android's quickstart computes that bit from one shared oval geometry used both by CameraX-frame mapping and `FaceGuideOverlay`. Android and iOS adapters populate normalized bounds, while only hosts that enable the guide gate enforce it.

**Tech Stack:** Kotlin Multiplatform, Kotlin coroutines/Flow, CameraX + ML Kit, AVFoundation + Vision, Android Views, Kotlin test.

**Spec:** `docs/superpowers/specs/2026-08-13-guide-gate-design.md`

**Execution order override (user-directed):** Implement Tasks 1–5 and perform
their static reviews before running automated or device tests. Task 6 creates
and runs the complete verification matrix at the end. This deliberately trades
early feedback for one consolidated validation pass.

## Global Constraints

- The initial frontal hold is exactly `3_000L` ms and its countdown is `3`, `2`, `1`.
- A face must be fully inside the oval for positioning, both turn legs, and the final center hold to advance.
- Leaving the guide resets active holds but does not pause the existing step timeout.
- Do not transmit guide geometry, preview coordinates, or biometric data to the backend.
- Preserve the current public camera API for hosts that do not opt into a guide.
- Keep machine logic pure: time comes only from `FaceFrame.timestampMs`.

---

## File structure

| File | Responsibility |
|---|---|
| `facecheck-kmp/src/commonMain/.../liveness/FaceFrame.kt` | Normalized face-rectangle contract and per-frame guide result. |
| `facecheck-kmp/src/commonMain/.../liveness/LivenessState.kt` | Recoverable `OUTSIDE_GUIDE` coaching copy. |
| `facecheck-kmp/src/commonMain/.../liveness/ChallengeMachine.kt` | Block/restart all liveness advancement outside the guide. |
| `facecheck-kmp/src/commonTest/.../liveness/Frames.kt` | Guide-aware frame fixture. |
| `facecheck-kmp/src/commonTest/.../liveness/ChallengeMachineTest.kt` | Engine-level guide and hold regression tests. |
| `facecheck-kmp/src/androidMain/.../camera/FrameGeometry.kt` | ML Kit rectangle normalization. |
| `facecheck-kmp/src/iosMain/.../camera/VisionFaceDetector.kt` | Vision rectangle normalization. |
| `samples/android-quickstart/.../FaceGuideGeometry.kt` | Single pure oval/containment geometry for the sample. |
| `samples/android-quickstart/.../FaceGuideOverlay.kt` | Draw the oval from `FaceGuideGeometry`. |
| `facecheck-kmp/src/androidMain/.../camera/AndroidCameraController.kt` | Map the ML Kit face rectangle into the actual `PreviewView` viewport before evaluating the guide. |
| `samples/android-quickstart/.../PreviewFaceGuide.kt` | Small Android callback supplying the exact visible oval to the controller. |
| `samples/android-quickstart/.../EnrollmentSessionPolicy.kt` | Three-second initial hold for enrollment. |
| `samples/android-quickstart/.../CapturePresentation.kt` | Spanish countdown derived from hold progress. |
| `samples/android-quickstart/.../VerifyActivity.kt` | Build and use one overlay/gate pair for each capture. |
| `samples/android-quickstart/src/test/.../FaceGuideGeometryTest.kt` | Oval containment and boundary tests. |
| `samples/android-quickstart/src/test/.../CapturePresentationTest.kt` | Countdown and guide hint presentation tests. |

### Task 1: Make guide compliance part of the pure liveness contract

**Files:**
- Modify: `facecheck-kmp/src/commonMain/kotlin/com/borealnetwork/facecheck/liveness/FaceFrame.kt`
- Modify: `facecheck-kmp/src/commonMain/kotlin/com/borealnetwork/facecheck/liveness/LivenessState.kt`
- Modify: `facecheck-kmp/src/commonTest/kotlin/com/borealnetwork/facecheck/liveness/Frames.kt`
- Test later (Task 6): `facecheck-kmp/src/commonTest/kotlin/com/borealnetwork/facecheck/liveness/FaceFrameTest.kt`

**Interfaces:**
- Produces `NormalizedFaceBounds(left: Float, top: Float, right: Float, bottom: Float)` with values in `[0f, 1f]`, `left < right`, and `top < bottom`.
- Produces `FaceFrame.bounds: NormalizedFaceBounds? = null` and `FaceFrame.insideGuide: Boolean = true`.
- Produces `PositioningHint.OUTSIDE_GUIDE("Vuelve a colocar tu rostro dentro del óvalo")`.

- [ ] **Step 1: Add the minimal common fields and guide hint**

```kotlin
@Test fun `normalized bounds reject an inverted horizontal edge`() {
    assertFailsWith<IllegalArgumentException> {
        NormalizedFaceBounds(left = .7f, top = .2f, right = .3f, bottom = .8f)
    }
}

@Test fun `fixture can mark a detected face outside the guide`() {
    assertFalse(frame(atMs = 0, insideGuide = false).insideGuide)
}
```

- [ ] **Step 2: Update the fixture API without running it yet**

```kotlin
data class NormalizedFaceBounds(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(left < right && top < bottom)
    }
}

data class FaceFrame(/* existing fields */, val bounds: NormalizedFaceBounds? = null,
                     val insideGuide: Boolean = true)
```

Add `insideGuide` to the `frame()` fixture with a default of `true` so pre-existing tests retain their intended ideal face.

- [ ] **Step 3: Commit the pure contract**

```bash
git add facecheck-kmp/src/commonMain/kotlin/com/borealnetwork/facecheck/liveness/{FaceFrame,LivenessState}.kt \
  facecheck-kmp/src/commonTest/kotlin/com/borealnetwork/facecheck/liveness/{FaceFrameTest,Frames}.kt
git commit -m "feat: expose guide compliance in liveness frames"
```

### Task 2: Gate every liveness transition and prove the three-second reset behavior

**Files:**
- Modify: `facecheck-kmp/src/commonMain/kotlin/com/borealnetwork/facecheck/liveness/ChallengeMachine.kt`
- Modify: `facecheck-kmp/src/commonTest/kotlin/com/borealnetwork/facecheck/liveness/ChallengeMachineTest.kt`

**Interfaces:**
- Consumes `FaceFrame.insideGuide` and `PositioningHint.OUTSIDE_GUIDE` from Task 1.
- Produces `LivenessState.Positioning(OUTSIDE_GUIDE, 0f)` outside the guide.
- Produces `ChallengeActive(..., hint = OUTSIDE_GUIDE)` without changing phase, evidence, or index.

- [ ] **Step 1: Add one guide guard before all pose scoring**

```kotlin
@Test fun `outside guide frontal frames cannot complete the three second positioning hold`() {
    val machine = ChallengeMachine(listOf(Challenge.TurnLeft), testConfig.copy(positioningHoldMs = 3_000))
    machine.onFrame(frame(atMs = 0))
    machine.onFrame(frame(atMs = 2_900, insideGuide = false))
    machine.onFrame(frame(atMs = 3_000))
    assertIs<LivenessState.Positioning>(machine.state.value)
}

@Test fun `outside guide turn cannot enter its return phase`() {
    val machine = machine(Challenge.TurnLeft)
    val started = machine.completePositioning()
    machine.onFrame(frame(atMs = started + 100, yaw = -40f, insideGuide = false))
    val state = assertIs<LivenessState.ChallengeActive>(machine.state.value)
    assertEquals(ChallengePhase.AWAITING_ACTION, state.phase)
    assertEquals(PositioningHint.OUTSIDE_GUIDE, state.hint)
}
```

Add one analogous test that breaks `Challenge.Center` midway through its hold and proves it restarts after re-entry.

- [ ] **Step 2: Preserve the intended test scenarios for Task 6**

```kotlin
private fun guideProblem(frame: FaceFrame): PositioningHint? =
    PositioningHint.OUTSIDE_GUIDE.takeUnless { frame.insideGuide }
```

Call it before `offerAsPrimary`, `positioningProblem`, `handleTurn`, and `handleCenter`. When it returns non-null, clear `holdStartedMs`, emit the applicable recoverable state, and return before recording `primaryFrame` or `extremeFrame`. Do not alter `stepStartedMs`.

- [ ] **Step 3: Commit the liveness gate**

```bash
git add facecheck-kmp/src/commonMain/kotlin/com/borealnetwork/facecheck/liveness/ChallengeMachine.kt \
  facecheck-kmp/src/commonTest/kotlin/com/borealnetwork/facecheck/liveness/ChallengeMachineTest.kt
git commit -m "feat: block liveness steps outside guide"
```

### Task 3: Populate normalized bounds on Android and iOS

**Files:**
- Modify: `facecheck-kmp/src/androidMain/kotlin/com/borealnetwork/facecheck/camera/FrameGeometry.kt`
- Modify: `facecheck-kmp/src/iosMain/kotlin/com/borealnetwork/facecheck/camera/VisionFaceDetector.kt`
- Test: `facecheck-kmp/src/androidUnitTest/kotlin/com/borealnetwork/facecheck/camera/FrameGeometryTest.kt`

**Interfaces:**
- Consumes `NormalizedFaceBounds` from Task 1.
- Produces `FrameGeometry.normalizedBounds(rect, uprightWidth, uprightHeight): NormalizedFaceBounds`.
- Produces non-null `FaceFrame.bounds` for both default platform detectors when a face exists.

- [ ] **Step 1: Implement platform-bound creation**

```kotlin
@Test fun `normalizes an upright ML Kit rectangle`() {
    assertEquals(
        NormalizedFaceBounds(.25f, .20f, .75f, .80f),
        FrameGeometry.normalizedBounds(Rect(100, 80, 300, 320), uprightWidth = 400, uprightHeight = 400),
    )
}
```

Add an edge-clamping case and a zero-sized-dimension rejection case.

- [ ] **Step 2: Preserve the Android geometry cases for Task 6**

```kotlin
fun normalizedBounds(rect: Rect, uprightWidth: Int, uprightHeight: Int): NormalizedFaceBounds =
    NormalizedFaceBounds(
        left = rect.left.toFloat() / uprightWidth,
        top = rect.top.toFloat() / uprightHeight,
        right = rect.right.toFloat() / uprightWidth,
        bottom = rect.bottom.toFloat() / uprightHeight,
    )
```

Pass this value from Android `frameOf`. On iOS convert Vision's normalized bottom-left `boundingBox` into the documented top-left upright convention with `top = 1f - (origin.y + size.height)` and `bottom = 1f - origin.y`, then pass it to `FaceFrame`.

- [ ] **Step 3: Commit detector coordinates**

```bash
git add facecheck-kmp/src/androidMain/kotlin/com/borealnetwork/facecheck/camera/FrameGeometry.kt \
  facecheck-kmp/src/iosMain/kotlin/com/borealnetwork/facecheck/camera/VisionFaceDetector.kt \
  facecheck-kmp/src/androidUnitTest/kotlin/com/borealnetwork/facecheck/camera/FrameGeometryTest.kt
git commit -m "feat: report normalized face bounds from cameras"
```

### Task 4: Build one Android guide geometry and map the real preview frame

**Files:**
- Create: `samples/android-quickstart/src/main/kotlin/com/borealnetwork/facecheck/sample/FaceGuideGeometry.kt`
- Create: `samples/android-quickstart/src/main/kotlin/com/borealnetwork/facecheck/sample/PreviewFaceGuide.kt`
- Modify: `samples/android-quickstart/src/main/kotlin/com/borealnetwork/facecheck/sample/FaceGuideOverlay.kt`
- Modify: `samples/android-quickstart/src/main/kotlin/com/borealnetwork/facecheck/sample/VerifyActivity.kt`
- Modify: `facecheck-kmp/src/androidMain/kotlin/com/borealnetwork/facecheck/camera/AndroidCameraController.kt`
- Test: `samples/android-quickstart/src/test/kotlin/com/borealnetwork/facecheck/sample/FaceGuideGeometryTest.kt`

**Interfaces:**
- Produces `FaceGuideGeometry` in actual `PreviewView` pixels, with `contains(mappedFaceBounds: RectF): Boolean` requiring all four corners to lie within the oval.
- Produces `PreviewFaceGuide` supplied by the sample to `AndroidCameraController` while a capture is active.
- Produces the authoritative `FaceFrame.insideGuide` by mapping ML Kit `Face.boundingBox` through `ImageProxyTransformFactory(useRotationDegrees = true)` and `CoordinateTransform(source, PreviewView.outputTransform)` into the exact visible preview coordinates; an unavailable transform or absent face must result in `false` while guide enforcement is opted in.

- [ ] **Step 1: Implement one shared oval and exact CameraX mapping**

```kotlin
@Test fun `fully contained face passes the oval gate`() {
    assertTrue(guide.contains(NormalizedFaceBounds(.42f, .30f, .58f, .66f)))
}

@Test fun `face touching an oval edge is rejected`() {
    assertFalse(guide.contains(NormalizedFaceBounds(.10f, .30f, .58f, .66f)))
}
```

Add a test for a rectangle with its center inside but one corner outside; that is the regression the current visual-only oval misses.

Use one `FaceGuideGeometry` instance for overlay drawing and containment. Do not compare normalized image rectangles to a normalized overlay: CameraX preview crop, scale, and rotation make that approximation unsafe. Map from the live `ImageProxy` to the live `PreviewView` output transform first, then call the same geometry. Construct and install the callback in `VerifyActivity` before starting enrollment/verification and remove it when the capture closes.

- [ ] **Step 2: Preserve containment and transform cases for Task 6**

Test a fully contained face, an edge-touching face, a center-inside/corner-outside face, and unavailable transform blocking.

- [ ] **Step 3: Commit the Android sample gate**

```bash
git add samples/android-quickstart/src/main/kotlin/com/borealnetwork/facecheck/sample/{FaceGuideGeometry,PreviewFaceGuide,FaceGuideOverlay,VerifyActivity}.kt \
  facecheck-kmp/src/androidMain/kotlin/com/borealnetwork/facecheck/camera/AndroidCameraController.kt \
  samples/android-quickstart/src/test/kotlin/com/borealnetwork/facecheck/sample/FaceGuideGeometryTest.kt
git commit -m "feat: enforce sample face guide"
```

### Task 5: Present the three-second countdown

**Files:**
- Modify: `samples/android-quickstart/src/main/kotlin/com/borealnetwork/facecheck/sample/EnrollmentSessionPolicy.kt`
- Modify: `samples/android-quickstart/src/main/kotlin/com/borealnetwork/facecheck/sample/CapturePresentation.kt`
- Test later (Task 6): `samples/android-quickstart/src/test/kotlin/com/borealnetwork/facecheck/sample/CapturePresentationTest.kt`

**Interfaces:**
- Consumes `LivenessState.Positioning.holdProgress` and `PositioningHint.OUTSIDE_GUIDE`.
- Produces `CapturePresentation.stepLabel` of `Mantén el rostro dentro del óvalo · 3`, `· 2`, or `· 1` while the initial hold is valid.
- Produces enrollment config `positioningHoldMs = 3_000L`.

- [ ] **Step 1: Implement countdown mapping and policy**

```kotlin
@Test fun `positioning shows a three second countdown`() {
    val presentation = CapturePresentation.from(LivenessState.Positioning(PositioningHint.OK, holdProgress = .34f))
    assertEquals("Mantén el rostro dentro del óvalo · 2", presentation.stepLabel)
}

@Test fun `outside guide resets the countdown copy`() {
    val presentation = CapturePresentation.from(LivenessState.Positioning(PositioningHint.OUTSIDE_GUIDE, 0f))
    assertEquals("Vuelve a colocar tu rostro dentro del óvalo", presentation.instruction)
    assertEquals("Mantén el rostro dentro del óvalo · 3", presentation.stepLabel)
}
```

- [ ] **Step 2: Preserve presentation cases for Task 6**

```kotlin
private fun positioningLabel(progress: Float): String {
    val seconds = (3 - (progress.coerceIn(0f, .999f) * 3).toInt()).coerceIn(1, 3)
    return "Mantén el rostro dentro del óvalo · $seconds"
}

val livenessConfig = LivenessConfig(positioningHoldMs = 3_000, /* existing limits */)
```

Use the same `holdProgress` already sent to the ring and lower progress bar; do not create a second timer in the activity.

- [ ] **Step 3: Commit user-facing timing**

```bash
git add samples/android-quickstart/src/main/kotlin/com/borealnetwork/facecheck/sample/{EnrollmentSessionPolicy,CapturePresentation}.kt \
  samples/android-quickstart/src/test/kotlin/com/borealnetwork/facecheck/sample/CapturePresentationTest.kt
git commit -m "feat: require stable frontal face before enrollment"
```

### Task 6: Add tests and run the complete verification matrix

**Files:**
- Create or modify the test files identified in Tasks 1–5.

- [ ] **Step 1: Add the contract, machine, Android geometry, guide containment/transform, and presentation regression tests described above.**

- [ ] **Step 2: Run all automated verification**

Run: `./gradlew :facecheck-kmp:allTests :facecheck-kmp:testDebugUnitTest :samples:android-quickstart:testDebugUnitTest :samples:android-quickstart:assembleDebug`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Install and manually verify on Xiaomi**

Run:

```bash
adb -s 3d9337d1 install -r samples/android-quickstart/build/outputs/apk/debug/android-quickstart-debug.apk
adb -s 3d9337d1 shell monkey -p com.borealnetwork.facecheck.sample 1
```

Manual assertions: outside-oval frontal face does not advance; the `3→2→1` counter restarts after leaving the guide or moving; left/right/center do not count outside; a contained three-step enrollment reaches the completion sheet. Record only UI state and test result, never screenshots or logs containing a face.

- [ ] **Step 4: Commit the regression tests**

```bash
git add facecheck-kmp/src/commonTest facecheck-kmp/src/androidUnitTest \
  samples/android-quickstart/src/test
git commit -m "test: cover face guide gate and stable positioning"
```
