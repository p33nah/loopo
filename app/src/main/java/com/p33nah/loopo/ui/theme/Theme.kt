package com.p33nah.loopo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val LoopoColors = darkColorScheme(
	primary = Accent,
	background = Background,
	surface = Card,
	onPrimary = Background,
	onBackground = TextPrimary,
	onSurface = TextPrimary,
)

@Composable
fun LoopoTheme(content: @Composable () -> Unit) {
	MaterialTheme(colorScheme = LoopoColors, content = content)
}
