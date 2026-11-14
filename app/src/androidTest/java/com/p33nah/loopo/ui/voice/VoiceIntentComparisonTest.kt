package com.p33nah.loopo.ui.voice

import android.util.Log
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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

	@Test
	fun replaySameTranscriptsThroughBothInterpreters() {
		val assets = InstrumentationRegistry.getInstrumentation().context.assets
		val training = assets.open("voice-intent-ab/training.txt").bufferedReader().use { it.readLines() }
			.filter(String::isNotBlank).map { line ->
				val (label, transcript) = line.split('|', limit = 2)
				ItalianIntent.valueOf(label) to transcript
			}
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

		val fitStarted = SystemClock.elapsedRealtimeNanos()
		val classifier = ItalianIntentClassifier(training)
		val fitMillis = (SystemClock.elapsedRealtimeNanos() - fitStarted) / 1_000_000.0
		val parser = ItalianVoiceCommandParser::parse
		val candidate = classifier::interpret
		val report = StringBuilder("Android ${Build.VERSION.SDK_INT}, ${Build.MODEL}, ${Build.SUPPORTED_ABIS.first()}\n")
		report.appendLine("${training.size} training examples, ${classifier.weightCount} sparse weights")
		report.appendLine("Classifier fit: %.1f ms (one cold construction)".format(Locale.ROOT, fitMillis))
		report.appendLine("${cases.size} written transcripts, ${syntheticCases.size} Vosk transcripts")
		report.appendLine("Each row reports correct, missed, wrong plan, false acceptance.")
		for ((category, categoryCases) in (cases + syntheticCases).groupBy { it.category }) {
			report.appendLine("$category (${categoryCases.size})")
			for ((name, interpreter) in listOf("A parser" to parser, "B classifier" to candidate)) {
				val outcomes = categoryCases.groupingBy { case -> outcome(case, interpreter(case.transcript)) }.eachCount()
				report.appendLine("  $name: ${Outcome.entries.joinToString { (outcomes[it] ?: 0).toString() }}")
			}
		}
		for (case in cases + syntheticCases) {
			val a = parser(case.transcript)?.actions
			val b = candidate(case.transcript)?.actions
			if (a != case.expected || b != case.expected) {
				report.appendLine("${case.category} | prompt=${case.prompt} | transcript=${case.transcript} | expected=${case.expected} | A=$a | B=$b")
			}
		}

		val transcripts = (cases + syntheticCases).map { it.transcript }
		repeat(WARMUP_BATCHES) {
			checksum(transcripts, parser)
			checksum(transcripts, candidate)
		}
		val expectedA = checksum(transcripts, parser)
		val expectedB = checksum(transcripts, candidate)
		val samplesA = mutableListOf<Double>()
		val samplesB = mutableListOf<Double>()
		repeat(BATCHES) { batch ->
			if (batch % 2 == 0) {
				samplesA += measureBatch(transcripts, parser, expectedA)
				samplesB += measureBatch(transcripts, candidate, expectedB)
			} else {
				samplesB += measureBatch(transcripts, candidate, expectedB)
				samplesA += measureBatch(transcripts, parser, expectedA)
			}
		}
		report.appendLine("$BATCHES alternating batches per interpreter, $REPEATS corpus passes per batch")
		report.appendLine(timing("A parser", samplesA))
		report.appendLine(timing("B classifier", samplesB))
		report.lineSequence().filter(String::isNotBlank).forEach { Log.i("LoopoVoiceAB", it) }
	}

	private fun canonical(text: String): String = Normalizer.normalize(text.lowercase(Locale.ITALIAN), Normalizer.Form.NFD)
		.replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}%]+"), " ").trim()

	private fun parseExpected(text: String): List<VoiceAction>? {
		if (text == "reject") return null
		return text.split(',').map { action ->
			when (action.substringBefore(':')) {
				"resume" -> VoiceAction.Resume
				"pause" -> VoiceAction.StayPaused
				"set" -> VoiceAction.SetRate(PlaybackRate(action.substringAfter(':').toInt()))
				"delta" -> VoiceAction.AdjustRate(checkNotNull(RateDeltaPercent.from(action.substringAfter(':').toInt())))
				else -> error("Unknown expected action $action")
			}
		}
	}

	private fun outcome(case: Case, plan: VoicePlan?): Outcome = when {
		plan?.actions == case.expected -> Outcome.CORRECT
		case.expected == null -> Outcome.FALSE_ACCEPTANCE
		plan == null -> Outcome.MISSED
		else -> Outcome.WRONG_PLAN
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
