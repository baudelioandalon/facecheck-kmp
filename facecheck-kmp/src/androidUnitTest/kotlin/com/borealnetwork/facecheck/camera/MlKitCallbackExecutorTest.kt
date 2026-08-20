package com.borealnetwork.facecheck.camera

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class MlKitCallbackExecutorTest {

    @Test
    fun `callback executor drains callback instead of throwing after delegate shutdown`() {
        val delegate = Executors.newSingleThreadExecutor()
        val executor = MlKitCallbackExecutor(delegate)
        delegate.shutdown()

        var callbacks = 0
        try {
            executor.execute { callbacks += 1 }
        } catch (error: RejectedExecutionException) {
            fail("ML Kit callback executor must not reject late callbacks: ${error.message}")
        }

        assertEquals(1, callbacks)
    }
}
