package com.borealnetwork.facecheck.liveness

import com.borealnetwork.facecheck.camera.CameraController
import com.borealnetwork.facecheck.model.FaceCheckErrorCode
import com.borealnetwork.facecheck.model.FaceCheckException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A camera that replays a scripted list of frames and hands back fixed bytes. */
private class ScriptedCamera(
    private val script: List<FaceFrame>,
    private val still: ByteArray = "jpeg".encodeToByteArray(),
    private val failCapture: Boolean = false,
) : CameraController {
    var started = 0
    var stopped = 0
    var captures = 0

    override val frames: Flow<FaceFrame> get() = script.asFlow()

    override suspend fun captureStill(): ByteArray {
        captures++
        if (failCapture) {
            throw FaceCheckException(FaceCheckErrorCode.CAMERA_UNAVAILABLE, "sin cámara")
        }
        return still
    }

    override fun start() {
        started++
    }

    override fun stop() {
        stopped++
    }
}

private fun happyScript(): List<FaceFrame> = listOf(
    frame(0),
    frame(800),
    frame(900, yaw = -35f),
    frame(1_000, yaw = 0f),
)

class LivenessSessionTest {

    @Test
    fun a_successful_session_captures_a_still_and_releases_the_camera() = runTest {
        val camera = ScriptedCamera(happyScript())
        val machine = ChallengeMachine(listOf(Challenge.TurnLeft), testConfig)

        val capture = runLivenessSession(camera, machine, timeoutMs = 60_000)

        assertEquals("jpeg", capture.still.decodeToString())
        assertEquals(1, capture.evidence.challenges.size)
        assertEquals(1, camera.started)
        assertEquals(1, camera.captures)
        assertEquals(1, camera.stopped)
        assertTrue(machine.state.value is LivenessState.Done)
    }

    @Test
    fun a_failed_session_still_releases_the_camera() = runTest {
        // A verification that throws must not leave the camera light on; users
        // read that as the app spying on them, and they are not wrong to.
        val camera = ScriptedCamera(
            listOf(frame(0), frame(800), frame(900, trackingId = 42)),
        )
        val machine = ChallengeMachine(listOf(Challenge.TurnLeft), testConfig)

        val failure = assertFailsWith<FaceCheckException> {
            runLivenessSession(camera, machine, timeoutMs = 60_000)
        }

        assertEquals(FaceCheckErrorCode.LIVENESS_FAILED, failure.code)
        assertEquals("Detectamos un cambio de persona. Vuelve a empezar.", failure.message)
        assertEquals(0, camera.captures, "no photo should be taken for a failed session")
        assertEquals(1, camera.stopped)
    }

    @Test
    fun a_camera_that_never_delivers_a_usable_frame_times_out() = runTest {
        val camera = ScriptedCamera(listOf(frame(0, faceCount = 0)))
        val machine = ChallengeMachine(listOf(Challenge.TurnLeft), testConfig)

        val failure = assertFailsWith<FaceCheckException> {
            runLivenessSession(camera, machine, timeoutMs = 200)
        }

        assertEquals(FaceCheckErrorCode.TIMEOUT, failure.code)
        assertEquals(1, camera.stopped)
        assertTrue(machine.state.value is LivenessState.Failed)
    }

    @Test
    fun a_capture_failure_propagates_and_still_stops_the_camera() = runTest {
        val camera = ScriptedCamera(happyScript(), failCapture = true)
        val machine = ChallengeMachine(listOf(Challenge.TurnLeft), testConfig)

        val failure = assertFailsWith<FaceCheckException> {
            runLivenessSession(camera, machine, timeoutMs = 60_000)
        }

        assertEquals(FaceCheckErrorCode.CAMERA_UNAVAILABLE, failure.code)
        assertEquals(1, camera.stopped)
    }

    @Test
    fun a_session_cancelled_by_the_user_is_not_reported_as_a_liveness_failure() = runTest {
        // Showing "no pudimos verificarte" to someone who pressed back is wrong.
        val machine = ChallengeMachine(listOf(Challenge.TurnLeft), testConfig)
        machine.onFrame(frame(0))
        machine.cancel()

        val camera = ScriptedCamera(emptyList())
        val failure = assertFailsWith<FaceCheckException> {
            runLivenessSession(camera, machine, timeoutMs = 60_000)
        }

        assertEquals(FaceCheckErrorCode.CANCELLED, failure.code)
    }
}