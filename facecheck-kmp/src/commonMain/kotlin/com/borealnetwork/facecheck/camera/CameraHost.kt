package com.borealnetwork.facecheck.camera

/**
 * Whatever handle the platform camera needs to attach itself to the host app.
 *
 * On Android this is a `Context`; on iOS it is the `UIViewController` that owns
 * the preview. It is `expect` rather than `Any` so neither platform has to cast,
 * and it exists at all because there is no portable way to say "the thing a
 * camera hangs off".
 */
expect class CameraHost

/**
 * The default camera implementation for the current platform.
 *
 * A host app that already runs its own camera pipeline should implement
 * [CameraController] instead and skip this entirely.
 */
expect fun createCameraController(
    host: CameraHost,
    options: CameraOptions = CameraOptions(),
): CameraController

// ---------------------------------------------------------------------------
// Why this file exists separately from CameraController.kt
//
// Every `expect`/`actual` declaration in the whole SDK lives here and in the
// matching `CameraHost.<platform>.kt` files. Nothing else in commonMain or
// androidMain uses expect/actual.
//
// That is not an accident. The Android-only distribution
// (github.com/borealnetwork/facecheck-android) is a byte-for-byte mirror of
// this module's commonMain + androidMain, compiled as a plain
// `com.android.library` — and a plain Kotlin/Android module cannot compile
// expect/actual. Keeping the whole expect/actual surface in one excludable
// file per source set turns "port the SDK to a non-KMP build" into "skip two
// files", with no transformation of any other source.
//
// If you add an expect/actual declaration, put it in these files. The mirror's
// sync script asserts that no `expect `/`actual ` token survives anywhere else
// and fails loudly if one does.
// ---------------------------------------------------------------------------