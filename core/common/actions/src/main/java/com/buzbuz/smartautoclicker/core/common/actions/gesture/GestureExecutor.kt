/*
 * Copyright (C) 2025 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.buzbuz.smartautoclicker.core.common.actions.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.util.Log

import com.buzbuz.smartautoclicker.core.base.Dumpable
import com.buzbuz.smartautoclicker.core.base.addDumpTabulationLvl

import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull


@Singleton
internal class GestureExecutor @Inject constructor() : Dumpable {

    private var currentContinuation: Continuation<Boolean>? = null

    private var completedGestures: Long = 0L
    private var cancelledGestures: Long = 0L
    private var errorGestures: Long = 0L
    private var timedOutGestures: Long = 0L


    fun clear() {
        completedGestures = 0L
        cancelledGestures = 0L
        errorGestures = 0L
        timedOutGestures = 0L
        currentContinuation = null
    }

    suspend fun dispatchGesture(service: AccessibilityService, gesture: GestureDescription): Boolean {
        if (currentContinuation != null) {
            Log.w(TAG, "Previous gesture result is not available yet, clearing listener to avoid stale events")
            currentContinuation = null
        }

        // FIX (Android 16+): Always create a NEW GestureResultCallback for every gesture.
        // The original code reused the same callback instance (`resultCallback ?: new…`).
        // On Android 16 the framework can deliver a completion event for a previous gesture
        // *after* a new one has been dispatched, causing the stale callback to resume the
        // new continuation immediately with a false result → processing loop permanently
        // suspended (frozen detection / no more clicks).
        val callback = newGestureResultCallback()

        // FIX (Android 16+): Wrap in withTimeoutOrNull so the processing coroutine is NEVER
        // permanently suspended. On Android 16 the InputDispatcher can silently drop gestures
        // (blocked state, Pixel bug #384188031 or similar) meaning neither onCompleted nor
        // onCancelled fires. Without a timeout the suspendCoroutine blocks forever → UI frozen.
        //
        // Timeout is set to gesture duration + 3 s headroom, min 3 s for instant clicks.
        val gestureDurationMs = runCatching {
            gesture.strokeCount.let { count ->
                (0 until count).maxOfOrNull { gesture.getStroke(it).duration } ?: 0L
            }
        }.getOrDefault(0L)
        val timeoutMs = gestureDurationMs + GESTURE_TIMEOUT_HEADROOM_MS

        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                currentContinuation = continuation

                continuation.invokeOnCancellation {
                    // Coroutine was cancelled (timeout or scope cancel) — clear reference
                    currentContinuation = null
                }

                try {
                    service.dispatchGesture(gesture, callback, null)
                } catch (rEx: RuntimeException) {
                    Log.w(TAG, "System is not responsive, the user might be spamming gesture too quickly", rEx)
                    errorGestures++
                    resumeExecution(gestureError = true)
                }
            }
        }

        if (result == null) {
            // Timeout hit — gesture callback never fired
            timedOutGestures++
            currentContinuation = null
            Log.w(TAG, "dispatchGesture timed out after ${timeoutMs}ms (InputDispatcher may be blocked on Android 16)")
            return false
        }

        return result
    }

    private fun resumeExecution(gestureError: Boolean) {
        currentContinuation?.let { continuation ->
            currentContinuation = null

            try {
                continuation.resume(!gestureError)
            } catch (isEx: IllegalStateException) {
                Log.w(TAG, "Continuation have already been resumed. Did the same event got two results ?", isEx)
            }
        } ?: Log.w(TAG, "Can't resume continuation. Did the same event got two results ?")
    }

    private fun newGestureResultCallback() = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            completedGestures++
            resumeExecution(gestureError = false)
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            cancelledGestures++
            resumeExecution(gestureError = false)
        }
    }

    override fun dump(writer: PrintWriter, prefix: CharSequence) {
        val contentPrefix = "${prefix.addDumpTabulationLvl()}- "

        writer.apply {
            append(prefix).println("* GestureExecutor:")
            append(contentPrefix).append("Completed=$completedGestures").println()
            append(contentPrefix).append("Cancelled=$cancelledGestures").println()
            append(contentPrefix).append("Error=$errorGestures").println()
            append(contentPrefix).append("TimedOut=$timedOutGestures").println()
        }
    }
}

private const val TAG = "GestureExecutor"

/**
 * Extra headroom added on top of the gesture's own stroke duration before we declare a timeout.
 * 3 seconds is enough to cover normal system latency, including slow Samsung/Pixel dispatchers.
 */
private const val GESTURE_TIMEOUT_HEADROOM_MS = 3_000L
