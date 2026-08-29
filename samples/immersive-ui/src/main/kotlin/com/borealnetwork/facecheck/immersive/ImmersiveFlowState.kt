package com.borealnetwork.facecheck.immersive

sealed interface ImmersiveFlowState {
    data object Positioning : ImmersiveFlowState
    data object Ready : ImmersiveFlowState
    data class Running(val step: Int, val total: Int = TOTAL_STEPS) : ImmersiveFlowState
    data object Saving : ImmersiveFlowState
    data object Completed : ImmersiveFlowState

    private companion object {
        const val TOTAL_STEPS = 3
    }
}

sealed interface ImmersiveFlowEvent {
    data class FaceFrameUpdated(
        val faceCount: Int,
        val insideOval: Boolean,
        val stableForMs: Long,
    ) : ImmersiveFlowEvent

    data object StartPressed : ImmersiveFlowEvent
    data object ChallengeCompleted : ImmersiveFlowEvent
    data object ErrorRaised : ImmersiveFlowEvent
    data object Completed : ImmersiveFlowEvent
}

class ImmersiveFlowReducer(
    initial: ImmersiveFlowState = ImmersiveFlowState.Positioning,
) {

    var state: ImmersiveFlowState = initial
        private set

    fun reduce(event: ImmersiveFlowEvent): ImmersiveFlowState {
        state = when (event) {
            is ImmersiveFlowEvent.FaceFrameUpdated -> when (state) {
                ImmersiveFlowState.Positioning,
                ImmersiveFlowState.Ready,
                -> if (event.faceCount == 1 &&
                    event.insideOval &&
                    event.stableForMs >= READY_HOLD_MS
                ) {
                    ImmersiveFlowState.Ready
                } else {
                    ImmersiveFlowState.Positioning
                }

                else -> state
            }

            ImmersiveFlowEvent.StartPressed -> when (state) {
                ImmersiveFlowState.Ready -> ImmersiveFlowState.Running(step = 0)
                else -> state
            }

            ImmersiveFlowEvent.ChallengeCompleted -> when (val current = state) {
                is ImmersiveFlowState.Running -> if (current.step + 1 < current.total) {
                    current.copy(step = current.step + 1)
                } else {
                    ImmersiveFlowState.Saving
                }

                else -> state
            }

            // The operation has already released the camera and restarted its
            // preview. Return to positioning immediately for every failure so
            // the oval gate can begin a fresh three-second hold. A separate
            // "Reintentar" tap would duplicate that recovery path.
            ImmersiveFlowEvent.ErrorRaised -> ImmersiveFlowState.Positioning

            ImmersiveFlowEvent.Completed -> ImmersiveFlowState.Completed
        }
        return state
    }

    companion object {
        const val READY_HOLD_MS = 3_000L
    }
}

internal fun stableCountdownSeconds(stableForMs: Long): Int? =
    if (stableForMs <= 0L || stableForMs >= ImmersiveFlowReducer.READY_HOLD_MS) {
        null
    } else {
        ((ImmersiveFlowReducer.READY_HOLD_MS - stableForMs + 999L) / 1_000L)
            .toInt()
            .coerceIn(1, 3)
    }

internal fun stableProgress(stableForMs: Long): Float =
    (stableForMs.toFloat() / ImmersiveFlowReducer.READY_HOLD_MS.toFloat()).coerceIn(0f, 1f)
