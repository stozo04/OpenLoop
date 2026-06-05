package io.github.stozo04.openloop.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = "io.github.stozo04.openloop",
        // Check if there are other critical paths like Camera or Video Editor
        includeInStartupProfile = true
    ) {
        // This block defines the interactions to be profiled.
        // For a basic profile, just starting the app is often enough, 
        // but we can add more interactions here.
        pressHome()
        startActivityAndWait()
    }
}
