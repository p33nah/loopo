package com.p33nah.loopo.ui.voice

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.p33nah.loopo.domain.model.PlaybackRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ceil

@RunWith(AndroidJUnit4::class)
class VoiceIntentComparisonTest {
	private data class Case(
		val category: String,
		val expected: List<VoiceAction>?,
		val transcript: String,
		val prompt: String = transcript,
	)

	private enum class Outcome { CORRECT, MISSED, WRONG_PLAN, FALSE_ACCEPTANCE }

	private data class Result(val case: Case, val plan: VoicePlan?) {
		val outcome: Outcome = when {
			plan?.actions == case.expected -> Outcome.CORRECT
			case.expected == null -> Outcome.FALSE_ACCEPTANCE
			plan == null -> Outcome.MISSED
			else -> Outcome.WRONG_PLAN
		}
	}

	@Test
	fun replaySameTranscriptsThroughAllInterpreters() {
		val assets = InstrumentationRegistry.getInstrumentation().context.assets
		fun readTraining(name: String) = assets.open("voice-intent-ab/$name").bufferedReader().useLines { lines ->
			lines.map { line ->
				val (label, transcript) = line.split('|', limit = 2)
				ItalianIntent.valueOf(label) to transcript
			}.toList()
		}
		val training = readTraining("training.txt")
		val extraTraining = readTraining("training-extra.txt")
		val expandedTraining = training + extraTraining
		val cases = assets.open("voice-intent-ab/evaluation.txt").bufferedReader().use { it.readLines() }
			.filter(String::isNotBlank).map { line ->
				val (category, expected, transcript) = line.split('|', limit = 3)
				Case(category, parseExpected(expected), transcript)
			}
		val trainingText = training.map { canonical(it.second) }.toSet()
		assertTrue("Evaluation cases must be disjoint from training examples",
			cases.none { canonical(it.transcript) in trainingText })
		assertEquals(ItalianIntent.entries.toSet(), training.map { it.first }.toSet())
		assertEquals(setOf("legacy", "paraphrase", "asr-like", "negative"), cases.map { it.category }.toSet())

		val syntheticCases = assets.open("voice-intent-ab/vosk-synthetic.txt").bufferedReader().use { it.readLines() }
			.filter(String::isNotBlank).map { line ->
				val (voice, expected, prompt, transcript) = line.split('|', limit = 4)
				Case("Vosk / $voice", parseExpected(expected), transcript, prompt)
			}
		assertTrue("Synthetic prompts must be disjoint from training examples",
			syntheticCases.isNotEmpty() && syntheticCases.none { canonical(it.prompt) in trainingText })

		val generatedCases = assets.open("voice-intent-ab/generated-evaluation.txt").bufferedReader().useLines { lines ->
			lines.map { line ->
				val (category, expected, prompt, transcript) = line.split('|', limit = 4)
				Case("generated / $category", parseExpected(expected), transcript, prompt)
			}.toList()
		}
		val reserved = trainingText + (cases + syntheticCases).flatMap { listOf(canonical(it.prompt), canonical(it.transcript)) }
		val generatedText = generatedCases.map { canonical(it.transcript) }
		assertEquals(generatedText.size, generatedText.toSet().size)
		assertTrue("Generated transcripts must be disjoint from the original sets",
			generatedCases.none { canonical(it.prompt) in reserved || canonical(it.transcript) in reserved })
		assertEquals(setOf("clean", "paraphrase", "grammar", "asr-like", "typo-1", "typo-2", "negative"),
			generatedCases.map { it.category.substringAfter(" / ") }.toSet())

		val holdout = assets.open("voice-intent-ab/training-holdout.txt").bufferedReader().useLines { lines ->
			lines.map { line ->
				val (category, expected, transcript) = line.split('|', limit = 3)
				Case("holdout / $category", parseExpected(expected), transcript)
			}.toList()
		}
		val originalCases = cases + syntheticCases + generatedCases
		val challenge = assets.open("voice-intent-ab/guarded-holdout.txt").bufferedReader().useLines { lines ->
			lines.map { line ->
				val (category, expected, transcript) = line.split('|', limit = 3)
				Case("challenge / $category", parseExpected(expected), transcript)
			}.toList()
		}
		val allCases = originalCases + holdout + challenge
		val expandedText = expandedTraining.map { canonical(it.second) }.toSet()
		assertEquals("Duplicate training examples", expandedTraining.size, expandedText.size)
		assertTrue("Training must exclude evaluation prompts and transcripts",
			allCases.none { canonical(it.prompt) in expandedText || canonical(it.transcript) in expandedText })
		val previousText = originalCases
			.flatMap { listOf(canonical(it.prompt), canonical(it.transcript)) }.toSet()
		assertEquals(setOf("clean", "paraphrase", "noisy", "negative"),
			holdout.map { it.category.substringAfter(" / ") }.toSet())
		assertEquals("Duplicate holdout examples", holdout.size, holdout.map { canonical(it.transcript) }.toSet().size)
		assertTrue("Holdout must exclude the previous evaluation data", holdout.none { canonical(it.transcript) in previousText })

		val challengeText = challenge.map { canonical(it.transcript) }.toSet()
		val holdoutText = holdout.map { canonical(it.transcript) }.toSet()
		assertEquals("Duplicate challenge examples", challenge.size, challengeText.size)
		assertTrue("Challenge must exclude the previous evaluation data",
			challengeText.none { it in previousText || it in holdoutText })
		val report = StringBuilder("Android ${Build.VERSION.SDK_INT}, ${Build.MODEL}, ${Build.SUPPORTED_ABIS.first()}\n")
		val original = fit("B original", training, report)
		val expanded = fit("C expanded", expandedTraining, report)
		val guarded = GuardedItalianInterpreter(expanded)
		val interpreters = listOf(
			"A parser" to ItalianVoiceCommandParser::parse,
			"B original" to original::interpret,
			"C expanded" to expanded::interpret,
			"D guarded" to guarded::interpret,
		)
		report.appendLine("${cases.size} written transcripts, ${syntheticCases.size} Vosk transcripts, ${generatedCases.size} generated transcripts, ${holdout.size} holdout transcripts, ${challenge.size} challenge transcripts")
		report.appendLine("Each row reports correct, missed, wrong plan, false acceptance.")
		for ((category, categoryCases) in allCases.groupBy { it.category }) {
			report.appendLine("$category (${categoryCases.size})")
			for ((name, interpreter) in interpreters) {
				val results = categoryCases.map { case -> Result(case, interpreter(case.transcript)) }
				val byOutcome = results.groupBy { it.outcome }
				report.appendLine("  $name: ${Outcome.entries.joinToString { byOutcome[it].orEmpty().size.toString() }}")
				for (outcome in listOf(Outcome.WRONG_PLAN, Outcome.FALSE_ACCEPTANCE, Outcome.MISSED)) {
					for ((case, plan) in byOutcome[outcome].orEmpty().take(2)) {
						report.appendLine("    $outcome | prompt=${case.prompt} | transcript=${case.transcript} | expected=${case.expected} | actual=${plan?.actions}")
					}
				}
			}
		}

		var recovered = 0
		for (case in allCases) {
			val baseline = ItalianVoiceCommandParser.parse(case.transcript)?.actions
			val candidate = guarded.interpret(case.transcript)
			if (baseline != null && baseline == case.expected) {
				assertEquals("Lost an existing command: ${case.transcript}", baseline, candidate?.actions)
			}
			assertTrue("Guarded candidate proposed an unintended action: ${case.transcript}",
				candidate == null || candidate.actions == case.expected)
			if (baseline == null && candidate != null) recovered++
		}
		assertTrue("Guarded candidate must recover at least one command", recovered > 0)
		report.appendLine("Guarded candidate recovered $recovered commands rejected by the parser.")
		val timingTranscripts = (cases + syntheticCases).map { it.transcript }
		repeat(WARMUP_BATCHES) {
			for ((_, interpret) in interpreters) checksum(timingTranscripts, interpret)
		}
		val checksums = interpreters.associate { (name, interpret) -> name to checksum(timingTranscripts, interpret) }
		val samples = interpreters.associate { (name, _) -> name to mutableListOf<Double>() }
		repeat(BATCHES) { batch ->
			val offset = batch % interpreters.size
			for ((name, interpret) in interpreters.drop(offset) + interpreters.take(offset)) {
				samples.getValue(name) += measureBatch(timingTranscripts, interpret, checksums.getValue(name))
			}
		}
		report.appendLine("$BATCHES rotated batches per interpreter, $REPEATS passes over ${timingTranscripts.size} original cases per batch")
		for ((name, timings) in samples) report.appendLine(timing(name, timings))
		report.lineSequence().filter(String::isNotBlank).forEach { Log.i("LoopoVoiceAB", it) }
	}

