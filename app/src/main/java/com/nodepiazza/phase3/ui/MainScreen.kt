@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.nodepiazza.phase3.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodepiazza.phase3.AppState
import com.nodepiazza.phase3.Interest
import com.nodepiazza.phase3.InterestsPayload
import com.nodepiazza.phase3.Peer
import com.nodepiazza.phase3.Protocol
import com.nodepiazza.phase3.ble.PeerCoordinator

@Composable
fun MainScreen(state: AppState, coordinator: PeerCoordinator) {
    val interests by state.interests.collectAsStateWithLifecycle()
    val peers by coordinator.peers.collectAsStateWithLifecycle()
    val bleEnabled by state.bleEnabled.collectAsStateWithLifecycle()
    val aboutMe by state.aboutMe.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    Scaffold(
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
                    )
                }

                item { Spacer(Modifier.height(20.dp)) }
                item { AboutMeSection(value = aboutMe, onChange = state::setAboutMe) }

                item { Spacer(Modifier.height(20.dp)) }
                if (matched.isEmpty()) {
                    item { ScanStatusHint(scanning = bleEnabled) }
                }
                items(matched, key = { it.deviceId }) { peer ->
                    MatchedPeerRow(peer, onOpen = { coordinator.openChat(peer.deviceId) })
                }

                if (others.isNotEmpty()) {
                    item { Spacer(Modifier.height(16.dp)) }
                    item { SectionLabel("Other devices nearby") }
                    items(others, key = { it.deviceId }) { peer ->
                        OtherPeerRow(peer)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InterestsHeader(percent: Int, overflow: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel("Talk to me about")
        Spacer(Modifier.weight(1f))
        // Gray below 70%; ramps to error red between 70% and 100%; stays red beyond.
        val frac = ((percent - 70).coerceAtLeast(0) / 30f).coerceIn(0f, 1f)
        val color = lerp(
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.error,
            frac,
        )
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelMedium,
            color = if (overflow) MaterialTheme.colorScheme.error else color,
            fontWeight = if (overflow) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun AboutMeSection(value: String, onChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Only your phone sees")
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "private",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "Private to your device. Never broadcast",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = LocalContentColor.current),
                cursorBrush = SolidColor(LocalContentColor.current),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            "Anything that might help decide who you'd want to meet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalContentColor.current.copy(alpha = 0.55f),
                        )
                    }
                    inner()
                },
            )
        }
    }
}

@Composable
private fun OverflowBanner() {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Ignoring interests over the limit",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun InterestChips(
    interests: List<Interest>,
    fits: List<Boolean>,
    onUpdateText: (String, String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        interests.forEachIndexed { index, interest ->
            InterestChip(
                interest = interest,
                overLimit = fits.getOrNull(index) == false,
                onCommitText = { onUpdateText(interest.id, it) },
                onDelete = { onRemove(interest.id) },
            )
        }
        AddInterestChip(onAdd = onAdd)
    }
}

@Composable
private fun InterestChip(
    interest: Interest,
    overLimit: Boolean,
    onCommitText: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var editing by remember(interest.id) { mutableStateOf(false) }
    var initialCursor by remember(interest.id) { mutableStateOf(interest.text.length) }
    var menuOpen by remember(interest.id) { mutableStateOf(false) }

    var chipCoords by remember(interest.id) { mutableStateOf<LayoutCoordinates?>(null) }
    var textCoords by remember(interest.id) { mutableStateOf<LayoutCoordinates?>(null) }
    var textLayout by remember(interest.id) { mutableStateOf<TextLayoutResult?>(null) }

    Box {
        if (editing) {
            ChipEditField(
                initial = interest.text,
                initialCursor = initialCursor,
                placeholder = null,
                onCommit = { committed ->
                    editing = false
                    if (committed != interest.text) onCommitText(committed)
                },
            )
        } else {
            val bg = if (overLimit) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer
            val fg = if (overLimit) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSecondaryContainer
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = bg,
                contentColor = fg,
                modifier = Modifier
                    .onGloballyPositioned { chipCoords = it }
                    .pointerInput(interest.id) {
                        detectTapGestures(
                            onTap = { tap ->
                                initialCursor = cursorOffsetFromTap(
                                    tap = tap,
                                    chip = chipCoords,
                                    text = textCoords,
                                    layout = textLayout,
                                    fallback = interest.text.length,
                                )
                                editing = true
                            },
                            onLongPress = { menuOpen = true },
                        )
                    },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        interest.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.onGloballyPositioned { textCoords = it },
                        onTextLayout = { textLayout = it },
                    )
                }
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun AddInterestChip(onAdd: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }

    if (editing) {
        ChipEditField(
            initial = "",
            placeholder = "Add interest",
            onCommit = { committed ->
                editing = false
                if (committed.isNotBlank()) onAdd(committed)
            },
        )
    } else {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.clickable { editing = true },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    "+ add",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun ChipEditField(
    initial: String,
    initialCursor: Int = initial.length,
    placeholder: String?,
    onCommit: (String) -> Unit,
) {
    var draft by remember {
        mutableStateOf(
            TextFieldValue(
                text = initial,
                selection = TextRange(initialCursor.coerceIn(0, initial.length)),
            ),
        )
    }
    var hadFocus by remember { mutableStateOf(false) }
    var committed by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val commit = {
        if (!committed) {
            committed = true
            onCommit(draft.text.trim())
        }
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { fs ->
                    if (fs.isFocused) {
                        hadFocus = true
                    } else if (hadFocus) {
                        hadFocus = false
                        commit()
                    }
                },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = LocalContentColor.current),
            singleLine = true,
            cursorBrush = SolidColor(LocalContentColor.current),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            decorationBox = { inner ->
                Box {
                    if (draft.text.isEmpty() && placeholder != null) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalContentColor.current.copy(alpha = 0.6f),
                        )
                    }
                    inner()
                }
            },
        )
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

private fun cursorOffsetFromTap(
    tap: Offset,
    chip: LayoutCoordinates?,
    text: LayoutCoordinates?,
    layout: TextLayoutResult?,
    fallback: Int,
): Int {
    if (chip == null || text == null || layout == null) return fallback
    val textInChip = chip.localPositionOf(text, Offset.Zero)
    val rel = tap - textInChip
    return layout.getOffsetForPosition(rel).coerceIn(0, layout.layoutInput.text.length)
}

@Composable
private fun ScanStatusHint(scanning: Boolean) {
    val transition = rememberInfiniteTransition(label = "scanning")
    val pulse by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    val dotAlpha = if (scanning) pulse else 0.5f
    val dotColor = if (scanning) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = if (scanning) "Scanning nearby…" else "Scanning paused"

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(dotAlpha)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MatchedPeerRow(peer: Peer, onOpen: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(peer.label, fontWeight = FontWeight.Medium)
                Text(
                    "tap to chat",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OtherPeerRow(peer: Peer) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            peer.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}
