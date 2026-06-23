package io.github.stozo04.openloop.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real [preferencesDataStore] round-trip on the JVM — covers the production
 * [UserPreferencesRepositoryImpl] that ViewModel fakes stand in for.
 */
@RunWith(RobolectricTestRunner::class)
class UserPreferencesRepositoryImplRobolectricTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearDataStoreFile() {
        File(context.filesDir, "datastore/openloop_preferences.preferences_pb").delete()
    }

    @Test
    fun freshStore_defaultsOnboardingIncomplete() = runTest {
        val repo = UserPreferencesRepositoryImpl(context.dataStore)

        assertFalse(repo.hasCompletedOnboarding.first())
    }

    @Test
    fun setOnboardingCompleted_persistsAndReadsBack() = runTest {
        val repo = UserPreferencesRepositoryImpl(context.dataStore)

        repo.setOnboardingCompleted(true)

        assertTrue(repo.hasCompletedOnboarding.first())
    }
}
