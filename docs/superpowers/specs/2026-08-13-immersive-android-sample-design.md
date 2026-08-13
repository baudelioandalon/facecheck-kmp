# Immersive Android Sample Design

## Goal

Turn the Android quickstart from a technical form into a guided FaceCheck
sample. A developer chooses enrolment or verification, selects the subject on
a separate screen, and then enters a focused full-screen camera session with a
face frame, liveness instruction and visible progress.

## Flow

1. **Home** explains that camera, image access and location are required before
   a session. It offers `Enrolar una persona` and `Verificar una identidad`.
   Until configuration and permissions are ready, both actions show the
   prerequisite explanation and the single permission action; CameraX is not
   attached and no FaceCheck operation begins.
2. **Subject setup** is a separate screen. Enrolment accepts the email to
   create. Verification presents people successfully enrolled by this sample
   on the current device and also permits an explicit email. The sample must
   not query the owner directory with a client API key: that directory is
   owner-only portal data and belongs behind an integrator backend.
3. **Capture** fills the display with the camera preview. A dimmed oval frame
   makes the intended face position clear. The liveness state controls the
   Spanish instruction, a challenge label and a progress ring around the oval.
   The only persistent control is cancellation; email entry and primary mode
   buttons are absent.
4. **Result** closes the camera, gives a clear success or failure outcome and
   returns to home. A successful enrolment is saved in the local sample
   directory for future verification selection.

## Components

- `ImmersiveSampleFlow` is a pure state model for home, subject setup, capture
  and outcome. It validates a trimmed email before capture.
- `CapturePresentation` maps `LivenessState` to UI-safe instruction, progress
  and step label. The visual layer never interprets liveness internals.
- `LocalSubjectDirectory` normalizes and orders locally remembered enrolment
  emails. `VerifyActivity` persists it with `SharedPreferences`; no biometric
  material, photos, templates or server-directory data are saved.
- `FaceGuideOverlay` draws a transparent oval cutout, its neutral guide and a
  progress arc above the camera preview.
- `VerifyActivity` renders the screens, requests existing `RequiredPermissions`,
  binds the camera only in capture, and calls existing `FaceCheck.enroll` and
  `FaceCheck.verify` APIs.

## Privacy and verification

Location remains an eligibility permission only; this sample does not collect
or transmit coordinates. Unit tests cover flow transitions, email rejection,
local-subject normalization and liveness presentation. The Android sample must
compile and install on the Xiaomi; physical biometric E2E occurs only when the
person in front of the device is ready.
# Immersive Android Sample Design
