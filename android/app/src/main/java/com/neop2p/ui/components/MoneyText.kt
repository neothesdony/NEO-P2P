package com.neop2p.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.neop2p.ui.util.formatBtc

/**
 * Money-styled text: monospace + tabular numerals so digits never jump
 * as amounts change (Bithumb/Muun money-grade pattern). Use for any BTC
 * or fiat amount, txid, or dense numeric column.
 */
@Composable
fun MoneyText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = style.copy(
            fontFamily = FontFamily.Monospace,
            fontFeatureSettings = "tnum"
        ),
        color = color,
        modifier = modifier
    )
}

/**
 * Renders a satoshi amount through the shared adaptive formatter
 * (ui/util/BtcFormat.kt — 4/6/8-decimal ladder, never sci-notation).
 */
@Composable
fun BtcAmountText(
    sats: Long,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    MoneyText(text = formatBtc(sats), style = style, color = color, modifier = modifier)
}
