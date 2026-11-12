package com.p33nah.loopo.ui.voice

import com.p33nah.loopo.domain.model.PlaybackRate
import com.p33nah.loopo.domain.model.PlaybackRate.Companion.MAX_PERCENT
import com.p33nah.loopo.domain.model.PlaybackRate.Companion.MIN_PERCENT
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@JvmInline
internal value class RateDeltaPercent private constructor(val value: Int) {
	fun resolve(basePercent: Int): PlaybackRate? =
		(basePercent + value).takeIf { it in MIN_PERCENT..MAX_PERCENT }?.let(::PlaybackRate)

	companion object {
		fun from(value: Int): RateDeltaPercent? = value.takeIf { it != 0 && it in -175..175 }?.let(::RateDeltaPercent)
	}
}

internal sealed interface VoiceAction {
	data object Resume : VoiceAction
	data object StayPaused : VoiceAction
	data class SetRate(val rate: PlaybackRate) : VoiceAction
	data class AdjustRate(val delta: RateDeltaPercent) : VoiceAction
}

internal class VoicePlan private constructor(val actions: List<VoiceAction>) {
	companion object {
		fun validated(actions: List<VoiceAction>): VoicePlan? {
			val valid = actions.size == 1 ||
				(actions.size == MAX_ACTIONS && (actions.firstOrNull() is VoiceAction.SetRate || actions.firstOrNull() is VoiceAction.AdjustRate) && actions.lastOrNull() == VoiceAction.Resume)
			return if (valid) VoicePlan(actions.toList()) else null
		}

		private const val MAX_ACTIONS = 2
	}
}

internal enum class VoiceStatus {
	PermissionDenied, Pausing, Listening, Applying, Paused, CommandSent, PauseFailed,
	TimedOut, Unavailable, WakeListening, WakeUnavailable, WakeFailed, Unrecognized, Failed, Cancelled,
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
	private val dispatch: suspend (VoiceAction) -> Boolean,
	private val isCurrent: () -> Boolean = { true },
	private val onTranscript: (String) -> Unit = {},
) {
	suspend fun run(onStatus: (VoiceStatus) -> Unit = {}): VoiceStatus {
		if (!isCurrent()) return VoiceStatus.Cancelled
		onStatus(VoiceStatus.Pausing)
		if (!pause() || !isCurrent()) return VoiceStatus.PauseFailed
		currentCoroutineContext().ensureActive()
		val transcript = transcribe { onStatus(VoiceStatus.Listening) }
		currentCoroutineContext().ensureActive()
		if (!isCurrent()) return VoiceStatus.Cancelled

		return when (transcript) {
			is TranscriptResult.Text -> {
				onTranscript(transcript.value)
				val plan = ItalianVoiceCommandParser.parse(transcript.value) ?: return VoiceStatus.Unrecognized
				for (action in plan.actions) {
					currentCoroutineContext().ensureActive()
					if (!isCurrent()) return VoiceStatus.Cancelled
					onStatus(VoiceStatus.Applying)
					if (!dispatch(action)) return VoiceStatus.Failed
				}
				if (plan.actions.last() == VoiceAction.StayPaused) VoiceStatus.Paused else VoiceStatus.CommandSent
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
	private val numbersByWords = (0..200).associateBy(::italianNumber)
	private val absoluteRate = Regex("(?:rallenta al|velocità al) (.+?)(?: ?%| per cento)?")
	private val increaseAbsolute = Regex("aumenta a (.+?) la velocità")
	private val relativeRate = Regex("(aumenta|diminuisci) del (.+?)(?: ?%| per cento) la velocità")
	private val spaces = Regex("\\s+")
	private val trailingPunctuation = Regex("[.!?,;:]+$")

	fun parse(raw: String): VoicePlan? {
		val phrase = normalize(raw)
		if (phrase.isEmpty()) return null
		val clauses = phrase.split(" e poi ")
		if (clauses.size > 2 || clauses.any(String::isBlank)) return null
		val actions = clauses.map { parseAction(it) ?: return null }
		return VoicePlan.validated(actions)
	}

	private fun normalize(raw: String) = raw.trim().lowercase()
		.replace('é', 'e').replace(trailingPunctuation, "").replace(spaces, " ")

	private fun parseAction(phrase: String): VoiceAction? = when (phrase) {
		"riprendi", "continua", "play" -> VoiceAction.Resume
		"pausa", "ferma" -> VoiceAction.StayPaused
		else -> parseRate(phrase)
	}

	private fun parseRate(phrase: String): VoiceAction? {
		val relative = relativeRate.matchEntire(phrase)
		if (relative != null) {
			val amount = parseNumber(relative.groupValues[2])?.takeIf { it in 1..175 } ?: return null
			val delta = if (relative.groupValues[1] == "aumenta") amount else -amount
			return RateDeltaPercent.from(delta)?.let(VoiceAction::AdjustRate)
		}
		val percent = (increaseAbsolute.matchEntire(phrase)?.groupValues?.get(1)
			?: absoluteRate.matchEntire(phrase)?.groupValues?.get(1)
			?: return null).let(::parseNumber) ?: return null
		return percent.takeIf { it in MIN_PERCENT..MAX_PERCENT }?.let { VoiceAction.SetRate(PlaybackRate(it)) }
	}

	private fun parseNumber(value: String): Int? = value.toIntOrNull() ?: numbersByWords[value]

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
