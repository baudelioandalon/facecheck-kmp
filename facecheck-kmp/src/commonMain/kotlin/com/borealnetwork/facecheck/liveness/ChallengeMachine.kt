package com.borealnetwork.facecheck.liveness

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Drives one liveness session: positioning, then a plan of challenges, then a
 * still capture, ending in [LivenessState.Done] or [LivenessState.Failed].
 *
 * ## This class is UX, not security
 *
 * Read this before building anything on top of it.
 *
 * The machine runs on a device the attacker controls. Every input it sees is a
 * [FaceFrame] — a handful of floats produced by code on that same device. An
 * attacker rooting the phone, hooking the detector, or feeding a virtual camera
 * does not have to turn his head; he emits `yaw = -30f` and the challenge
 * passes. No threshold in [LivenessConfig] changes that, and neither would ten
 * more challenge types, because the problem is not which values are demanded
 * but who computes them.
 *
 * So what is it for? Two real jobs:
 *
 *  - **Coaching.** It walks a cooperative user into producing one good, frontal,
 *    sharp, well-lit still photo. That is what the accuracy of the whole system
 *    rests on, and it is what most users fail at without guidance.
 *  - **Raising the floor.** It defeats the casual attack — holding up a printed
 *    photo or a still on another phone — because a photo cannot turn its head.
 *    It does not defeat a video replay, a 3D mask, or a hooked build.
 *
 * The actual decision belongs to the backend, which receives a photo and matches
 * it against a stored template under a threshold the SDK never sees and cannot
 * influence. Nothing this class produces is sent as proof of anything: a
 * `Done` state means "we captured a usable photo", never "this person is real".
 * Treating it as an authorisation would put the security boundary inside the
 * attacker's own process.
 *
 * ## Purity
 *
 * There is no clock, no camera, no coroutine dispatcher and no platform code
 * here. Time comes exclusively from [FaceFrame.timestampMs] (or [onTick]), so a
 * test drives a twenty-second session in microseconds and a slow device
 * measures elapsed time in frames it actually received rather than in seconds it
 * spent stalled.
 *
 * ## Threading
 *
 * Not thread-safe. Feed it from a single frame-analysis thread. [state] is a
 * [StateFlow] and is safe to *observe* from anywhere.
 *
 * @param challenges the plan, in order. Must not be empty; build one with
 *   [ChallengePlan.random].
 * @param config thresholds and deadlines.
 */
