package com.borealnetwork.facecheck.camera

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

/**
 * Executor passed to ML Kit task listeners.
 *
 * ML Kit can finish/cancel an in-flight detection after the host screen already
 * closed the camera controller. A raw [ExecutorService] rejects those late
 * listener deliveries after shutdown, and Google Play Services surfaces that
 * rejection as a main-thread crash. This wrapper lets the final listener drain
 * synchronously instead, so cleanup callbacks such as ImageProxy.close() still
 * run and the UI transition remains safe.
 */
internal class MlKitCallbackExecutor(
    private val delegate: ExecutorService,
) : Executor {

    override fun execute(command: Runnable) {
        try {
            delegate.execute(command)
        } catch (_: RejectedExecutionException) {
            command.run()
        }
    }
}
