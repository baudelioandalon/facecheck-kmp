# Face-guide gate and frontal stability design

## Context

The Android quickstart draws an oval through `FaceGuideOverlay`, but that oval
is currently presentation-only. The liveness engine receives angle, size and
quality information, but no position of the detected face. Consequently a
frontal face can satisfy positioning and the final center challenge while it is
outside the visible guide. The initial positioning hold is also only 700 ms.

## Goals

- Make the visible guide an actual gate for enrollment and verification.
- Require the subject's face to remain fully inside the oval before **any**
  liveness transition may advance.
- Require a continuous three-second frontal, stable initial pose. The UI shows
  a descending `3`, `2`, `1` countdown and restarts it whenever a prerequisite
  is broken.
- Preserve existing face-count, identity-continuity, quality and timeout
  behavior.
- Keep the liveness state machine deterministic and unit-testable without a
  camera or Android view.

## Non-goals

- This does not make device-side liveness an authorization boundary.
- It does not send guide coordinates, preview images, or biometric data to the
  backend.
- It does not redesign portal flows, API endpoints, grants, or subject IDs.

## Chosen architecture

Use a shared frame contract and a sample-owned guide mapper.

1. The KMP camera contract carries a normalized detected-face rectangle in
   addition to pose and quality. Each platform adapter reports the rectangle in
   a documented, upright camera-coordinate space.
2. A pure `FaceGuideGate` owns the oval geometry and determines whether the
   complete detected rectangle is inside it. It also maps the image-coordinate
   rectangle into the preview coordinate system, using the same CameraX
   transform used by the overlay. The overlay reads that exact geometry instead
   of independently calculating a similar oval.
3. The Android quickstart decorates each camera frame with `insideGuide` before
   it reaches `ChallengeMachine`. The common machine treats a false value as a
   blocking positioning condition, never as a completed pose or challenge.
4. `ChallengeMachine` remains UI-agnostic. Its state exposes guide blocking and
   initial hold progress; `CapturePresentation` turns the latter into the
   Spanish countdown. The sample configures `positioningHoldMs = 3_000`.

The same frame contract is implemented on iOS so KMP consumers retain a
consistent capability. A host only enables guide enforcement when it supplies
the matching guide mapper; existing consumers without a visual guide retain
their current behavior.

## State behavior

### Initial positioning

The machine starts in `Positioning` and can begin the three-second hold only
when exactly one face is detected, its full rectangle is inside the guide, it
is in the allowed size and quality range, and yaw/pitch/roll are frontal.

The UI says `Mantén tu cara dentro del óvalo` and displays `3`, then `2`, then
`1` as the continuous hold elapses. Leaving the guide, moving out of the
frontal tolerance, losing the face, detecting a second face, or failing the
existing size/quality rules resets the timer to three. No initial still is
selected from a frame outside the guide.

### Challenges

For turn-left, turn-right, the return-to-center leg, and the final center hold,
a frame outside the guide produces the visible hint `Vuelve a colocar tu rostro
dentro del óvalo` and clears any active center hold. It does not record an
extreme frame, complete a transition, or consume a challenge. The normal
per-step timeout continues to run, so moving outside the guide cannot extend a
session indefinitely.

## UX and accessibility

- The oval border remains visible, and its progress ring reflects the initial
  hold before challenges begin.
- The instruction and lower progress bar use the same hold progress and
  countdown, so they cannot disagree.
- Existing haptic and sound feedback remain attached only to actual challenge
  completion; guide re-entry does not vibrate or click.
- The retry sheet remains the failure path; the new guide hint is in the live
  camera screen so the person can correct position immediately.

## Error handling

- Missing, multiple, swapped and timed-out faces keep their existing failure
  reasons.
- A face outside the oval is a recoverable positioning state, not a new server
  error and not a biometric event.
- A platform adapter that cannot provide normalized bounds must not claim guide
  compliance when guide enforcement is enabled; it reports the guide-blocked
  state instead.

## Test strategy

1. Common liveness tests prove that an out-of-guide frontal frame cannot start
   or complete the initial hold, cannot advance either turn, and resets the
   final-center hold.
2. Pure guide tests prove full-rectangle containment, edge rejection, and
   preview/image coordinate mapping for crop and letterbox transforms.
3. Presentation tests prove three-second labels and reset behavior.
4. Android adapter tests prove ML Kit bounds are normalized and fed through the
   guide gate.
5. The complete KMP and sample unit suites build, then the debug quickstart is
   installed on the Xiaomi. Manual QA verifies that a face outside the oval
   cannot advance, that the 3-2-1 hold restarts on movement, and that all three
   enrollment steps work while contained.

## Compatibility and release

The new frame fields have safe defaults for hosts that do not opt into a guide.
The quickstart opts in immediately. This change stays on a feature branch until
the tests and Xiaomi evidence are fresh; it does not tag or publish an SDK
artifact by itself.
