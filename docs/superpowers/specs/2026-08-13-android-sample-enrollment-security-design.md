# Android Sample Enrollment and Permission Gate Design

## Goal

Make the Android quickstart runnable against a developer's FaceCheck test app: require the user to accept camera, media-storage and location permissions before a liveness session, then let the developer enroll and verify an editable email address.

## Configuration

`facecheck init --stack android` writes `FACECHECK_API_KEY` to the ignored root `local.properties`. The Android sample reads that property during Gradle configuration and exposes it as `BuildConfig.FACECHECK_API_KEY`. The key is intentionally not committed; test keys are still public client identifiers, but the sample must not distribute one owned by its maintainer.

## Permission gate

Before either operation, the UI shows the required permissions and a single action to request them. Required permissions are camera, coarse or fine location, and the platform's media-storage read permission (`READ_MEDIA_IMAGES` on Android 13+; `READ_EXTERNAL_STORAGE` on earlier Android versions). The sample does not start CameraX, capture a selfie or call FaceCheck until all are granted.

Location is a local eligibility requirement for this sample's security posture. The present FaceCheck SDK and inference API do not accept or transmit coordinates, so the UI and documentation must state that plainly. Sending location to a backend requires a separate consent, privacy policy and backend contract; it is outside this sample change.

## Enrollment and verification

The sample has one editable email field and separate `Enrolar` and `Verificar` buttons. Both create a fresh camera controller and liveness challenge. Enrollment calls `FaceCheck.enroll`; verification calls `FaceCheck.verify`. The sample targets `lk_test_` by default, so an enrollment grant is not needed for this local test flow. A live key remains subject to the backend's grant policy.

## Error handling and lifecycle

The UI disables both operations while a session is running, prints typed FaceCheck failures without key, selfie or location values, and releases the camera at the end of each attempt and on Activity destruction. The app reports a configuration error before a permission prompt if `FACECHECK_API_KEY` is missing.

## Verification

Unit tests cover permission selection and the requirement that all permission groups are accepted before an operation. The debug APK must compile, install and open on the connected Xiaomi. Physical enrollment and verification require the person holding the device to approve Android permissions and complete the liveness challenges.
