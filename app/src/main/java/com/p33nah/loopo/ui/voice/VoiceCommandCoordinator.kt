package com.p33nah.loopo.ui.voice

import com.p33nah.loopo.domain.model.PlaybackRate
import com.p33nah.loopo.domain.model.PlaybackRate.Companion.MAX_PERCENT
import com.p33nah.loopo.domain.model.PlaybackRate.Companion.MIN_PERCENT
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

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
	private val spaces = Regex("\\s+")
	private val punctuation = Regex("[^\\p{L}\\p{N}%]+")
	private val signedNumber = Regex("[-+](?=\\d)")
	private val chain = Regex("\\be poi\\b")
	private val increaseWords = setOf("aumenta", "accelera", "alza", "incrementa")
	private val decreaseWords = setOf("diminuisci", "rallenta", "riduci")
	private val setWords = setOf("imposta", "porta", "metti", "regola")
	private val targetWords = setOf("a", "al", "alla", "su", "sul", "sulla", "sullo", "sull")
	private val relativeWords = setOf("di", "del")
	private val rateWords = setOf("velocita", "ritmo")
	private val playbackWords = setOf("riprendi", "continua", "play", "pausa", "ferma")
	private val resumePhrases = setOf(
		listOf("riprendi"), listOf("continua"), listOf("play"), listOf("riparti"), listOf("avvia"),
		listOf("avvia", "la", "riproduzione"), listOf("riavvia", "la", "riproduzione"),
	)
	private val pausePhrases = setOf(
		listOf("pausa"), listOf("ferma"), listOf("interrompi"), listOf("metti", "in", "pausa"),
		listOf("ferma", "la", "riproduzione"), listOf("interrompi", "la", "riproduzione"),
	)
	private val articles = setOf("il", "lo", "la")
	private val rateUnits = setOf("%", "percento", "per", "cento", "punto", "punti", "percentuale", "percentuali")
	private val commandWords = increaseWords + decreaseWords + setWords + targetWords + relativeWords + rateWords +
		articles + rateUnits + setOf("non", "e", "poi") + playbackWords

	fun parse(raw: String): VoicePlan? {
		if (signedNumber.containsMatchIn(raw)) return null
		val phrase = normalize(raw)
		if (phrase.isEmpty()) return null
		val clauses = chain.split(phrase)
		if (clauses.size > 2 || clauses.any(String::isBlank)) return null
		val actions = clauses.map { parseAction(it.trim().split(' ')) ?: return null }
		return VoicePlan.validated(actions)
	}

	private fun normalize(raw: String): String = raw.trim().lowercase(Locale.ITALIAN)
		.replace('é', 'e').replace('à', 'a')
		.replace(Regex("all['’]"), "alla ")
		.replace(Regex("[’']"), " ")
		.replace("%", " % ")
		.replace(punctuation, " ")
		.replace(spaces, " ").trim()

	private fun parseAction(tokens: List<String>): VoiceAction? = when (tokens) {
		in resumePhrases -> VoiceAction.Resume
		in pausePhrases -> VoiceAction.StayPaused
		else -> parseRate(tokens)
	}

	private fun parseRate(tokens: List<String>): VoiceAction? {
		if (tokens.isEmpty() || tokens.any { it == "non" || it == "e" || it == "poi" || it in playbackWords }) return null
		val verbs = tokens.filter { it in increaseWords || it in decreaseWords || it in setWords }
		if (verbs.size > 1) return null
		val rateNounCount = tokens.count { it in rateWords }
		if (rateNounCount > 1) return null
		val numberTokens = tokens.mapIndexedNotNull { index, token ->
			if (token == "cento" && tokens.getOrNull(index - 1) == "per") null else parseNumber(token)?.let { index to it }
		}
		if (numberTokens.size != 1) return null
		val (numberIndex, amount) = numberTokens.single()
		val other = tokens.filterIndexed { index, token -> index != numberIndex && parseNumber(token) == null }
		if (other.any { it !in commandWords }) return null
		val unitStart = numberIndex + 1
		val unitLength = when {
			tokens.getOrNull(unitStart) == "%" || tokens.getOrNull(unitStart) == "percento" -> 1
			tokens.drop(unitStart).take(2) == listOf("per", "cento") -> 2
			tokens.drop(unitStart).take(2) in listOf(listOf("punto", "percentuale"), listOf("punti", "percentuali")) -> 2
			else -> 0
		}
		val hasPercent = unitLength > 0
		if (tokens.indices.any { index ->
				index != numberIndex && index !in unitStart until unitStart + unitLength &&
					tokens[index] in rateUnits
			}) return null

		val targetCount = tokens.count { it in targetWords }
		val relativeCount = tokens.count { it in relativeWords }
		if (targetCount + relativeCount != 1) return null
		if (other.count { it in targetWords || it in relativeWords } != 1) return null
		val hasRateNoun = rateNounCount == 1
		val verb = verbs.singleOrNull()
		val articleIndexes = tokens.withIndex().filter { it.value in articles }.map { it.index }
		val nounIndex = tokens.indexOfFirst { it in rateWords }
		if (articleIndexes.size > 1 || (articleIndexes.singleOrNull() != null && articleIndexes.single() + 1 != nounIndex)) return null

		if (targetCount == 1) {
			if (tokens.getOrNull(numberIndex - 1) !in targetWords) return null
			// Legacy "rallenta al settanta" is retained; other target commands name the rate.
			if (!hasRateNoun && verb !in decreaseWords) return null
			if (verb == null && !hasRateNoun) return null
			if (amount !in MIN_PERCENT..MAX_PERCENT) return null
			return VoiceAction.SetRate(PlaybackRate(amount))
		}

		if (verb !in increaseWords && verb !in decreaseWords) return null
		if (tokens.getOrNull(numberIndex - 1) !in relativeWords) return null
		if (!hasRateNoun || !hasPercent) return null
		if (amount !in 1..175) return null
		val delta = if (verb in increaseWords) amount else -amount
		return RateDeltaPercent.from(delta)?.let(VoiceAction::AdjustRate)
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
