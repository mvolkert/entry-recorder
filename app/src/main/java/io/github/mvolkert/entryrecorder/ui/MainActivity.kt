package io.github.mvolkert.entryrecorder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.mvolkert.entryrecorder.EntryRecorderApp
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.service.IntercomMonitorService
import io.github.mvolkert.entryrecorder.ui.live.LiveCamerasScreen
import io.github.mvolkert.entryrecorder.ui.recordings.RecordingsScreen
import io.github.mvolkert.entryrecorder.ui.settings.SettingsScreen
import io.github.mvolkert.entryrecorder.ui.settings.SettingsViewModel

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Live : Screen("live", "Live View", Icons.Default.Videocam)
    object Recordings : Screen("recordings", "Recordings", Icons.Default.VideoLibrary)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

    private val app by lazy { application as EntryRecorderApp }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Monitor service can be started or refreshed
        IntercomMonitorService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()
        IntercomMonitorService.start(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                MainAppScaffold()
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    @Composable
    fun MainAppScaffold() {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route

        val items = listOf(Screen.Live, Screen.Recordings, Screen.Settings)
        val settingsViewModel: SettingsViewModel = viewModel()

        Scaffold(
            bottomBar = {
                NavigationBar {
                    items.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title) },
                            selected = currentRoute == screen.route,
                            onClick = {
                                if (currentRoute != screen.route) {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        )
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Live.route,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                composable(Screen.Live.route) {
                    LiveCamerasScreen(
                        settingsViewModel = settingsViewModel,
                        onStartManualRecording = { device ->
                            app.recorder.startRecording(device, EventType.MANUAL, 120)
                        },
                        onStopManualRecording = { device ->
                            app.recorder.stopRecording(device.id)
                        },
                        isDeviceRecording = { deviceId ->
                            app.recorder.isRecording(deviceId)
                        }
                    )
                }

                composable(Screen.Recordings.route) {
                    RecordingsScreen()
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(viewModel = settingsViewModel)
                }
            }
        }
    }
}
