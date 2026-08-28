package com.neop2p.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Wizard progress dots (onboarding). Current step = filled primary dot;
 * past steps = primaryContainer; future = surfaceVariant.
 * [current] is 0-based.
 */
@Composable
fun OnboardingStepIndicator(
    total: Int,
    current: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(total) { index ->
            val color = when {
                index < current -> MaterialTheme.colorScheme.primaryContainer
                index == current -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}
