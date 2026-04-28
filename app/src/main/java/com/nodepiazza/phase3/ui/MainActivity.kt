package com.nodepiazza.phase3.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
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

class MainActivity : ComponentActivity() {

    private lateinit var appState: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appState = AppState.get(this)
        Services.init(this, appState)
        if (hasAllPermissions(this)) BleScanService.start(this)
        handleOpenChatIntent(intent)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RootScreen(appState, Services.ble)
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
        val addr = intent?.getStringExtra(EXTRA_OPEN_CHAT_ADDRESS) ?: return
        appState.openChat(addr)
    }

    companion object {
        const val EXTRA_OPEN_CHAT_ADDRESS = "open_chat_address"
    }
}

@Composable
fun RootScreen(state: AppState, ble: BleCore) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasAllPermissions(ctx)) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        granted = result.values.all { it }
        if (granted) BleScanService.start(ctx)
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(requiredPermissions())
    }

    if (!granted) {
        PermissionGate { launcher.launch(requiredPermissions()) }
        return
    }

    val activeChat by state.activeChatAddress.collectAsStateWithLifecycle()
    if (activeChat != null) {
        ChatScreen(state, ble, activeChat!!)
    } else {
        MainScreen(state)
    }
}
