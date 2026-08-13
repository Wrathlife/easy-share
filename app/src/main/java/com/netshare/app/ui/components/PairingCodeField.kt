package com.netshare.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.netshare.app.webrtc.PairingCode

/**
 * Share-code field that stores wire form (no hyphen) and shows ABCDF-23457.
 * Backspace / mid-edit corrections work; IME caps mode is avoided.
 */
@Composable
fun PairingCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {}
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(PairingCode.sanitizeTyping(it)) },
        modifier = modifier.fillMaxWidth(),
        label = { Text("Share code") },
        placeholder = { Text("ABCDF-23457") },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(
            fontFamily = FontFamily.Monospace
        ),
        visualTransformation = PairingCodeHyphenTransformation,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear share code")
                }
            }
        }
    )
}

/** Inserts a hyphen after the letter block without affecting the stored value. */
private object PairingCodeHyphenTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.length < PairingCode.LETTER_COUNT) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val out = buildString(raw.length + 1) {
            append(raw, 0, PairingCode.LETTER_COUNT)
            append('-')
            append(raw, PairingCode.LETTER_COUNT, raw.length)
        }
        val letters = PairingCode.LETTER_COUNT
        val originalLen = raw.length
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val o = offset.coerceIn(0, originalLen)
                return if (o < letters) o else o + 1
            }

            override fun transformedToOriginal(offset: Int): Int {
                val t = offset.coerceIn(0, out.length)
                return when {
                    t <= letters -> t.coerceAtMost(originalLen)
                    else -> (t - 1).coerceIn(0, originalLen)
                }
            }
        }
        return TransformedText(AnnotatedString(out), mapping)
    }
}
