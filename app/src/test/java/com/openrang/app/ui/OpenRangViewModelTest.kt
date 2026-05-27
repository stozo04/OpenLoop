package com.openrang.app.ui

import android.content.Context
import android.util.Log
import androidx.camera.video.VideoRecordEvent
import com.openrang.app.camera.CameraManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import androidx.core.util.Consumer

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

class OpenRangViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var viewModel: OpenRangViewModel
    private val context: Context = mockk(relaxed = true)
    private val cameraManager: CameraManager = mockk(relaxed = true)
    private val cacheDir: File = mockk(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        every { context.cacheDir } returns cacheDir
        every { cacheDir.absolutePath } returns "/fake/cache"

        viewModel = OpenRangViewModel()
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `initial state is Onboarding`() {
        assertEquals(OpenRangUiState.Onboarding, viewModel.uiState.value)
    }

    @Test
    fun `onOnboardingCompleted transitions to CheckingPermissions`() {
        viewModel.onOnboardingCompleted()
        assertEquals(OpenRangUiState.CheckingPermissions, viewModel.uiState.value)
    }

    @Test
    fun `onPermissionsChecked when granted transitions to ReadyToCapture`() {
        viewModel.onPermissionsChecked(true)
        assertEquals(OpenRangUiState.ReadyToCapture, viewModel.uiState.value)
    }

    @Test
    fun `onPermissionsChecked when denied transitions to PermissionDenied`() {
        viewModel.onPermissionsChecked(false)
        assertEquals(OpenRangUiState.PermissionDenied, viewModel.uiState.value)
    }

    @Test
    fun `resetToCapture transitions state back to ReadyToCapture`() {
        viewModel.onPermissionsChecked(false) // PermissionDenied
        viewModel.resetToCapture()
        assertEquals(OpenRangUiState.ReadyToCapture, viewModel.uiState.value)
    }

    @Test
    fun `startBurstCapture when not ready does not transition or call camera`() {
        // State is Onboarding, which is not ReadyToCapture
        viewModel.startBurstCapture(context, cameraManager)
        assertEquals(OpenRangUiState.Onboarding, viewModel.uiState.value)
        verify(exactly = 0) { cameraManager.startRecording(any(), any()) }
    }

    @Test
    fun `startBurstCapture successfully starts recording and delays automatic stop`() = runTest {
        viewModel.onPermissionsChecked(true) // Set state to ReadyToCapture
        
        // Mock startRecording to capture and trigger the callback
        val slot = slot<Consumer<VideoRecordEvent>>()
        every { cameraManager.startRecording(any(), capture(slot)) } just Runs

        viewModel.startBurstCapture(context, cameraManager)

        assertEquals(OpenRangUiState.Recording, viewModel.uiState.value)
        verify(exactly = 1) { cameraManager.startRecording(any(), any()) }

        // Simulate 1.5 seconds delay to trigger the automatic capture halt
        advanceTimeBy(1500)
        
        // Verify stopRecording was invoked after the delay
        verify(exactly = 1) { cameraManager.stopRecording() }
    }

    @Test
    fun `startBurstCapture failures fallback state gracefully`() {
        viewModel.onPermissionsChecked(true) // ReadyToCapture
        every { cameraManager.startRecording(any(), any()) } throws RuntimeException("Camera error")

        viewModel.startBurstCapture(context, cameraManager)

        assertEquals(OpenRangUiState.ReadyToCapture, viewModel.uiState.value)
    }

    @Test
    fun `stopBurstCapture cancels coroutine job and stops recording`() {
        viewModel.onPermissionsChecked(true) // ReadyToCapture
        every { cameraManager.startRecording(any(), any()) } just Runs

        viewModel.startBurstCapture(context, cameraManager)
        viewModel.stopBurstCapture(cameraManager)

        verify(exactly = 1) { cameraManager.stopRecording() }
    }

    @Test
    fun `video record event finalize transitions to LoopingPreview on success`() {
        viewModel.onPermissionsChecked(true) // ReadyToCapture
        
        val slot = slot<Consumer<VideoRecordEvent>>()
        every { cameraManager.startRecording(any(), capture(slot)) } just Runs

        viewModel.startBurstCapture(context, cameraManager)

        // Mock a success Finalize event
        val finalizeEvent = mockk<VideoRecordEvent.Finalize>(relaxed = true)
        every { finalizeEvent.hasError() } returns false

        slot.captured.accept(finalizeEvent)

        val state = viewModel.uiState.value
        assertTrue(state is OpenRangUiState.LoopingPreview)
        val previewState = state as OpenRangUiState.LoopingPreview
        assertEquals("/fake/cache/raw_capture.mp4", previewState.videoPath)
        assertEquals(1.5f, previewState.playbackSpeed)
    }

    @Test
    fun `video record event finalize transitions back to ReadyToCapture on error`() {
        viewModel.onPermissionsChecked(true) // ReadyToCapture
        
        val slot = slot<Consumer<VideoRecordEvent>>()
        every { cameraManager.startRecording(any(), capture(slot)) } just Runs

        viewModel.startBurstCapture(context, cameraManager)

        // Mock a failed Finalize event
        val finalizeEvent = mockk<VideoRecordEvent.Finalize>(relaxed = true)
        every { finalizeEvent.hasError() } returns true
        every { finalizeEvent.error } returns VideoRecordEvent.Finalize.ERROR_UNKNOWN

        slot.captured.accept(finalizeEvent)

        assertEquals(OpenRangUiState.ReadyToCapture, viewModel.uiState.value)
    }
}
