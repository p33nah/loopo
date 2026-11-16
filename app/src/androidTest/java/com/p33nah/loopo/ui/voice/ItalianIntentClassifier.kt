package com.p33nah.loopo.ui.voice

import com.p33nah.loopo.domain.model.PlaybackRate
import java.text.Normalizer
import java.util.Locale
import kotlin.math.sqrt

internal enum class ItalianIntent {
	RESUME, PAUSE, SET_RATE, INCREASE_RATE, DECREASE_RATE, REJECT,
}

internal class ItalianIntentClassifier(training: List<Pair<ItalianIntent, String>>) {
	private val weights = training.groupBy({ it.first }, { it.second }).mapValues { (_, examples) ->
		val centroid = mutableMapOf<String, Double>()
		for (example in examples) {
			val tokens = words(example)
			val features = features(maskAmount(tokens, findAmounts(tokens).singleOrNull()))
			val weight = 1.0 / sqrt(features.size.toDouble())
			for (feature in features) centroid.merge(feature, weight, Double::plus)
		}
		val norm = sqrt(centroid.values.sumOf { it * it })
		centroid.mapValues { (_, weight) -> weight / norm }
	}
	val weightCount: Int get() = weights.values.sumOf { it.size }

	fun interpret(transcript: String): VoicePlan? {
		if (signedOrDecimal.containsMatchIn(transcript)) return null
		val normalized = normalize(transcript)
		val clauses = chain.split(normalized)
		if (clauses.size > 2 || clauses.any(String::isBlank)) return null
		val actions = clauses.map { interpretClause(it.split(' ')) ?: return null }
		return VoicePlan.validated(actions)
	}

	private fun interpretClause(tokens: List<String>): VoiceAction? {
		if (tokens.any { it in blockers }) return null
		val amounts = findAmounts(tokens)
		if (amounts.size > 1) return null
		val amount = amounts.singleOrNull()
		val scores = scores(maskAmount(tokens, amount))
		val (intent, score) = scores.first()
		val competing = scores[1].second
		if (score < MIN_SIMILARITY || score - competing < MIN_MARGIN) return null
		return action(intent, amount)
	}

	internal fun interpretConstrained(intent: ItalianIntent, words: List<String>, amount: Amount?): VoiceAction? {
		val features = if (amount == null) words else words + amount.kind.feature
		val scores = scores(features).toMap()
		val score = scores.getValue(intent)
		if (score < MIN_SIMILARITY || score <= scores.getValue(ItalianIntent.REJECT)) return null
		return action(intent, amount)
	}

	private fun scores(tokens: List<String>): List<Pair<ItalianIntent, Double>> {
		val features = features(tokens)
		val norm = sqrt(features.size.toDouble())
		return weights.map { (intent, centroid) ->
			intent to features.sumOf { centroid[it] ?: 0.0 } / norm
		}.sortedByDescending { it.second }
	}

	private fun action(intent: ItalianIntent, amount: Amount?): VoiceAction? = when (intent) {
		ItalianIntent.RESUME -> if (amount == null) VoiceAction.Resume else null
		ItalianIntent.PAUSE -> if (amount == null) VoiceAction.StayPaused else null
		ItalianIntent.SET_RATE -> amount?.takeIf { it.kind == AmountKind.TARGET }
			?.value?.takeIf { it in PlaybackRate.MIN_PERCENT..PlaybackRate.MAX_PERCENT }
			?.let { VoiceAction.SetRate(PlaybackRate(it)) }
		ItalianIntent.INCREASE_RATE -> relativeAction(amount, 1)
		ItalianIntent.DECREASE_RATE -> relativeAction(amount, -1)
		ItalianIntent.REJECT -> null
	}

	private fun relativeAction(amount: Amount?, sign: Int): VoiceAction? =
		amount?.takeIf { it.kind == AmountKind.DELTA && it.hasUnit && it.value in 1..175 }
			?.let { RateDeltaPercent.from(it.value * sign) }
			?.let(VoiceAction::AdjustRate)

	internal enum class AmountKind(val feature: String) {
		TARGET("@target"), DELTA("@delta"), UNSPECIFIED("@amount"),
	}

	internal data class Amount(
		val value: Int,
		val start: Int,
		val end: Int,
		val kind: AmountKind,
		val hasUnit: Boolean,
	)

