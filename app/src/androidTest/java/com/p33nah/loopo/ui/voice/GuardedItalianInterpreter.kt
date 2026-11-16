package com.p33nah.loopo.ui.voice

internal class GuardedItalianInterpreter(private val classifier: ItalianIntentClassifier) {
	fun interpret(transcript: String): VoicePlan? {
		if (ItalianIntentClassifier.signedOrDecimal.containsMatchIn(transcript) ||
			transcript.any { it in quotationMarks } || singleQuoted.containsMatchIn(transcript)) return null
		ItalianVoiceCommandParser.parse(transcript)?.let { return it }

		val normalized = playbackQualifier.replace(ItalianIntentClassifier.normalize(transcript)) { it.groupValues[1] }
		val clauses = sequenceSeparator.split(normalized)
		if (clauses.size > 2) return null
		val actions = clauses.map { interpretClause(it) ?: return null }
		return VoicePlan.validated(actions)
	}

	private fun interpretClause(clause: String): VoiceAction? {
		val hasRequestPrefix = politePrefix.containsMatchIn(clause)
		val request = politePrefix.replaceFirst(politeSuffix.replaceFirst(clause, ""), "").trim()
		if (request.isEmpty()) return null
		val tokens = pauseRequest.replaceFirst(request, "pausa").split(' ')
		val amounts = ItalianIntentClassifier.findAmounts(tokens, splitHundreds = true)
		if (amounts.size > 1) return null
		val amount = amounts.singleOrNull()
		val remainder = if (amount == null) tokens else tokens.take(amount.start) + tokens.drop(amount.end)
		val words = remainder.mapIndexed { index, word ->
			vocabulary[word] ?: (if (index == 0) correctCommandWord(word) else null) ?: return null
		}
		val start = words.firstOrNull() ?: return null
		if (start !in requestStarts || (amount == null && start !in playbackStarts)) return null
		val anchors = words.filter { it in commandWords }
		val hasRate = words.any { it in rateWords }
		val hasPlayback = words.any { it in playbackWords }
		if (anchors.size > 1 && anchors != listOf("fai", "riprendi")) return null
		val anchor = anchors.firstOrNull()
		val intent = when {
			amount == null && anchor == "pausa" -> ItalianIntent.PAUSE
			amount == null && anchors == listOf("fai", "riprendi") -> ItalianIntent.RESUME
			amount == null && anchor == "riprendi" -> ItalianIntent.RESUME
			amount == null -> return null
			amount.kind == ItalianIntentClassifier.AmountKind.DELTA && amount.hasUnit && hasRate -> when (anchor) {
				"aumenta" -> ItalianIntent.INCREASE_RATE
				"riduci" -> ItalianIntent.DECREASE_RATE
				else -> return null
			}
			amount.kind == ItalianIntentClassifier.AmountKind.TARGET &&
				(
					(anchor in targetAnchors && (hasRate || hasPlayback || anchor == "riduci")) ||
					(anchor == null && (start in rateWords || (hasRequestPrefix && hasRate)))
				) -> ItalianIntent.SET_RATE
			amount.kind == ItalianIntentClassifier.AmountKind.UNSPECIFIED && amount.hasUnit && hasRate &&
				(anchor == "imposta" || (anchor == null && start in rateWords)) -> ItalianIntent.SET_RATE
			else -> return null
		}
		if (amount == null && (hasRate || words.any { it !in playbackVocabulary })) return null
		if (amount != null) {
			val markerCount = if (amount.kind == ItalianIntentClassifier.AmountKind.UNSPECIFIED) 0 else 1
			if (remainder.count { it in rateMarkers } != markerCount) return null
		}
		val groundedAmount = if (amount?.kind == ItalianIntentClassifier.AmountKind.UNSPECIFIED) {
			amount.copy(kind = ItalianIntentClassifier.AmountKind.TARGET)
		} else amount
		return classifier.interpretConstrained(intent, words, groundedAmount)
	}

	private fun correctCommandWord(word: String): String? {
		if (word.length < 4) return null
		return correctionWords.asSequence().filter { oneEditApart(word, it) }
			.map(vocabulary::getValue).distinct().take(2).toList().singleOrNull()
	}

	private fun oneEditApart(a: String, b: String): Boolean {
		val first = a.indices.firstOrNull { it >= b.length || a[it] != b[it] } ?: minOf(a.length, b.length)
		if (a.length == b.length) {
			return a.drop(first + 1) == b.drop(first + 1) ||
				(first + 1 < a.length && a[first] == b[first + 1] && a[first + 1] == b[first] &&
					a.drop(first + 2) == b.drop(first + 2))
		}
		val longer = if (a.length > b.length) a else b
		val shorter = if (a.length > b.length) b else a
		return longer.length - shorter.length == 1 && longer.removeRange(first, first + 1) == shorter
	}

