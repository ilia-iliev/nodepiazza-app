package com.nodepiazza.phase3.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodepiazza.phase3.AppState
import com.nodepiazza.phase3.Services
import com.nodepiazza.phase3.ble.BleCore
import com.nodepiazza.phase3.ble.BleScanService
import com.nodepiazza.phase3.ble.PeerCoordinator

class MainActivity : ComponentActivity() {

    private lateinit var appState: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appState = AppState.get(this)
        Services.init(this, appState)
        handleOpenChatIntent(intent)

        setContent {
            MaterialTheme(colorScheme = NodepiazzaLightColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RootScreen(appState, Services.coordinator, Services.ble)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenChatIntent(intent)
    }

    private fun handleOpenChatIntent(intent: Intent?) {
        val deviceId = intent?.getStringExtra(EXTRA_OPEN_CHAT_DEVICE_ID) ?: return
        Services.coordinator.openChat(deviceId)
    }

    companion object {
        const val EXTRA_OPEN_CHAT_DEVICE_ID = "open_chat_device_id"
    }
}

@Composable
fun RootScreen(state: AppState, coordinator: PeerCoordinator, ble: BleCore) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasAllPermissions(ctx)) }
    val bleEnabled by state.bleEnabled.collectAsStateWithLifecycle()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        granted = result.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(requiredPermissions())
    }

    LaunchedEffect(granted, bleEnabled) {
        if (granted && bleEnabled) {
            BleScanService.start(ctx)
        } else {
            BleScanService.stop(ctx)
        }
    }

    if (!granted) {
        PermissionGate { launcher.launch(requiredPermissions()) }
        return
    }

    val activeChat by coordinator.activeChatDeviceId.collectAsStateWithLifecycle()
    if (activeChat != null) {
        ChatScreen(coordinator, ble, activeChat!!)
    } else {
        MainScreen(state, coordinator)
    }
}