	internal companion object {
		const val MIN_SIMILARITY = 0.22
		const val MIN_MARGIN = 0.03
		val signedOrDecimal = Regex("[-+−]\\s*\\d|\\d[.,]\\d")
		val chain = Regex(" (?:e )?(?:poi|quindi|dopodiche) ")
		val nonWord = Regex("[^\\p{L}\\p{N}%]+")
		val accent = Regex("\\p{M}+")
		val blockers = setOf(
			"non", "mai", "no", "senza", "forse", "oppure", "o", "se", "quando", "dopo",
			"minuti", "minuto", "secondi", "secondo", "virgola", "meno", "piu", "tra", "fra",
		)
		val courtesyWords = setOf("puoi", "potresti", "vorrei", "favore", "grazie", "gentilmente", "cortesemente")
		val target = setOf("a", "al", "alla", "all", "su", "sul", "sulla")
		val delta = setOf("di", "del")
		val ones = listOf("zero", "uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove")
		val teens = listOf("dieci", "undici", "dodici", "tredici", "quattordici", "quindici", "sedici", "diciassette", "diciotto", "diciannove")
		val tens = listOf("", "", "venti", "trenta", "quaranta", "cinquanta", "sessanta", "settanta", "ottanta", "novanta")
		val numbers = (0..200).associateBy { italianNumber(it) }

		fun italianNumber(value: Int): String = when (value) {
			in 0..9 -> ones[value]
			in 10..19 -> teens[value - 10]
			in 20..99 -> {
				val unit = value % 10
				val decade = tens[value / 10]
				if (unit == 0) decade else (if (unit == 1 || unit == 8) decade.dropLast(1) else decade) + ones[unit]
			}
			100 -> "cento"
			200 -> "duecento"
			else -> italianNumber(value - 100).let { (if (it.startsWith("ott")) "cent" else "cento") + it }
		}

		fun normalize(text: String): String =
			accent.replace(Normalizer.normalize(text.lowercase(Locale.ITALIAN), Normalizer.Form.NFD), "")
				.replace("%", " % ").replace(nonWord, " ").trim()

		fun words(text: String): List<String> = normalize(text).split(' ')

		fun findAmounts(tokens: List<String>, splitHundreds: Boolean = false): List<Amount> {
			val amounts = mutableListOf<Amount>()
			var index = 0
			while (index < tokens.size) {
				val token = tokens[index]
				val next = tokens.getOrNull(index + 1)
				val unitAfterDecade = next != null && next in ones && next != "zero"
				val hundreds = if (splitHundreds && token == "cento") numbers[next]?.takeIf { it in 1..99 } else null
				val joined = when {
					token in tens && token.isNotEmpty() && unitAfterDecade ->
						numbers.getValue(token) + ones.indexOf(next)
					hundreds != null -> 100 + hundreds
					else -> null
				}
				val tail = tokens.getOrNull(index + 2)
				val splitHundredUnit = if (hundreds != null && next in tens && tail in ones && tail != "zero") ones.indexOf(tail) else 0
				val value = joined?.plus(splitHundredUnit) ?: numbers[token] ?: token.toIntOrNull()
				if (value == null || (token == "cento" && tokens.getOrNull(index - 1) == "per")) {
					index++
					continue
				}
				val numberEnd = index + if (joined == null) 1 else if (splitHundredUnit == 0) 2 else 3
				val unitEnd = when {
					tokens.getOrNull(numberEnd) in setOf("%", "percento") -> numberEnd + 1
					tokens.getOrNull(numberEnd) == "per" && tokens.getOrNull(numberEnd + 1) == "cento" -> numberEnd + 2
					tokens.getOrNull(numberEnd) in setOf("punto", "punti") &&
						tokens.getOrNull(numberEnd + 1) in setOf("percentuale", "percentuali") -> numberEnd + 2
					else -> numberEnd
				}
				val marker = tokens.getOrNull(index - 1)
				val kind = when {
					marker in target -> AmountKind.TARGET
					marker in delta -> AmountKind.DELTA
					else -> AmountKind.UNSPECIFIED
				}
				amounts += Amount(value, index, unitEnd, kind, unitEnd != numberEnd)
				index = unitEnd
			}
			return amounts
		}

		fun maskAmount(tokens: List<String>, amount: Amount?): List<String> =
			if (amount == null) tokens else tokens.take(amount.start) + amount.kind.feature + tokens.drop(amount.end)

		fun features(tokens: List<String>): Set<String> = buildSet {
			for (token in tokens) {
				if (token in courtesyWords) continue
				add(token)
				if (!token.startsWith('@')) addAll("^$token$".windowed(3))
			}
		}
	}
}