class ChallengeMachine(
    val challenges: List<Challenge>,
    private val config: LivenessConfig = LivenessConfig(),
) {
    init {
        require(challenges.isNotEmpty()) {
            "a liveness session needs at least one challenge"
        }
    }

    private val _state = MutableStateFlow<LivenessState>(LivenessState.Idle)

    /** The current step, for the UI to render. Replays its latest value. */
    val state: StateFlow<LivenessState> = _state.asStateFlow()

    // --- Session-scoped mutable state ----------------------------------------

    /** Start of the deadline for whatever step is current. */
    private var stepStartedMs: Long = 0

    /** Timestamp of the last frame that actually contained one face. */
    private var lastFaceSeenMs: Long = 0

    /** Start of the current "hold still" run, or null if it has been broken. */
    private var holdStartedMs: Long? = null

    /** The tracking id the session is bound to, once the detector reports one. */
    private var boundTrackingId: Int? = null
    private var trackingBound: Boolean = false

    private var challengeIndex: Int = 0
    private var phase: ChallengePhase = ChallengePhase.AWAITING_ACTION

    /** Best frame seen during positioning, by [scoreAsPrimary]. */
    private var primaryFrame: FaceFrame? = null
    private var primaryScore: Float = Float.NEGATIVE_INFINITY

    /** Most extreme frame of the challenge in flight. */
    private var extremeFrame: FaceFrame? = null
    private var extremeMagnitude: Float = Float.NEGATIVE_INFINITY

    private val completed = mutableListOf<ChallengeCapture>()

    // --- Public API -----------------------------------------------------------

    /**
     * Feed one analysed frame.
     *
     * Ignored once [state] is terminal, so a camera that keeps delivering frames
     * after the session ended cannot resurrect or corrupt it.
     */
    fun onFrame(frame: FaceFrame) {
        if (_state.value.isTerminal) return

        if (_state.value is LivenessState.Idle) {
            beginPositioning(frame.timestampMs)
        }

        // Deadlines are checked before the frame is scored, so a frame that
        // arrives after the step already expired cannot complete it.
        if (checkDeadlines(frame.timestampMs)) return

        if (frame.faceCount > 1) {
            fail(FailureReason.MULTIPLE_FACES)
            return
        }
        if (frame.faceCount <= 0) {
            onFaceMissing(frame.timestampMs)
            return
        }
        if (!bindTracking(frame)) {
            fail(FailureReason.FACE_SWAPPED)
            return
        }
        lastFaceSeenMs = frame.timestampMs

        when (val current = _state.value) {
            is LivenessState.Positioning -> handlePositioning(frame)
            is LivenessState.ChallengeActive -> handleChallenge(frame, current)
            // Capturing only needs the face to still be there; the checks above
            // already established that.
            LivenessState.Capturing -> Unit
            else -> Unit
        }
    }

    /**
     * Advance time without a frame.
     *
     * The host pumps this (say, every 250 ms) so a session still times out when
     * the camera stops delivering frames entirely — a stall that would otherwise
     * leave the machine waiting forever for a deadline it can only observe
     * through frames it is no longer receiving.
     */
    fun onTick(nowMs: Long) {
        if (_state.value.isTerminal || _state.value is LivenessState.Idle) return
        checkDeadlines(nowMs)
    }

    /**
     * Move out of [LivenessState.Capturing] into [LivenessState.Done].
     *
     * Called by the orchestrator once the still photo is in hand. Separate from
     * the frame path because taking a photo is I/O and this class does none.
     *
     * @throws IllegalStateException if the session is not in [LivenessState.Capturing].
     */
    fun completeCapture() {
        check(_state.value is LivenessState.Capturing) {
            "completeCapture() is only valid while Capturing, was ${_state.value}"
        }
        val primary = primaryFrame
        checkNotNull(primary) {
            "reached Capturing without a primary frame, which positioning cannot allow"
        }
        _state.value = LivenessState.Done(
            LivenessEvidence(primary = primary, challenges = completed.toList()),
        )
    }

    /** Abort the session. A no-op once it has already ended. */
    fun cancel() {
        if (!_state.value.isTerminal) fail(FailureReason.CANCELLED)
    }

    /** Return to [LivenessState.Idle] so the same instance can run again. */
    fun reset() {
        stepStartedMs = 0
        lastFaceSeenMs = 0
        holdStartedMs = null
        boundTrackingId = null
        trackingBound = false
        challengeIndex = 0
        phase = ChallengePhase.AWAITING_ACTION
        primaryFrame = null
        primaryScore = Float.NEGATIVE_INFINITY
        extremeFrame = null
        extremeMagnitude = Float.NEGATIVE_INFINITY
        completed.clear()
        _state.value = LivenessState.Idle
    }

    // --- Steps ----------------------------------------------------------------

    private fun beginPositioning(nowMs: Long) {
        stepStartedMs = nowMs
        lastFaceSeenMs = nowMs
        holdStartedMs = null
        _state.value = LivenessState.Positioning(PositioningHint.NO_FACE)
    }

    private fun handlePositioning(frame: FaceFrame) {
        // Scored before the pose gate: the sharpest, most frontal frame is worth
        // keeping even if the user has not yet held it long enough to advance.
        offerAsPrimary(frame)

        val problem = positioningProblem(frame)
        if (problem != null) {
            holdStartedMs = null
            _state.value = LivenessState.Positioning(problem, holdProgress = 0f)
            return
        }

        val start = holdStartedMs ?: frame.timestampMs.also { holdStartedMs = it }
        val held = frame.timestampMs - start
        if (held >= config.positioningHoldMs) {
            startChallenge(0, frame.timestampMs)
        } else {
            val ratio = if (config.positioningHoldMs == 0L) {
                1f
            } else {
                held.toFloat() / config.positioningHoldMs.toFloat()
            }
            _state.value = LivenessState.Positioning(
                PositioningHint.OK,
                holdProgress = ratio.coerceIn(0f, 1f),
            )
        }
    }

    /**
     * What is wrong with this frame as a starting pose, or null if nothing is.
     *
     * Ordered by what the user should fix first: being out of frame beats being
     * badly lit, which beats being slightly off-axis.
     */
    private fun positioningProblem(frame: FaceFrame): PositioningHint? = when {
        frame.faceRatio < config.minFaceRatio -> PositioningHint.MOVE_CLOSER
        frame.faceRatio > config.maxFaceRatio -> PositioningHint.MOVE_AWAY
        frame.quality.detectorScore < config.minDetectorScore -> PositioningHint.NO_FACE
        frame.quality.brightness < config.minBrightness -> PositioningHint.TOO_DARK
        frame.quality.brightness > config.maxBrightness -> PositioningHint.TOO_BRIGHT
        frame.quality.sharpness < config.minSharpness -> PositioningHint.LOW_QUALITY
        abs(frame.roll) > config.maxRollDeg -> PositioningHint.STRAIGHTEN_HEAD
        abs(frame.yaw) > config.centerToleranceDeg -> PositioningHint.LOOK_STRAIGHT
        abs(frame.pitch) > config.centerToleranceDeg -> PositioningHint.LOOK_STRAIGHT
        else -> null
    }

    private fun startChallenge(index: Int, nowMs: Long) {
        challengeIndex = index
        phase = ChallengePhase.AWAITING_ACTION
        stepStartedMs = nowMs
        holdStartedMs = null
        extremeFrame = null
        extremeMagnitude = Float.NEGATIVE_INFINITY
        _state.value = LivenessState.ChallengeActive(
            challenge = challenges[index],
            index = index,
            total = challenges.size,
            phase = ChallengePhase.AWAITING_ACTION,
        )
    }

    private fun handleChallenge(frame: FaceFrame, current: LivenessState.ChallengeActive) {
        // A face that drifted out of range still counts as present — the swap and
        // face-lost checks already passed — but it cannot advance the challenge,
        // because a pose measured on a face too small to resolve is noise.
        val blocking = when {
            frame.faceRatio < config.minFaceRatio -> PositioningHint.MOVE_CLOSER
            frame.faceRatio > config.maxFaceRatio -> PositioningHint.MOVE_AWAY
            else -> null
        }
        if (blocking != null) {
            holdStartedMs = null
            emitChallenge(current.copy(hint = blocking))
            return
        }

        when (challenges[challengeIndex]) {
            Challenge.TurnLeft -> handleTurn(frame, current, toTheLeft = true)
            Challenge.TurnRight -> handleTurn(frame, current, toTheLeft = false)
            Challenge.Center -> handleCenter(frame, current)
        }
    }

    /**
     * A turn is two events, not one: reach the angle, then come back to frontal.
     *
     * The return leg is what makes this a movement rather than a pose. Scoring
     * only the extreme angle would accept a photograph held at the right angle;
     * it cannot also be held straight a moment later.
     *
     * Turning the *wrong* way is not a failure — users overshoot and correct all
     * the time — it simply does not advance anything, so a wrong turn burns the
     * challenge's deadline and nothing else. That is also what keeps a later
     * challenge from being satisfied early by a movement made during this one.
     */
    private fun handleTurn(
        frame: FaceFrame,
        current: LivenessState.ChallengeActive,
        toTheLeft: Boolean,
    ) {
        val reached = if (toTheLeft) {
            frame.yaw <= -config.turnThresholdDeg
        } else {
            frame.yaw >= config.turnThresholdDeg
        }

        // Track the most extreme frame in the requested direction only. The frame
        // is worth keeping whether or not the threshold has been crossed yet, but
        // a yaw in the opposite direction never represents this challenge.
        val signedMagnitude = if (toTheLeft) -frame.yaw else frame.yaw
        if (signedMagnitude > 0f && signedMagnitude > extremeMagnitude) {
            extremeMagnitude = signedMagnitude
            extremeFrame = frame
        }

        when (phase) {
            ChallengePhase.AWAITING_ACTION -> {
                if (reached) {
                    phase = ChallengePhase.RETURNING_TO_CENTER
                    emitChallenge(current.copy(phase = phase, hint = null))
                } else if (current.hint != null) {
                    emitChallenge(current.copy(hint = null))
                }
            }

            ChallengePhase.RETURNING_TO_CENTER -> {
                if (abs(frame.yaw) < config.centerToleranceDeg) {
                    finishChallenge(frame.timestampMs)
                } else if (current.hint != null) {
                    emitChallenge(current.copy(hint = null))
                }
            }
        }
    }

    /**
     * [Challenge.Center] is a hold, not a movement: frontal for
     * [LivenessConfig.centerHoldMs] without interruption.
     */
    private fun handleCenter(frame: FaceFrame, current: LivenessState.ChallengeActive) {
        val frontal = abs(frame.yaw) < config.centerToleranceDeg &&
            abs(frame.pitch) < config.centerToleranceDeg
        if (!frontal) {
            holdStartedMs = null
            emitChallenge(current.copy(hint = PositioningHint.LOOK_STRAIGHT))
            return
        }

        // "Most extreme" for a hold means "most frontal and sharpest".
        val score = scoreAsPrimary(frame)
        if (score > extremeMagnitude) {
            extremeMagnitude = score
            extremeFrame = frame
        }

        val start = holdStartedMs ?: frame.timestampMs.also { holdStartedMs = it }
        if (frame.timestampMs - start >= config.centerHoldMs) {
            finishChallenge(frame.timestampMs)
        } else if (current.hint != null) {
            emitChallenge(current.copy(hint = null))
        }
    }

    private fun finishChallenge(nowMs: Long) {
        val frame = extremeFrame
        checkNotNull(frame) {
            "a challenge completed without ever recording a frame for it"
        }
        completed += ChallengeCapture(challenges[challengeIndex], frame)

        val next = challengeIndex + 1
        if (next < challenges.size) {
            startChallenge(next, nowMs)
        } else {
            stepStartedMs = nowMs
            holdStartedMs = null
            _state.value = LivenessState.Capturing
        }
    }

    // --- Guards ---------------------------------------------------------------

    /**
     * Bind the session to a tracking identity, or report that it changed.
     *
     * The first frame that carries an id claims it. A later frame carrying a
     * *different* id means the detector stopped following the face this session
     * started with, which is either a genuine re-acquisition or a swap — and the
     * machine cannot tell those apart, so it treats both as a swap. Frames with
     * a null id are accepted without binding: a detector running in single-image
     * mode reports no identity at all, and refusing to work on those platforms
     * would be worse than losing the check there.
     *
     * @return false when the identity changed.
     */
    private fun bindTracking(frame: FaceFrame): Boolean {
        val incoming = frame.trackingId ?: return true
        if (!trackingBound || boundTrackingId == null) {
            boundTrackingId = incoming
            trackingBound = true
            return true
        }
        return boundTrackingId == incoming
    }

    private fun onFaceMissing(nowMs: Long) {
        when (val current = _state.value) {
            is LivenessState.Positioning -> {
                // Nothing has been asked of the user yet, so a missing face is a
                // prompt, not a failure. The positioning deadline still applies.
                holdStartedMs = null
                _state.value = LivenessState.Positioning(PositioningHint.NO_FACE)
            }

            is LivenessState.ChallengeActive -> {
                // checkDeadlines already failed the session if the gap ran past
                // the grace period, so reaching here means it is still within it.
                emitChallenge(current.copy(hint = PositioningHint.NO_FACE))
            }

            else -> Unit
        }
    }

    /**
     * Fail the session if any deadline has passed.
     *
     * @return true when the session ended here, in which case the caller must
     *   stop processing the frame.
     */
    private fun checkDeadlines(nowMs: Long): Boolean {
        val current = _state.value
        val elapsed = nowMs - stepStartedMs

        val limit = when (current) {
            is LivenessState.Positioning -> config.positioningTimeoutMs
            is LivenessState.ChallengeActive -> config.challengeTimeoutMs
            LivenessState.Capturing -> config.captureTimeoutMs
            else -> null
        }
        if (limit != null && elapsed > limit) {
            fail(FailureReason.TIMEOUT)
            return true
        }

        // Only meaningful once the user has been asked to do something. While
        // positioning, "no face" is the normal starting condition.
        val watchesForLoss = current is LivenessState.ChallengeActive ||
            current is LivenessState.Capturing
        if (watchesForLoss && nowMs - lastFaceSeenMs > config.faceLostGraceMs) {
            fail(FailureReason.FACE_LOST)
            return true
        }
        return false
    }

    // --- Frame selection ------------------------------------------------------

    private fun offerAsPrimary(frame: FaceFrame) {
        if (frame.faceRatio < config.minFaceRatio) return
        if (frame.faceRatio > config.maxFaceRatio) return
        if (frame.quality.detectorScore < config.minDetectorScore) return
        val score = scoreAsPrimary(frame)
        if (score > primaryScore) {
            primaryScore = score
            primaryFrame = frame
        }
    }

    /**
     * How good a frame is as *the* photo of this face: mostly how frontal it is,
     * partly how sharp.
     *
     * Frontality dominates because pose is what actually moves an ArcFace
     * embedding — a sharp three-quarter profile matches worse than a slightly
     * soft frontal shot. Roll counts half: the backend re-aligns on the five
     * landmarks, so a tilted head costs less than a turned one.
     */
    private fun scoreAsPrimary(frame: FaceFrame): Float {
        val deviation = abs(frame.yaw) + abs(frame.pitch) + abs(frame.roll) / 2f
        val frontality = (1f - (deviation / MAX_USEFUL_DEVIATION_DEG)).coerceIn(0f, 1f)
        val sharpness =
            (frame.quality.sharpness / FrameQuality.SHARPNESS_REFERENCE).coerceIn(0f, 1f)
        return frontality * FRONTALITY_WEIGHT + sharpness * SHARPNESS_WEIGHT
    }

    private fun emitChallenge(next: LivenessState.ChallengeActive) {
        _state.value = next
    }

    private fun fail(reason: FailureReason) {
        _state.value = LivenessState.Failed(reason)
    }

    private companion object {
        /** Angular deviation at which a face stops being useful for matching. */
        const val MAX_USEFUL_DEVIATION_DEG = 90f
        const val FRONTALITY_WEIGHT = 0.7f
        const val SHARPNESS_WEIGHT = 0.3f
    }
}