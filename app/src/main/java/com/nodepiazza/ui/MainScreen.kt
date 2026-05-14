@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.nodepiazza.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodepiazza.AppState
import com.nodepiazza.protocol.InterestsPayload
import com.nodepiazza.protocol.Protocol
import com.nodepiazza.ble.BleCore
import com.nodepiazza.ble.PeerCoordinator

@Composable
fun MainScreen(state: AppState, coordinator: PeerCoordinator, ble: BleCore) {
    val interests by state.interests.collectAsStateWithLifecycle()
    val peers by coordinator.peers.collectAsStateWithLifecycle()
    val bleEnabled by state.bleEnabled.collectAsStateWithLifecycle()
    val aboutMe by state.aboutMe.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    AppBackground {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("nodepiazza") },
                actions = {
                    Row(
                        modifier = Modifier.padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ModelPickerChip()
                        Spacer(Modifier.width(4.dp))
                        Switch(
                            checked = bleEnabled,
                            onCheckedChange = { state.setBleEnabled(it) },
                            modifier = Modifier.semantics { contentDescription = "Scan" },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                },
        ) {
            val matched = peers.values.filter { it.matched }.sortedBy { it.label }
            val others = peers.values.filterNot { it.matched }.sortedBy { it.label }
            val budget = remember(interests) {
                InterestsPayload.evaluate(interests.map { it.text })
            }
            val contentMax = Protocol.MAX_PAYLOAD_BYTES - InterestsPayload.HEADER_BYTES
            val contentUsed = (budget.wouldUseBytes - InterestsPayload.HEADER_BYTES)
                .coerceAtLeast(0)
            val percent = if (contentMax > 0) (contentUsed * 100) / contentMax else 0
            ModelStatusBanner()
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                item {
                    InterestsHeader(percent = percent, overflow = budget.overflow)
                }
                if (budget.overflow) {
                    item { OverflowBanner() }
                }
                item {
                    InterestChips(
                        interests = interests,
                        fits = budget.fits,
                        onUpdateText = state::updateInterest,
                        onAdd = state::addInterest,
                        onRemove = state::removeInterest,
                        onDismissPlaceholders = state::dismissPlaceholders,
                    )
                }

                item { Spacer(Modifier.height(20.dp)) }
                item { AboutMeSection(value = aboutMe, onChange = state::setAboutMe) }

                item { Spacer(Modifier.height(20.dp)) }
                item { SectionLabel("Nearby peers") }
                if (peers.isEmpty()) {
                    item { ScanStatusHint(scanning = bleEnabled) }
                }
                items(matched, key = { it.deviceId }) { peer ->
                    MatchedPeerRow(peer, onOpen = { ble.openChat(peer.deviceId) })
                }

                if (others.isNotEmpty()) {
                    item { Spacer(Modifier.height(16.dp)) }
                    item { SectionLabel("Other devices nearby") }
                    items(others, key = { it.deviceId }) { peer ->
                        OtherPeerRow(peer, onOpen = { ble.openChat(peer.deviceId) })
                    }
                }
            }
        }
    }
    }
}
