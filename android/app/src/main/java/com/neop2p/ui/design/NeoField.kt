package com.neop2p.ui.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation

/**
 * The one text input. Floating label, hairline outline that turns `error` only when
 * the field is actually invalid — never pre-red on an untouched form.
 */
@Composable
fun NeoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    textStyle: TextStyle? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, style = MaterialTheme.typography.labelLarge) },
            isError = error != null,
            singleLine = singleLine,
            enabled = enabled,
            textStyle = textStyle ?: MaterialTheme.typography.bodyLarge,
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = NeoShapes.small,
            trailingIcon = trailing,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = NeoTheme.colors.hairline,
                errorBorderColor = MaterialTheme.colorScheme.error,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                cursorColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = NeoTheme.spacing.md, top = NeoTheme.spacing.xs)
            )
        }
    }
}

/**
 * A numeric field that renders in the mono numeric role so an amount the user is
 * about to send never shifts glyph width as it is typed.
 */
@Composable
fun NeoMoneyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    suffix: String? = null,
    enabled: Boolean = true
) {
    val trailingSlot: (@Composable () -> Unit)? = if (suffix != null) {
        { Text(suffix, style = MaterialTheme.typography.labelMedium) }
    } else {
        null
    }
    NeoTextField(
        value = value,
        onValueChange = { raw -> onValueChange(raw.filter { it.isDigit() || it == '.' || it == ',' }) },
        label = label,
        modifier = modifier,
        error = error,
        singleLine = true,
        keyboardType = KeyboardType.Decimal,
        enabled = enabled,
        textStyle = NeoTheme.type.moneyLarge,
        trailing = trailingSlot
    )
}

/** Search input for the offer feed and chat history. */
@Composable
fun NeoSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = NeoShapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = NeoTheme.colors.hairline,
            cursorColor = MaterialTheme.colorScheme.primary
        ),
        modifier = modifier.fillMaxWidth()
    )
}
