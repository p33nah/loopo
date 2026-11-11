package com.p33nah.loopo.ui.voice

import com.p33nah.loopo.domain.model.PlaybackRate
import com.p33nah.loopo.domain.model.PlaybackRate.Companion.MAX_PERCENT
import com.p33nah.loopo.domain.model.PlaybackRate.Companion.MIN_PERCENT
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface VoiceCommand {
	data object Resume : VoiceCommand
	data object StayPaused : VoiceCommand
	data class SetRate(val rate: PlaybackRate) : VoiceCommand
}

internal enum class VoiceStatus {
	PermissionGranted, PermissionDenied, Pausing, Listening, Paused, CommandSent, PauseFailed,
	TimedOut, Unavailable, Unrecognized, Failed, Cancelled,
}

internal sealed interface TranscriptResult {
	data class Text(val value: String) : TranscriptResult
	data object TimedOut : TranscriptResult
	data object Unavailable : TranscriptResult
	data object PermissionDenied : TranscriptResult
	data object Unrecognized : TranscriptResult
	data object Failed : TranscriptResult
	data object Cancelled : TranscriptResult
}

internal class VoiceCommandCoordinator(
	private val pause: suspend () -> Boolean,
	private val transcribe: suspend (onListening: () -> Unit) -> TranscriptResult,
	private val dispatch: (VoiceCommand) -> Boolean,
) {
	suspend fun run(onStatus: (VoiceStatus) -> Unit = {}): VoiceStatus {
		onStatus(VoiceStatus.Pausing)
		if (!pause()) return VoiceStatus.PauseFailed
		currentCoroutineContext().ensureActive()
		val transcript = transcribe { onStatus(VoiceStatus.Listening) }
		currentCoroutineContext().ensureActive()

		return when (transcript) {
			is TranscriptResult.Text -> {
				val command = ItalianVoiceCommandParser.parse(transcript.value) ?: return VoiceStatus.Unrecognized
				if (!dispatch(command)) return VoiceStatus.Cancelled
				if (command == VoiceCommand.StayPaused) VoiceStatus.Paused else VoiceStatus.CommandSent
			}
			TranscriptResult.TimedOut -> VoiceStatus.TimedOut
			TranscriptResult.Unavailable -> VoiceStatus.Unavailable
			TranscriptResult.PermissionDenied -> VoiceStatus.PermissionDenied
			TranscriptResult.Unrecognized -> VoiceStatus.Unrecognized
			TranscriptResult.Failed -> VoiceStatus.Failed
			TranscriptResult.Cancelled -> VoiceStatus.Cancelled
		}
	}
}

internal object ItalianVoiceCommandParser {
	private val ones = listOf("", "uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove")
	private val teens = listOf("dieci", "undici", "dodici", "tredici", "quattordici", "quindici", "sedici", "diciassette", "diciotto", "diciannove")
	private val tens = listOf("", "", "venti", "trenta", "quaranta", "cinquanta", "sessanta", "settanta", "ottanta", "novanta")
	private val ratesByWords = (MIN_PERCENT..MAX_PERCENT).associateBy(::italianNumber)
	private val ratePhrase = Regex("(?:rallenta al|velocità al) (.+?)(?: ?%| per cento)?")
	private val spaces = Regex("\\s+")
	private val trailingPunctuation = Regex("[.!?,;:]+$")

	fun parse(raw: String): VoiceCommand? {
		val phrase = raw.trim().lowercase().replace(trailingPunctuation, "").replace(spaces, " ")
		return when (phrase) {
			"riprendi", "continua", "play" -> VoiceCommand.Resume
			"pausa", "ferma" -> VoiceCommand.StayPaused
			else -> parseRate(phrase)
		}
	}

	private fun parseRate(phrase: String): VoiceCommand.SetRate? {
		val value = ratePhrase.matchEntire(phrase)?.groupValues?.get(1) ?: return null
		val percent = value.toIntOrNull() ?: ratesByWords[value.replace('é', 'e')] ?: return null
		return percent.takeIf { it in MIN_PERCENT..MAX_PERCENT }?.let { VoiceCommand.SetRate(PlaybackRate(it)) }
	}

	private fun italianNumber(value: Int): String = when (value) {
		in 0..9 -> ones[value]
		in 10..19 -> teens[value - 10]
		in 20..99 -> {
			val ten = tens[value / 10]
			val unit = value % 10
			val stem = if (unit == 1 || unit == 8) ten.dropLast(1) else ten
			stem + ones[unit]
		}
		200 -> "duecento"
		else -> {
			val rest = italianNumber(value - 100)
			val stem = if (rest.startsWith("ott")) "cent" else "cento"
			stem + rest
		}
	}
}