	private fun fit(
		name: String,
		training: List<Pair<ItalianIntent, String>>,
		report: StringBuilder,
	): ItalianIntentClassifier {
		val start = SystemClock.elapsedRealtimeNanos()
		val classifier = ItalianIntentClassifier(training)
		val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
		report.appendLine("%s: %d training examples, %d sparse weights, %.1f ms fit"
			.format(Locale.ROOT, name, training.size, classifier.weightCount, elapsed))
		return classifier
	}

	private fun canonical(text: String): String = Normalizer.normalize(text.lowercase(Locale.ITALIAN), Normalizer.Form.NFD)
		.replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}%]+"), " ").trim()

	private fun parseExpected(text: String): List<VoiceAction>? {
		if (text == "reject") return null
		val actions = text.split(',').map { action ->
			when (action.substringBefore(':')) {
				"resume" -> VoiceAction.Resume
				"pause" -> VoiceAction.StayPaused
				"set" -> VoiceAction.SetRate(PlaybackRate(action.substringAfter(':').toInt()))
				"delta" -> VoiceAction.AdjustRate(checkNotNull(RateDeltaPercent.from(action.substringAfter(':').toInt())))
				else -> error("Unknown expected action $action")
			}
		}
		return checkNotNull(VoicePlan.validated(actions)) { "Invalid expected plan: $text" }.actions
	}

	private fun checksum(transcripts: List<String>, interpret: (String) -> VoicePlan?): Int =
		transcripts.fold(0) { hash, transcript -> 31 * hash + (interpret(transcript)?.actions?.hashCode() ?: 0) }

	private fun measureBatch(transcripts: List<String>, interpret: (String) -> VoicePlan?, expected: Int): Double {
		val start = SystemClock.elapsedRealtimeNanos()
		var observed = 0
		repeat(REPEATS) { observed += checksum(transcripts, interpret) }
		val duration = SystemClock.elapsedRealtimeNanos() - start
		assertEquals(expected * REPEATS, observed)
		return duration.toDouble() / (REPEATS * transcripts.size * 1_000)
	}

	private fun timing(name: String, samples: List<Double>): String {
		val sorted = samples.sorted()
		val median = sorted[sorted.size / 2]
		val p95 = sorted[ceil(sorted.size * 0.95).toInt() - 1]
		return "%s: median %.1f, p95 %.1f, range %.1f–%.1f µs/transcript (batch averages)"
			.format(Locale.ROOT, name, median, p95, sorted.first(), sorted.last())
	}

	private companion object {
		const val WARMUP_BATCHES = 12
		const val BATCHES = 15
		const val REPEATS = 12
	}
}
