package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.Position
import com.example.model.TrackerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Cleaner Tracker", appName)
  }

  @Test
  fun `test position and tracker state initialization`() {
    val initialPos = Position(x = 0.0, y = 0.0, floor = 1)
    val state = TrackerState(
        isTracking = false,
        currentPosition = initialPos,
        stepCount = 0,
        totalDistance = 0.0
    )
    assertEquals(0.0, state.currentPosition.x, 0.001)
    assertEquals(0.0, state.currentPosition.y, 0.001)
    assertEquals(1, state.currentPosition.floor)
    assertEquals(0, state.stepCount)
  }
}

