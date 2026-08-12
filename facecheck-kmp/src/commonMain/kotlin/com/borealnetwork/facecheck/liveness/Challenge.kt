package com.borealnetwork.facecheck.liveness

import kotlin.random.Random

/**
 * One action the user is asked to perform during a liveness session.
 *
 * The set is deliberately tiny. Every extra challenge type is another way for a
 * real user to fail, and none of them are a security control on their own — see
 * the class documentation on [ChallengeMachine].
 *
 * [instructionEs] is shown to the end user verbatim.
 */
sealed interface Challenge {
    /** Stable identifier for logs and analytics. Never localised. */
    val id: String

    /** The instruction as the end user reads it. Spanish, imperative, short. */
    val instructionEs: String

    /**
     * Turn the head to the user's own left, which is a negative yaw.
     *
     * See [FaceFrame.yaw] for the sign convention — getting it backwards is the
     * single easiest way to ship an SDK that tells everyone to turn the wrong
     * way and then fails them for complying.
     */
    data object TurnLeft : Challenge {
        override val id: String = "turn_left"
        override val instructionEs: String = "Gira la cabeza a la izquierda"
    }

    /** Turn the head to the user's own right, which is a positive yaw. */
    data object TurnRight : Challenge {
        override val id: String = "turn_right"
        override val instructionEs: String = "Gira la cabeza a la derecha"
    }

    /** Hold a frontal pose. Used to bookend a sequence of turns. */
    data object Center : Challenge {
        override val id: String = "center"
        override val instructionEs: String = "Mira de frente a la cámara"
    }

    companion object {
        /** Every challenge the SDK knows how to score. */
        val all: List<Challenge> = listOf(TurnLeft, TurnRight, Center)

        fun fromId(id: String): Challenge? = all.firstOrNull { it.id == id }
    }
}

/**
 * Builds the sequence of challenges for one session.
 *
 * Randomising the order raises the cost of a pre-recorded video only slightly —
 * an attacker who controls the device can read the plan out of memory before the
 * first frame is drawn. It is worth doing anyway because it defeats the lazy
 * attack (one recording replayed forever) at zero cost to the honest user, but
 * it must not be mistaken for the reason the system is safe.
 */
object ChallengePlan {

    /** Bounds enforced on [random]; also the range [FaceCheckConfig] accepts. */
    const val MIN_CHALLENGES: Int = 1
    const val MAX_CHALLENGES: Int = 5

    /**
     * A plan of [count] challenges with no two identical in a row.
     *
     * The first challenge is always a turn: [Challenge.Center] immediately after
     * positioning is already satisfied by the pose the user had to hold to get
     * there, so leading with it would ask for nothing.
     *
     * [random] is injectable so tests get a fixed plan without reaching into
     * internals.
     */
    fun random(count: Int, random: Random = Random.Default): List<Challenge> {
        require(count in MIN_CHALLENGES..MAX_CHALLENGES) {
            "count must be in $MIN_CHALLENGES..$MAX_CHALLENGES, was $count"
        }
        val turns = listOf(Challenge.TurnLeft, Challenge.TurnRight)
        val plan = ArrayList<Challenge>(count)
        plan += turns[random.nextInt(turns.size)]
        while (plan.size < count) {
            val candidates = Challenge.all.filter { it != plan.last() }
            plan += candidates[random.nextInt(candidates.size)]
        }
        return plan
    }
}