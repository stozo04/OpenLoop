package com.openrang.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openrang.app.R
import com.openrang.app.camera.CameraManager
import com.openrang.app.data.UserPreferencesRepositoryImpl
import com.openrang.app.data.VideoStorageRepositoryImpl
import com.openrang.app.data.dataStore
import com.openrang.app.ui.CameraScreen
import com.openrang.app.ui.GalleryScreen
import com.openrang.app.ui.OnboardingScreen
import com.openrang.app.ui.OpenRangUiState
import com.openrang.app.ui.OpenRangViewModel
import com.openrang.app.ui.PreviewScreen

class MainActivity : ComponentActivity() {
    private val viewModel: OpenRangViewModel by viewModels {
        // Bridge Context → repositories here, once. applicationContext is the long-lived,
        // safe Context to read dataStore / cacheDir / filesDir from; nothing downstream
        // (Factory, ViewModel) ever sees a Context.
        OpenRangViewModel.Factory(
            UserPreferencesRepositoryImpl(applicationContext.dataStore),
            VideoStorageRepositoryImpl(
                cacheDir = applicationContext.cacheDir,
                filesDir = applicationContext.filesDir,
            ),
        )
    }
    private lateinit var cameraManager: CameraManager

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    // Permission request contract launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        viewModel.onPermissionsChecked(cameraGranted && audioGranted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate(): hands the system splash to core-splashscreen,
        // which then swaps to postSplashScreenTheme (Theme.OpenRang) for the app window.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        cameraManager = CameraManager(this)

        setContent {
            OpenRangTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                    // Auto-trigger permission check when state reaches CheckingPermissions
                    // (from either Initializing→CheckingPermissions for returning users,
                    //  or Onboarding→CheckingPermissions for first-time users)
                    LaunchedEffect(uiState) {
                        if (uiState is OpenRangUiState.CheckingPermissions) {
                            checkPermissions()
                        }
                    }

                    when (uiState) {
                        is OpenRangUiState.Initializing -> {
                            InfinityLoadingScreen()
                        }
                        is OpenRangUiState.Onboarding -> {
                            OnboardingScreen(
                                onGetStartedClick = {
                                    viewModel.onOnboardingCompleted()
                                }
                            )
                        }
                        is OpenRangUiState.CheckingPermissions -> {
                            InfinityLoadingScreen()
                        }
                        is OpenRangUiState.PermissionRationale -> {
                            PermissionExplanationScreen(
                                title = "We need a quick permission",
                                body = "OpenRang needs Camera and Audio to capture your " +
                                    "video loops. Tap Grant to continue.",
                                primaryActionLabel = "Grant Permissions",
                                onPrimaryAction = { onRationaleAcknowledged() },
                                secondaryActionLabel = "Not now",
                                onSecondaryAction = { viewModel.onRationaleDeclined() }
                            )
                        }
                        is OpenRangUiState.PermissionDenied -> {
                            PermissionExplanationScreen(
                                title = "Permissions Required",
                                body = "OpenRang needs Camera and Audio recording permissions " +
                                    "to capture high-quality speed-controlled video loops.",
                                primaryActionLabel = "Try Again",
                                onPrimaryAction = { checkPermissions() },
                                secondaryActionLabel = "Open Device Settings",
                                onSecondaryAction = { openAppSettings() }
                            )
                        }
                        is OpenRangUiState.ReadyToCapture -> {
                            CameraScreen(
                                viewModel = viewModel,
                                cameraManager = cameraManager
                            )
                        }
                        is OpenRangUiState.Recording -> {
                            CameraScreen(
                                viewModel = viewModel,
                                cameraManager = cameraManager
                            )
                        }
                        is OpenRangUiState.LoopingPreview -> {
                            val state = uiState as OpenRangUiState.LoopingPreview
                            PreviewScreen(
                                videoPath = state.videoPath,
                                onBackToCaptureClick = { viewModel.resetToCapture() }
                            )
                        }
                        is OpenRangUiState.Gallery -> {
                            GalleryScreen(
                                viewModel = viewModel,
                                onBackClick = { viewModel.navigateBackFromGallery() }
                            )
                        }
                        else -> {
                            // Fallback to camera preview screen for developmental support
                            CameraScreen(
                                viewModel = viewModel,
                                cameraManager = cameraManager
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissions() {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        when {
            allGranted -> viewModel.onPermissionsChecked(true)

            // Denied at least once but not permanently — explain before re-asking.
            requiredPermissions.any { shouldShowRequestPermissionRationale(it) } ->
                viewModel.showPermissionRationale()

            // First request, or permanently denied — the system handles both. A permanent
            // denial returns granted=false from the launcher, routing to PermissionDenied.
            else -> requestPermissionLauncher.launch(requiredPermissions)
        }
    }

    private fun onRationaleAcknowledged() {
        viewModel.onRationaleAcknowledged()
        // Launch the system dialog directly, bypassing checkPermissions(), so we don't
        // re-enter the rationale branch (shouldShowRequestPermissionRationale stays true
        // until the user actually responds to the dialog).
        requestPermissionLauncher.launch(requiredPermissions)
    }

    private fun openAppSettings() {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            startActivity(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.shutdown()
    }
}

/**
 * Loading screen shown during app init / permission checks. Renders the same neon infinity as
 * the launcher icon and system splash, on a matching black field, so the system splash hands off
 * to this screen with no visible seam. Static by design — no artificial hold, so the loader only
 * shows for the natural (sub-second) init window and the user gets straight into the app.
 */
@Composable
fun InfinityLoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "Loading",
            modifier = Modifier.size(200.dp)
        )
    }
}

/**
 * Educational permission screen reused for both the rationale step (before re-asking) and the
 * permanent-denial step. The optional secondary action is "Not now" (cancel) on the rationale
 * variant and "Open Device Settings" on the denial variant; omit both [secondaryActionLabel] and
 * [onSecondaryAction] to render only the primary button.
 */
@Composable
fun PermissionExplanationScreen(
    title: String,
    body: String,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF1F1C2C), Color(0xFF3A3B45))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xCC1A1A1D))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color(0x1AFF5252))
                    .border(2.dp, Color(0xFFFF5252), androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "!",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF5252)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = body,
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onPrimaryAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF5252)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = primaryActionLabel,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            if (secondaryActionLabel != null && onSecondaryAction != null) {
                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onSecondaryAction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                ) {
                    Text(
                        text = secondaryActionLabel,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun OpenRangTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFFF5252),
            secondary = Color(0xFF7C4DFF),
            background = Color(0xFF121212)
        ),
        content = content
    )
}
