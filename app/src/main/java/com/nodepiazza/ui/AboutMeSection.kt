package com.nodepiazza.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp

@Composable
internal fun AboutMeSection(value: String, onChange: (String) -> Unit) {
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