	private companion object {
		const val quotationMarks = "\"«»“”"
		val singleQuoted = Regex("(?:^|\\s)['\u2018][^\\r\\n]+['\u2019](?:$|\\s|[.!?])")
		val sequenceSeparator = Regex(" (?:e )?(?:poi|quindi|dopodiche) | e ")
		val playbackQualifier = Regex("\\b(velocita|ritmo) (?:del (?:brano|pezzo|loop)|della traccia|di riproduzione)\\b")
		val politePrefix = Regex("^(?:per questa prova )?(?:mi serve |mi |puoi |potresti |vorrei )")
		val politeSuffix = Regex(" (?:per favore|per piacere|per cortesia|gentilmente|cortesemente|grazie|eh)$")
		val pauseRequest = Regex("^(?:(?:metti|mettere) (?:il (?:brano|pezzo|loop) |la traccia )?in pausa(?:a)?|lascia (?:il (?:brano|pezzo|loop)|la traccia) in pausa)\\b")
		val rateWords = setOf("velocita", "ritmo", "andatura", "rapidita")
		val playbackWords = setOf("brano", "pezzo", "traccia", "riproduzione", "musica", "audio", "loop", "ascolto")
		val rateMarkers = setOf("a", "al", "alla", "all", "su", "sul", "sulla", "di", "del")
		val commandWords = setOf("riprendi", "pausa", "imposta", "aumenta", "riduci", "fai")
		val targetAnchors = setOf("imposta", "fai", "aumenta", "riduci")
		val aliases = mapOf(
			"riprendere" to "riprendi", "riprende" to "riprendi", "riparti" to "riprendi",
			"ripartire" to "riprendi", "continua" to "riprendi", "continuare" to "riprendi",
			"riattiva" to "riprendi", "riavvialo" to "riprendi", "prosegui" to "riprendi", "avvia" to "riprendi", "parti" to "riprendi",
			"partire" to "riprendi", "riavvia" to "riprendi", "ricomincia" to "riprendi",
			"ricominciare" to "riprendi", "play" to "riprendi", "riproduci" to "riprendi",
			"ferma" to "pausa", "fermi" to "pausa", "fermare" to "pausa", "sospendi" to "pausa",
			"sospendere" to "pausa", "interrompi" to "pausa", "blocca" to "pausa",
			"congela" to "pausa", "arresta" to "pausa",
			"riporta" to "imposta", "regola" to "imposta", "porta" to "imposta", "portare" to "imposta",
			"metti" to "imposta", "mettere" to "imposta", "fissa" to "imposta",
			"setta" to "imposta", "impostare" to "imposta",
			"alza" to "aumenta", "alzi" to "aumenta", "incrementa" to "aumenta",
			"incrementare" to "aumenta", "accelera" to "aumenta", "aumentare" to "aumenta",
			"sali" to "aumenta", "aggiungi" to "aumenta",
			"abbassa" to "riduci", "abbassi" to "riduci", "diminuisci" to "riduci",
			"rallenta" to "riduci", "rallentare" to "riduci", "ridurre" to "riduci",
			"scendi" to "riduci", "togli" to "riduci", "far" to "fai", "fa" to "fai", "fallo" to "fai",
		)
		val vocabulary = (rateWords + playbackWords + rateMarkers + commandWords + setOf(
			"il", "lo", "la", "le", "l", "un", "in", "con", "qui", "subito", "ora", "adesso",
			"attimo", "momento", "questa", "prova", "ascoltare", "andare", "suonare", "fermo",
			"da", "di", "nuovo", "per", "rimettere", "rimetti", "tornare",
		)).associateWith { it } + aliases
		val playbackStarts = setOf("riprendi", "pausa", "fai")
		val playbackVocabulary = playbackStarts + playbackWords + setOf(
			"il", "lo", "la", "l", "un", "in", "con", "qui", "subito", "ora", "adesso", "attimo", "momento", "suonare",
		)
		val correctionWords = setOf(
			"imposta", "porta", "regola", "aumenta", "incrementa", "riduci", "diminuisci",
			"rallenta", "ferma", "sospendi", "riprendi", "riparti", "avvia", "pausa",
		)
		val requestStarts = commandWords + rateWords + rateMarkers + setOf("il", "la", "ascoltare", "rimettere", "rimetti", "tornare")
	}
}
