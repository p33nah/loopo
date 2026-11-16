package com.p33nah.loopo.ui.voice

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Temporary CLI-injected replay fixture; ordinary app and test builds do not compile it. */
class ParserBenchmarkBridgeTest {
	private data class InputRow(val id: String, val text: String)

	@Test
	fun replayJsonlThroughBothBaselines() {
		val rows = File(requiredEnv("LOOPO_BENCH_INPUT_TSV")).useLines { lines ->
			lines.filter(String::isNotBlank).mapIndexed { index, line ->
				val columns = line.split('\t')
				require(columns.size == 2) { "Malformed normalized row ${index + 1}" }
				InputRow(decode(columns[0]), decode(columns[1]))
			}.toList()
		}
		assertTrue("Input JSONL has no rows", rows.isNotEmpty())

		val parserOutput = File(requiredEnv("LOOPO_BENCH_PARSER_OUTPUT"))
		val classifierOutput = File(requiredEnv("LOOPO_BENCH_CLASSIFIER_OUTPUT"))
		val trainingDirectory = File(requiredEnv("LOOPO_BENCH_TRAINING_DIR"))
		val training = readTraining(trainingDirectory.resolve("training.txt")) +
			readTraining(trainingDirectory.resolve("training-extra.txt"))
		val classifier = ItalianIntentClassifier(training)
		repeat(WARMUP_PASSES) {
			for (row in rows) {
				ItalianVoiceCommandParser.parse(row.text)
				classifier.interpret(row.text)
			}
		}

		writeReplay(parserOutput, rows, "production-parser", ItalianVoiceCommandParser::parse)
		writeReplay(classifierOutput, rows, "pr67-expanded-classifier", classifier::interpret)
	}

	private fun readTraining(file: File): List<Pair<ItalianIntent, String>> = file.useLines { lines ->
		lines.filter(String::isNotBlank).mapIndexed { index, line ->
			val split = line.indexOf('|')
			require(split > 0) { "Malformed training row ${file.name}:${index + 1}" }
			ItalianIntent.valueOf(line.substring(0, split)) to line.substring(split + 1)
		}.toList()
	}

	private fun writeReplay(
		output: File,
		rows: List<InputRow>,
		baseline: String,
		interpret: (String) -> VoicePlan?,
	) {
		output.parentFile?.mkdirs()
		output.bufferedWriter().use { writer ->
			for (row in rows) {
				val start = System.nanoTime()
				val plan = interpret(row.text)
				val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
				val calls = plan?.actions.orEmpty().joinToString(prefix = "[", postfix = "]") { toolCall(it) }
				writer.appendLine(
					"{\"id\":${quote(row.id)},\"calls\":$calls," +
						"\"invalid\":false,\"accepted\":${plan != null}," +
						"\"latency_ms\":$elapsedMs,\"baseline\":${quote(baseline)}}",
				)
			}
		}
	}

	private fun toolCall(action: VoiceAction): String = when (action) {
		VoiceAction.Resume -> call("resume")
		VoiceAction.Restart -> call("restart")
		VoiceAction.StayPaused -> call("stay_paused")
		is VoiceAction.SetRate -> call("set_rate", "percent" to action.rate.percent.toString())
		is VoiceAction.AdjustRate -> call("adjust_rate", "delta_percent" to action.delta.value.toString())
	}

	private fun call(name: String, vararg arguments: Pair<String, String>): String = buildString {
		append("{\"name\":${quote(name)},\"arguments\":{")
		append(arguments.joinToString(",") { (key, value) -> "${quote(key)}:$value" })
		append("}}")
	}

	private fun decode(value: String): String =
		String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)

	private fun quote(value: String): String = buildString {
		append('"')
		for (character in value) {
			when (character) {
				'"' -> append("\\\"")
				'\\' -> append("\\\\")
				'\b' -> append("\\b")
				'\u000c' -> append("\\f")
				'\n' -> append("\\n")
				'\r' -> append("\\r")
				'\t' -> append("\\t")
				else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
			}
		}
		append('"')
	}

	private fun requiredEnv(name: String): String = checkNotNull(System.getenv(name)) {
		"Set $name by running scripts/replay_baselines.py"
	}

	private companion object {
		const val WARMUP_PASSES = 3
	}
}
