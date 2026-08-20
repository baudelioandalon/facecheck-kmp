package com.borealnetwork.facecheck.liveness

/**
 * What the liveness session is doing right now.
 *
 * Emitted on [ChallengeMachine.state] so a UI can render the current
 * instruction and progress without knowing anything about yaw thresholds.
 * Every state carries [instructionEs], which is the exact string to put on
 * screen — a host app should never have to build one from the state's type.
 */
sealed interface LivenessState {

    /** The line to show the user, in Spanish. */
    val instructionEs: String

    /** 0..1, for a progress bar. Positioning counts as zero progress. */
    val progress: Float

    /** Nothing has happened yet; no frame has arrived. */
    data object Idle : LivenessState {
        override val instructionEs: String = "Prepárate para la verificación"
        override val progress: Float = 0f
    }

    /**
     * Waiting for the user to hold a usable frontal pose before the first
     * challenge. [hint] says what is currently wrong, if anything.
     */
    data class Positioning(
        val hint: PositioningHint = PositioningHint.NO_FACE,
        /** How much of the required steady hold has elapsed, 0..1. */
        val holdProgress: Float = 0f,
    ) : LivenessState {
        override val instructionEs: String get() = hint.instructionEs
        override val progress: Float = 0f
    }

    /**
     * A challenge is in flight.
     *
     * [index] is zero-based and [total] is the length of the plan, so
     * "reto 2 de 3" is `index + 1` of `total`.
     */
    data class ChallengeActive(
        val challenge: Challenge,
        val index: Int,
        val total: Int,
        val phase: ChallengePhase,
        /**
         * A problem that is blocking progress right now (face too far, face
         * lost). Null while the user simply has not completed the action yet.
         */
        val hint: PositioningHint? = null,
    ) : LivenessState {
        override val instructionEs: String
            get() = hint?.instructionEs ?: when (phase) {
                ChallengePhase.AWAITING_ACTION -> challenge.instructionEs
                ChallengePhase.RETURNING_TO_CENTER -> "Regresa a ver de frente"
            }

        override val progress: Float
            get() = if (total <= 0) 0f else index.toFloat() / total.toFloat()
    }

    /**
     * Every challenge passed; the still photo is being taken.
     *
     * The machine cannot take the photo itself — that is I/O — so it parks here
     * until the orchestrator calls [ChallengeMachine.completeCapture].
     */
    data object Capturing : LivenessState {
        override val instructionEs: String = "No te muevas, estamos tomando la foto"
        override val progress: Float = 1f
    }

    /**
     * Server-driven Active Liveness is taking one of the five canonical stills.
     *
     * Frames are still monitored for face loss/swap, but they cannot advance the
     * machine until the orchestrator acknowledges the camera capture for [role].
     */
    data class CapturingEvidence(val role: EvidenceRole) : LivenessState {
        override val instructionEs: String = "No te muevas, estamos tomando la foto"
        override val progress: Float
            get() = (role.ordinal.toFloat() / EvidenceRole.entries.size.toFloat()).coerceIn(0f, 1f)
    }

    /** The session succeeded. [evidence] holds the frames that were selected. */
    data class Done(val evidence: LivenessEvidence) : LivenessState {
        override val instructionEs: String = "Listo"
        override val progress: Float = 1f
    }

    /** The session ended without completing. [reason] is safe to show the user. */
    data class Failed(val reason: FailureReason) : LivenessState {
        override val instructionEs: String get() = reason.messageEs
        override val progress: Float = 0f
    }

    /** True once no further frame can change the outcome. */
    val isTerminal: Boolean
        get() = this is Done || this is Failed
}

/** Where a challenge is in its own two-step lifecycle. */
enum class ChallengePhase {
    /** Waiting for the user to reach the pose the challenge asks for. */
    AWAITING_ACTION,

    /**
     * The pose was reached; now the head has to come back to frontal.
     *
     * Requiring the return is what makes the challenge a *movement* rather than
     * a pose. A single photo held at the right angle satisfies "turn left"; it
     * cannot also satisfy "and then face forward again".
     */
    RETURNING_TO_CENTER,
}

/** The one thing currently standing between the user and the next step. */
enum class PositioningHint(val instructionEs: String) {
    /** Everything is fine; hold still. */
    OK("Mantente así"),
    NO_FACE("Coloca tu rostro dentro del marco"),
    OUTSIDE_GUIDE("Vuelve a colocar tu rostro dentro del óvalo"),
    MULTIPLE_FACES("Debe haber solo una persona frente a la cámara"),
    MOVE_CLOSER("Acércate un poco más a la cámara"),
    MOVE_AWAY("Aléjate un poco de la cámara"),
    LOOK_STRAIGHT("Mira de frente a la cámara"),
    STRAIGHTEN_HEAD("Endereza la cabeza"),
    TOO_DARK("Busca un lugar con más luz"),
    TOO_BRIGHT("Hay demasiada luz de fondo, cámbiate de lugar"),
    LOW_QUALITY("Sostén el teléfono firme, la imagen se ve borrosa"),
}

/** Why a session ended early. Every message is safe to show the user. */
enum class FailureReason(val messageEs: String) {
    /** A step ran past its deadline. */
    TIMEOUT("Se acabó el tiempo. Vuelve a intentarlo."),

    /** The face left the frame for longer than the grace period. */
    FACE_LOST("Perdimos tu rostro. Mantente dentro del marco e intenta de nuevo."),

    /**
     * The tracked face was replaced by a different one mid-session.
     *
     * This is the check that stops a session being handed from one person to
     * another between challenges — one person positions, another completes the
     * turns. It is UX enforcement, not proof: see [ChallengeMachine].
     */
    FACE_SWAPPED("Detectamos un cambio de persona. Vuelve a empezar."),

    /** More than one face appeared in frame. */
    MULTIPLE_FACES("Debe haber solo una persona frente a la cámara."),

    /** The host app or the user aborted the session. */
    CANCELLED("La verificación fue cancelada."),
}

/** One challenge and the single frame chosen to represent it. */
data class ChallengeCapture(
    val challenge: Challenge,
    /** The frame at the most extreme point of the movement. */
    val frame: FaceFrame,
)

/**
 * The frames a completed session selected.
 *
 * This is a record of what happened on device, useful for debugging and for a
 * host app that wants to show the user what it saw. It is **not** evidence in
 * the security sense and nothing here is sent to the backend as proof: the
 * backend receives a still photo and decides for itself.
 */
data class LivenessEvidence(
    /** The most frontal, sharpest frame seen while positioning. */
    val primary: FaceFrame,
    /** One entry per challenge, in the order the plan asked for them. */
    val challenges: List<ChallengeCapture>,
) {
    /** The primary frame followed by each challenge frame. */
    val frames: List<FaceFrame> get() = listOf(primary) + challenges.map { it.frame }

    /** Wall time from the primary frame to the last challenge frame. */
    val durationMs: Long
        get() = (challenges.lastOrNull()?.frame?.timestampMs ?: primary.timestampMs) -
            primary.timestampMs
}
