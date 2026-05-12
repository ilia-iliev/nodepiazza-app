package com.nodepiazza.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nodepiazza.Interest

@Composable
internal fun InterestsHeader(percent: Int, overflow: Boolean) {
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
internal fun OverflowBanner() {
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
internal fun InterestChips(
    interests: List<Interest>,
    fits: List<Boolean>,
    onUpdateText: (String, String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    PackedFlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalSpacing = 8.dp,
        verticalSpacing = 8.dp,
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

// First-fit packing with look-ahead: each line starts with the next remaining
// chip, then any subsequent chip that still fits is pulled up onto that line.
// Order within a line is preserved; chips can only move earlier, never later.
@Composable
private fun PackedFlowRow(
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 0.dp,
    verticalSpacing: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val hPx = horizontalSpacing.roundToPx()
        val vPx = verticalSpacing.roundToPx()
        val maxWidth = constraints.maxWidth
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }

        class Line(val items: MutableList<Placeable> = mutableListOf(), var width: Int = 0, var height: Int = 0)

        val remaining = placeables.toMutableList()
        val lines = mutableListOf<Line>()
        while (remaining.isNotEmpty()) {
            val first = remaining.removeAt(0)
            val line = Line(mutableListOf(first), first.width, first.height)
            val iter = remaining.listIterator()
            while (iter.hasNext()) {
                val p = iter.next()
                val needed = line.width + hPx + p.width
                if (needed <= maxWidth) {
                    line.items.add(p)
                    line.width = needed
                    if (p.height > line.height) line.height = p.height
                    iter.remove()
                }
            }
            lines.add(line)
        }

        val gaps = (lines.size - 1).coerceAtLeast(0) * vPx
        val height = lines.sumOf { it.height } + gaps
        val width = if (maxWidth == Int.MAX_VALUE) lines.maxOfOrNull { it.width } ?: 0 else maxWidth

        layout(width, height) {
            var y = 0
            for (line in lines) {
                var x = 0
                for (p in line.items) {
                    p.placeRelative(x, y + (line.height - p.height) / 2)
                    x += p.width + hPx
                }
                y += line.height + vPx
            }
        }
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
