package com.airtouch.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GestureEngineTest {
    @Test
    fun legacyOutOfRangeSensitivityFallsBackToResponsiveDefault() {
        assertEquals(0.60f, normalizeSensitivity(0.92f))
        assertEquals(0.60f, normalizeSensitivity(Float.NaN))
        assertEquals(0.60f, normalizeSensitivity(0.20f))
        assertEquals(0.75f, normalizeSensitivity(0.75f))
    }

    @Test
    fun lowConfidenceWithHandLandmarksShowsHandDetectedFeedback() {
        val events = mutableListOf<RecognitionEvent>()
        val engine = GestureEngine(0.60f, false, true, false, events::add)

        engine.onFrame("Thumb_Up", 0.45f, 0.5f, 1_000L)

        assertEquals("Hand detected · adjust pose", events.last().gesture)
        assertEquals("Gesture confidence 45% · keep your hand centered and well lit", events.last().status)
    }

    @Test
    fun thumbsUpTriggersOnlyAfterHoldThreshold() {
        val events = mutableListOf<RecognitionEvent>()
        val engine = GestureEngine(0.60f, false, true, false, events::add)

        engine.onFrame("Thumb_Up", 0.9f, 0.5f, 1_000L)
        engine.onFrame("Thumb_Up", 0.9f, 0.5f, 1_599L)
        assertNull(events.last().action)
        engine.onFrame("Thumb_Up", 0.9f, 0.5f, 1_600L)

        assertEquals("Play / resume", events.last().action)
    }

    @Test
    fun closedFistTriggersPauseAfterHoldThreshold() {
        val events = mutableListOf<RecognitionEvent>()
        val engine = GestureEngine(0.60f, false, true, false, events::add)

        engine.onFrame("Closed_Fist", 0.9f, 0.5f, 1_000L)
        engine.onFrame("Closed_Fist", 0.9f, 0.5f, 1_600L)

        assertEquals("Pause", events.last().action)
    }

    @Test
    fun openPalmSwipeTriggersNextItem() {
        val events = mutableListOf<RecognitionEvent>()
        val engine = GestureEngine(0.60f, false, true, false, events::add)

        engine.onFrame("Open_Palm", 0.9f, 0.30f, 1_000L)
        engine.onFrame("Open_Palm", 0.9f, 0.60f, 1_200L)

        assertEquals("Next item", events.last().action)
    }

    @Test
    fun twoFingerSwipeUpScrollsUp() {
        val events = mutableListOf<RecognitionEvent>()
        val engine = GestureEngine(0.60f, false, true, false, events::add)

        engine.onFrame("Victory", 0.9f, 0.5f, 1_000L, 0.70f)
        engine.onFrame("Victory", 0.9f, 0.5f, 1_200L, 0.42f)

        assertEquals("Scroll up", events.last().action)
    }

    @Test
    fun twoFingerSwipeDownScrollsDown() {
        val events = mutableListOf<RecognitionEvent>()
        val engine = GestureEngine(0.60f, false, true, false, events::add)

        engine.onFrame("Victory", 0.9f, 0.5f, 1_000L, 0.30f)
        engine.onFrame("Victory", 0.9f, 0.5f, 1_200L, 0.58f)

        assertEquals("Scroll down", events.last().action)
    }

    @Test
    fun openPalmSwipeDoesNotTriggerScreenScrolling() {
        val events = mutableListOf<RecognitionEvent>()
        val engine = GestureEngine(0.60f, false, true, false, events::add)

        engine.onFrame("Open_Palm", 0.9f, 0.5f, 1_000L, 0.70f)
        engine.onFrame("Open_Palm", 0.9f, 0.5f, 1_200L, 0.42f)

        assertNull(events.last().action)
    }

    @Test
    fun palmLockUsesEightHundredMillisecondHold() {
        val events = mutableListOf<RecognitionEvent>()
        val engine = GestureEngine(0.60f, true, true, false, events::add)

        engine.onFrame("Open_Palm", 0.9f, 0.5f, 1_000L)
        engine.onFrame("Open_Palm", 0.9f, 0.5f, 1_799L)
        assertNull(events.last().action)
        engine.onFrame("Open_Palm", 0.9f, 0.5f, 1_800L)

        assertEquals("Locking screen", events.last().action)
    }

    @Test
    fun lowConfidenceFrameResetsHoldTimer() {
        val events = mutableListOf<RecognitionEvent>()
        val engine = GestureEngine(0.60f, false, true, false, events::add)

        engine.onFrame("Thumb_Up", 0.9f, 0.5f, 1_000L)
        engine.onFrame("Thumb_Up", 0.5f, 0.5f, 1_500L)
        engine.onFrame("Thumb_Up", 0.9f, 0.5f, 1_600L)
        engine.onFrame("Thumb_Up", 0.9f, 0.5f, 2_199L)
        assertNull(events.last().action)
        engine.onFrame("Thumb_Up", 0.9f, 0.5f, 2_200L)

        assertEquals("Play / resume", events.last().action)
    }

    @Test
    fun disabledControlsDoNotTriggerActions() {
        val events = mutableListOf<RecognitionEvent>()
        val engine = GestureEngine(0.60f, false, false, false, events::add)

        engine.onFrame("Thumb_Up", 0.9f, 0.5f, 1_000L)
        engine.onFrame("Thumb_Up", 0.9f, 0.5f, 2_000L)

        assertNull(events.last().action)
        assertEquals("Gestures are off", events.last().gesture)
    }
}
