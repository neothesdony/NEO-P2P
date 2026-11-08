package com.neop2p.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * The app's only sheet. Rounded top, drag handle, `surfaceContainerLow` body.
 *
 * The Vaul-style backdrop shrink is intentionally not implemented here: it needs to
 * scale the content *behind* the sheet, which lives outside this composable. The
 * scrim still uses the strong scrim token so the modal reads as modal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeoSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    skipPartiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = NeoTheme.colors.scrimStrong,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = NeoTheme.spacing.md, bottom = NeoTheme.spacing.sm)
                    .size(width = 32.dp, height = 4.dp)
                    .clip(NeoPillShape)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}
