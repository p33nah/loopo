#include "PcmRingBuffer.h"
#include "TimeStretcher.h"
#include "NativeArrayBounds.h"
#include "NativeAudioLimits.h"
#include "SourceFrameMapper.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <exception>
#include <iostream>
#include <limits>
#include <numbers>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace {

void require(bool condition, const std::string& message) {
	if (!condition) throw std::runtime_error(message);
}

void requireNear(double actual, double expected, double tolerance, const std::string& message) {
	if (!std::isfinite(actual) || std::abs(actual - expected) > tolerance) {
		throw std::runtime_error(message + " (actual=" + std::to_string(actual) +
				", expected=" + std::to_string(expected) + ")");
	}
}

void testRingCapacityPartialReadAndChannels() {
	PcmRingBuffer ring(2, 3);
	const float input[] = {1, 10, 2, 20, 3, 30, 4, 40};
	const int64_t inputPositions[] = {7, 8, 9, 10};
	require(ring.availableToWrite() == 3, "new ring has its declared writable frame capacity");
	require(ring.write(input, 4, inputPositions) == 3, "write accepts only available frames");
	require(ring.availableToRead() == 3 && ring.availableToWrite() == 0,
			"accepted frames become readable and consume capacity");

	float mono[2]{};
	int64_t metadata[4]{};
	require(ring.read(mono, 2, 1, metadata) == 2, "read returns the available portion of a request");
	require(mono[0] == 1 && mono[1] == 2, "stereo to mono keeps the left channel");
	require(metadata[0] == 7 && metadata[1] == 8, "read preserves source frame metadata");
	require(ring.availableToRead() == 1 && ring.availableToWrite() == 2,
			"partial read releases exactly the consumed capacity");

	const float next[] = {5, 50, 6, 60};
	const int64_t nextPositions[] = {10, 11};
	require(ring.write(next, 2, nextPositions) == 2, "write reuses released capacity");
	float stereo[6]{};
	require(ring.read(stereo, 3, 2, metadata) == 3, "read drains wrapped data");
	const float expected[] = {3, 30, 5, 50, 6, 60};
	require(std::equal(std::begin(stereo), std::end(stereo), std::begin(expected)),
			"wrapped reads preserve interleaved channel order");
	require(metadata[0] == 9 && metadata[1] == 10 && metadata[2] == 11,
			"wrapped reads preserve source frame metadata");
	require(ring.availableToRead() == 0 && ring.availableToWrite() == 3,
			"draining the ring restores full capacity");

	PcmRingBuffer monoRing(1, 2);
	const float monoInput[] = {0.25f, -0.75f};
	const int64_t monoPositions[] = {0, 1};
	require(monoRing.write(monoInput, 2, monoPositions) == 2, "mono input writes");
	float upmixed[4]{};
	require(monoRing.read(upmixed, 2, 2, metadata) == 2, "mono frames can be read as stereo");
	require(upmixed[0] == 0.25f && upmixed[1] == 0.25f &&
			upmixed[2] == -0.75f && upmixed[3] == -0.75f,
			"mono to stereo duplicates each sample");
}

void testRingLoopMetadataAndWraparound() {
	PcmRingBuffer ring(1, 4);
	const float values[] = {0, 1, 2, 3};
	const int64_t beforeWrap[] = {1, 2, 3};
	require(ring.write(values, 3, beforeWrap) == 3, "write frames around loop start");
	float output[4]{};
	int64_t sourceFrames[4]{};
	require(ring.read(output, 2, 1, sourceFrames) == 2, "read initial loop segment");
	require(sourceFrames[0] == 1 && sourceFrames[1] == 2, "metadata before loop end remains unchanged");
	const float loopEndValue[] = {3.0f, 0.0f};
	const int64_t afterWrap[] = {2};
	require(ring.write(loopEndValue, 1, afterWrap) == 1, "write wraps at loop end");
	require(ring.read(output, 2, 1, sourceFrames) == 2, "read wrapped storage and loop metadata");
	require(sourceFrames[0] == 3 && sourceFrames[1] == 2,
			"loop metadata wraps source frame 5 to loop frame 2");
	require(output[0] == 2 && output[1] == 3,
			"storage wraparound does not alter audio sample ordering");

	PcmRingBuffer fractionalRing(1, 4);
	const float fractionalInput[] = {4, 5, 6, 7};
	const int64_t fractionalPositions[] = {10, 11, 13, 11};
	require(fractionalRing.write(fractionalInput, 4, fractionalPositions) == 4,
			"fractional source frame spacing writes");
	require(fractionalRing.read(output, 4, 1, sourceFrames) == 4,
			"fractional source frame spacing reads");
	require(sourceFrames[0] == 10 && sourceFrames[1] == 11 &&
			sourceFrames[2] == 13 && sourceFrames[3] == 11,
			"source metadata advances by fractional rate and wraps at the loop boundary");
}

void testRingSpscStress() {
	constexpr int frameCount = 500'000;
	constexpr int capacity = 257;
	PcmRingBuffer ring(1, capacity);
	std::atomic<bool> failed{false};
	std::string failure;
	std::thread producer([&] {
		for (int base = 0; base < frameCount && !failed.load(std::memory_order_relaxed);) {
			const int count = std::min(113, frameCount - base);
			float values[113];
			int64_t positions[113];
			for (int i = 0; i < count; ++i) {
				values[i] = static_cast<float>(base + i);
				positions[i] = base + i;
			}
			const auto wrote = ring.write(values, count, positions);
			base += wrote;
			if (wrote == 0) std::this_thread::yield();
		}
	});
	std::thread consumer([&] {
		int expected = 0;
		while (expected < frameCount && !failed.load(std::memory_order_relaxed)) {
			float values[97];
			int64_t sourceFrames[97];
			const auto count = ring.read(values, 97, 1, sourceFrames);
			if (count == 0) {
				std::this_thread::yield();
				continue;
			}
			for (int i = 0; i < count; ++i) {
				if (values[i] != static_cast<float>(expected) || sourceFrames[i] != expected) {
					failure = "SPSC stress lost ordering or source frame metadata at frame " +
							std::to_string(expected);
					failed.store(true, std::memory_order_relaxed);
					return;
				}
				++expected;
			}
		}
	});
	producer.join();
	consumer.join();
	require(!failed.load(std::memory_order_relaxed), failure.empty() ? "SPSC stress succeeds" : failure);
	require(ring.availableToRead() == 0, "SPSC stress leaves no queued frames");
}

struct SourceSpanReference {
	int64_t inputStart;
	int frameCount;
	int64_t sourceStart;
};

void testSourceFrameMapperMatchesSeededInputSequence() {
	for (const bool discontinuous : {false, true}) {
		for (int ratePercent = 25; ratePercent <= 200; ++ratePercent) {
			SourceFrameMapper mapper;
			std::vector<SourceSpanReference> spans;
			int remainderUnits = 0;
			int64_t totalInputFrames = 0;
			int64_t totalOutputFrames = 0;
			uint32_t seed = static_cast<uint32_t>(0x9e3779b9u + ratePercent * 17 + discontinuous);
			for (int chunk = 0; chunk < 16; ++chunk) {
				seed = seed * 1664525u + 1013904223u;
				const int inputFrames = chunk % 4 == 0 ? 1 : chunk % 4 == 1 ? 2'048 :
						1 + static_cast<int>(seed % 2'048u);
				const int64_t sourceStartFrame = discontinuous
						? 50'000 + static_cast<int64_t>(chunk) * 10'000 + totalInputFrames
						: 10'000 + totalInputFrames;
				const int64_t totalUnits = static_cast<int64_t>(inputFrames) *
						SourceFrameMapper::SOURCE_UNITS_PER_INPUT_FRAME + remainderUnits;
				const int outputFrames = static_cast<int>(totalUnits / ratePercent);
				remainderUnits = static_cast<int>(totalUnits % ratePercent);
				std::vector<int64_t> positions(static_cast<size_t>(std::max(1, outputFrames)));
				require(mapper.map(sourceStartFrame, inputFrames, outputFrames, ratePercent, -1, -1,
						positions.data(), static_cast<int>(positions.size())),
						"bounded mapper accepts seeded input at rate=" + std::to_string(ratePercent) +
						"%, chunk=" + std::to_string(chunk));
				spans.push_back({totalInputFrames, inputFrames, sourceStartFrame});
				for (int frame = 0; frame < outputFrames; ++frame) {
					const int64_t sourceCursorUnits = totalOutputFrames * ratePercent;
					const auto span = std::find_if(spans.begin(), spans.end(), [sourceCursorUnits](const auto& candidate) {
						const auto spanStartUnits = candidate.inputStart *
								SourceFrameMapper::SOURCE_UNITS_PER_INPUT_FRAME;
						const auto spanEndUnits = spanStartUnits + static_cast<int64_t>(candidate.frameCount) *
								SourceFrameMapper::SOURCE_UNITS_PER_INPUT_FRAME;
						return sourceCursorUnits >= spanStartUnits && sourceCursorUnits < spanEndUnits;
					});
					require(span != spans.end(), "each output starts within accepted input");
					const int64_t spanStartUnits = span->inputStart *
							SourceFrameMapper::SOURCE_UNITS_PER_INPUT_FRAME;
					const auto expected = span->sourceStart + (sourceCursorUnits - spanStartUnits) /
							SourceFrameMapper::SOURCE_UNITS_PER_INPUT_FRAME;
					require(positions[frame] == expected,
							"incremental source positions match the seeded full-input reference");
					++totalOutputFrames;
				}
				totalInputFrames += inputFrames;
			}
		}
	}
}

void testSourceFrameMapperPreservesFractionalCrossfadeAnchorsAndReset() {
	SourceFrameMapper mapper;
	int64_t positions[4]{};
	int remainderUnits = 0;
	auto write = [&](int64_t sourceStartFrame, int inputFrames, int ratePercent) {
		const int64_t totalUnits = static_cast<int64_t>(inputFrames) *
				SourceFrameMapper::SOURCE_UNITS_PER_INPUT_FRAME + remainderUnits;
		const int outputFrames = static_cast<int>(totalUnits / ratePercent);
		remainderUnits = static_cast<int>(totalUnits % ratePercent);
		require(mapper.map(sourceStartFrame, inputFrames, outputFrames, ratePercent, 20, 100,
				positions, 4), "fractional source span is accepted");
		return outputFrames;
	};
	require(write(92, 1, 150) == 0, "one frame can wait for its first stretched output");
	require(write(28, 1, 150) == 1 && positions[0] == 92,
			"first stretched output keeps the pending tail-crossfade source origin");
	require(write(29, 2, 150) == 1 && positions[0] == 28,
			"next output adopts the explicit post-crossfade a-plus-fade anchor");
	require(write(31, 2, 150) == 2 && positions[0] == 30 && positions[1] == 31,
			"fractional positions continue through the next anchored span");

	mapper.reset();
	remainderUnits = 0;
	require(write(99, 3, 100) == 3 && positions[0] == 99 && positions[1] == 20 && positions[2] == 21,
			"linear spans preserve loop-end wrapping");

	mapper.reset();
	remainderUnits = 0;
	require(write(7, 1, 200) == 0, "a fast-rate frame remains pending");
	const auto rejected = mapper.map(8, 1, 0, 200, 20, 100, positions, 4);
	require(!rejected, "an output budget inconsistent with pending input is rejected");
	require(mapper.map(8, 1, 1, 200, 20, 100, positions, 4) && positions[0] == 7,
			"rejected mapping leaves pending source positions unchanged");
	mapper.reset();
	remainderUnits = 0;
	require(write(70, 1, 200) == 0, "reset allows a new source to become pending");
	mapper.reset();
	remainderUnits = 0;
	require(mapper.map(80, 1, 0, 200, 20, 100, positions, 4), "new stream accepts input-only mapping");
	require(mapper.map(81, 1, 1, 200, 20, 100, positions, 4) && positions[0] == 80,
			"reset drops the previous stream's deferred source span");
	require(!mapper.map(82, 1, 0, 0, 20, 100, positions, 4), "mapper rejects an unsupported rate");
	require(!mapper.map(82, 1, 0, 200, 20, 20, positions, 4), "mapper rejects malformed loop bounds");
}

void testSourceFrameMapperBoundsAlternatingOneFrameAnchors() {
	SourceFrameMapper mapper;
	int64_t positions[1]{};
	int remainderUnits = 0;
	int64_t outputFramesSeen = 0;
	for (int inputIndex = 0; inputIndex < 8'192; ++inputIndex) {
		const int64_t totalUnits = 100 + remainderUnits;
		const int outputFrames = static_cast<int>(totalUnits / 199);
		remainderUnits = static_cast<int>(totalUnits % 199);
		const int64_t sourceStartFrame = 10'000 + static_cast<int64_t>(inputIndex) * 17;
		require(mapper.map(sourceStartFrame, 1, outputFrames, 199, -1, -1, positions, 1),
				"alternating one-frame spans remain within the fixed mapper bound");
		for (int frame = 0; frame < outputFrames; ++frame) {
			const auto sourceIndex = outputFramesSeen * 199 / 100;
			const auto expected = 10'000 + sourceIndex * 17;
			require(positions[frame] == expected,
					"bounded spans retain each one-frame source anchor after deferred output");
			++outputFramesSeen;
		}
	}
}

std::vector<float> makeSine(int frames, int channels, int sampleRate, double frequency) {
	std::vector<float> samples(static_cast<size_t>(frames) * channels);
	for (int frame = 0; frame < frames; ++frame) {
		const float sample = static_cast<float>(0.6 * std::sin(2.0 * std::numbers::pi *
				frequency * frame / sampleRate));
		for (int channel = 0; channel < channels; ++channel) {
			samples[static_cast<size_t>(frame) * channels + channel] = sample;
		}
	}
	return samples;
}

double estimateZeroCrossingFrequency(const std::vector<float>& samples, int channels,
		int sampleRate, size_t beginFrame, size_t endFrame) {
	size_t crossings = 0;
	for (size_t frame = beginFrame + 1; frame < endFrame; ++frame) {
		const float previous = samples[(frame - 1) * channels];
		const float current = samples[frame * channels];
		if (previous <= 0.0f && current > 0.0f) ++crossings;
	}
	return static_cast<double>(crossings) * sampleRate / (endFrame - beginFrame);
}

std::vector<float> stretchSine(int channels, float rate) {
	constexpr int sampleRate = 48'000;
	constexpr int inputFrames = 16'000;
	constexpr double frequency = 440.0;
	TimeStretcher stretcher(sampleRate, channels);
	const int preroll = stretcher.prerollFrames(rate);
	require(preroll > 0 && preroll <= inputFrames, "valid playback rates have a usable preroll size");
	auto samples = makeSine(inputFrames + preroll, channels, sampleRate, frequency);
	require(stretcher.prime(samples.data(), preroll, rate), "DSP accepts valid mono/stereo preroll");

	const int outputFrames = static_cast<int>(std::lround(inputFrames / rate));
	std::vector<float> output;
	output.reserve(static_cast<size_t>(outputFrames) * channels);
	int inputOffset = 0;
	int outputWritten = 0;
	while (inputOffset < inputFrames) {
		const int inputChunk = std::min(2'048, inputFrames - inputOffset);
		const int targetOutput = static_cast<int>((static_cast<int64_t>(inputOffset + inputChunk) * outputFrames) /
				inputFrames);
		const int outputChunk = targetOutput - outputWritten;
		const auto* chunk = samples.data() + static_cast<size_t>(preroll + inputOffset) * channels;
		require(stretcher.process(chunk, inputChunk, outputChunk), "DSP accepts each requested block");
		output.insert(output.end(), stretcher.output(), stretcher.output() +
				static_cast<size_t>(outputChunk) * channels);
		inputOffset += inputChunk;
		outputWritten = targetOutput;
	}
	return output;
}

void testStretcherRatePitchAndChannels() {
	for (const auto rate : {0.25f, 0.5f, 1.0f, 1.5f, 2.0f}) {
		for (const auto channels : {1, 2}) {
			const auto output = stretchSine(channels, rate);
			const size_t outputFrames = output.size() / channels;
			const double durationSeconds = static_cast<double>(outputFrames) / 48'000;
			requireNear(durationSeconds, 16'000.0 / 48'000 / rate, 1.0 / 48'000,
					"DSP output duration follows playback rate");
			const auto frequency = estimateZeroCrossingFrequency(output, channels, 48'000,
					outputFrames / 5, outputFrames * 4 / 5);
			requireNear(frequency, 440.0, 35.0, "time stretching preserves sine pitch");
			for (const auto sample : output) require(std::isfinite(sample), "DSP output remains finite");
		}
	}
}

void testStretcherRetainsInputAcrossZeroOutput() {
	constexpr int sampleRate = 48'000;
	constexpr int inputFrames = 48'000;
	constexpr int referenceChunkFrames = 2'048;
	constexpr int outputFrames = inputFrames / 2;
	constexpr float rate = 2.0f;
	const auto preroll = TimeStretcher(sampleRate, 1).prerollFrames(rate);
	auto signal = makeSine(preroll + inputFrames, 1, sampleRate, 440.0);
	TimeStretcher segmented(sampleRate, 1);
	TimeStretcher batched(sampleRate, 1);
	require(segmented.prime(signal.data(), preroll, rate), "segmented DSP accepts a signal preroll");
	require(batched.prime(signal.data(), preroll, rate), "batched DSP accepts the same signal preroll");
	std::vector<float> segmentedOutput;
	segmentedOutput.reserve(outputFrames);
	for (int frame = 0; frame < inputFrames; ++frame) {
		const int outputCount = frame % 2 == 0 ? 0 : 1;
		require(segmented.process(signal.data() + preroll + frame, 1, outputCount),
				"DSP accepts each one-frame input at a two-times playback rate");
		if (outputCount == 1) segmentedOutput.push_back(segmented.output()[0]);
	}
	std::vector<float> batchedOutput;
	batchedOutput.reserve(outputFrames);
	for (int inputOffset = 0; inputOffset < inputFrames; inputOffset += referenceChunkFrames) {
		const int chunkFrames = std::min(referenceChunkFrames, inputFrames - inputOffset);
		const int chunkOutputFrames = chunkFrames / 2;
		require(batched.process(signal.data() + preroll + inputOffset, chunkFrames, chunkOutputFrames),
				"batched DSP accepts the same input and output budget");
		batchedOutput.insert(batchedOutput.end(), batched.output(), batched.output() + chunkOutputFrames);
	}
	require(segmentedOutput.size() == outputFrames, "alternating input-only writes conserve every due output");
	require(batchedOutput.size() == outputFrames, "batched writes produce the same output duration");
	for (const auto sample : segmentedOutput) require(std::isfinite(sample), "later output remains finite");
	auto rootMeanSquare = [](const std::vector<float>& values) {
		double squaredSamples = 0.0;
		for (const auto sample : values) squaredSamples += sample * sample;
		return std::sqrt(squaredSamples / values.size());
	};
	require(rootMeanSquare(segmentedOutput) > 0.01 && rootMeanSquare(batchedOutput) > 0.01,
			"input-only writes preserve signal samples submitted with zero output");
	const auto beginFrame = static_cast<size_t>(outputFrames / 5);
	const auto endFrame = static_cast<size_t>(outputFrames * 4 / 5);
	const auto segmentedFrequency = estimateZeroCrossingFrequency(
			segmentedOutput, 1, sampleRate, beginFrame, endFrame);
	const auto batchedFrequency = estimateZeroCrossingFrequency(
			batchedOutput, 1, sampleRate, beginFrame, endFrame);
	requireNear(segmentedFrequency, 440.0, 35.0,
			"alternating input-only output retains the 440 Hz source pitch");
	requireNear(batchedFrequency, 440.0, 35.0,
			"batched reference retains the 440 Hz source pitch");
	require(!segmented.finish(200, 0).empty() && !batched.finish(200, 0).empty(),
			"both valid long-running schedules can finish their DSP tail");
}

void testStretcherValidationResetAndFinish() {
	TimeStretcher stretcher(48'000, 1);
	const float silence[4]{};
	for (const auto rate : {0.0f, -1.0f, 0.24f, 2.01f,
			std::numeric_limits<float>::quiet_NaN(), std::numeric_limits<float>::infinity()}) {
		require(stretcher.prerollFrames(rate) == 0, "preroll rejects invalid rates before floating-to-integer conversion");
	}
	require(!stretcher.process(silence, 4, 4), "DSP rejects processing before priming");
	require(!stretcher.process(silence, 1, 0), "DSP rejects input-only processing before priming");
	require(!stretcher.prime(nullptr, 4, 1.0f), "DSP rejects null preroll");
	require(!stretcher.prime(silence, 0, 1.0f), "DSP rejects empty preroll");
	require(!stretcher.prime(silence, 4, 0.0f), "DSP rejects zero playback rate");
	require(!stretcher.prime(silence, 4, std::numeric_limits<float>::quiet_NaN()),
			"DSP rejects NaN playback rate");
	require(!stretcher.prime(silence, 4, 2.01f), "DSP rejects out-of-range playback rate");

	const auto preroll = stretcher.prerollFrames(1.0f);
	auto signal = makeSine(preroll + 256, 1, 48'000, 440.0);
	require(stretcher.prime(signal.data(), preroll, 1.0f), "DSP can be primed after rejected inputs");
	require(!stretcher.process(nullptr, 1, 1), "DSP rejects null input");
	require(!stretcher.process(nullptr, 1, 0), "DSP rejects null input with zero output");
	require(!stretcher.process(signal.data() + preroll, 0, 1), "DSP rejects empty input");
	require(stretcher.process(signal.data() + preroll, 256, 0), "DSP accepts valid input with empty output");
	require(!stretcher.process(signal.data() + preroll, 2'049, 1),
			"DSP rejects blocks beyond its input limit before reading them");
	require(stretcher.process(signal.data() + preroll, 256, 256),
			"primed DSP accepts a valid output request");
	for (const int invalid : {0, 24, 201}) {
		require(stretcher.finish(invalid, 0).empty(), "finish rejects unsupported playback rates");
	}
	require(stretcher.finish(100, -1).empty(), "finish rejects a negative source-unit remainder");
	require(stretcher.finish(100, 100).empty(), "finish rejects a remainder outside the rate interval");
	require(stretcher.finishFrameCount(200, 199) > 0,
			"finish accepts the last source-unit remainder at the fastest rate");
	const auto tail = stretcher.finish(100, 0);
	require(!tail.empty(), "finish emits a tail for a valid primed stream");
	for (const auto sample : tail) require(std::isfinite(sample), "finish tail remains finite");
	require(!stretcher.process(signal.data() + preroll, 256, 256),
			"finish marks the current stream complete");

	require(stretcher.prime(signal.data(), preroll, 1.0f), "DSP can be primed for a second stream");
	stretcher.reset();
	require(!stretcher.process(signal.data() + preroll, 256, 256),
			"reset rejects processing until the next preroll");
	require(stretcher.prime(signal.data(), preroll, 1.0f), "DSP can be primed after reset");
	require(stretcher.process(signal.data() + preroll, 256, 256),
			"reset allows a fresh stream to process normally");
}

void testInterleavedArrayBounds() {
	require(containsInterleavedFrames(0, 4, 2, 8), "exact interleaved array bounds are accepted");
	require(containsInterleavedFrames(1, 2, 2, 8), "valid offset interleaved ranges are accepted");
	require(!containsInterleavedFrames(1, 4, 2, 8), "interleaved overrun is rejected");
	require(!containsInterleavedFrames(2'147'483'600LL, 100, 2, 2'147'483'647LL),
			"large frame bounds are rejected without signed overflow");
	require(!containsInterleavedFrames(-1, 1, 2, 8), "negative offsets are rejected");
	require(!containsInterleavedFrames(0, 0, 2, 8), "empty frame ranges are rejected");
}

void testEngineResourceLimits() {
	for (const int rate : {8'000, 44'100, 48'000, 96'000, 192'000}) {
		for (const int channels : {1, 2}) {
			require(isSupportedEngineConfiguration(rate, channels, rate * 2), "supported PCM configurations accept a two-second buffer");
		}
	}
	for (const int rate : {0, -1, 7'999, 192'001, 1'000'000'000, std::numeric_limits<int>::max()}) {
		require(!isSupportedEngineConfiguration(rate, 2, 1), "untrusted sample rates are rejected before allocation");
	}
	require(!isSupportedEngineConfiguration(48'000, 3, 96'000), "unsupported channel layouts are rejected");
	require(!isSupportedEngineConfiguration(48'000, 2, 0), "empty native ring capacity is rejected");
	require(!isSupportedEngineConfiguration(48'000, 2, 96'001), "native ring capacity has a bounded allocation");
	require(!isSupportedEngineConfiguration(48'000, 2, std::numeric_limits<int>::max()), "extreme capacity cannot overflow allocation sizing");
}

} // namespace

int main() {
	const std::vector<std::pair<std::string, void (*)()>> cases = {
		{"ring capacity, partial read, channel conversion", testRingCapacityPartialReadAndChannels},
		{"ring loop metadata and storage wraparound", testRingLoopMetadataAndWraparound},
		{"ring SPSC stress", testRingSpscStress},
		{"source mapper seeded input sequence", testSourceFrameMapperMatchesSeededInputSequence},
		{"source mapper fractional anchors and reset", testSourceFrameMapperPreservesFractionalCrossfadeAnchorsAndReset},
		{"source mapper bounded one-frame anchors", testSourceFrameMapperBoundsAlternatingOneFrameAnchors},
		{"DSP rate, pitch, and channels", testStretcherRatePitchAndChannels},
		{"DSP input retention across zero output", testStretcherRetainsInputAcrossZeroOutput},
		{"DSP validation, reset, and finish", testStretcherValidationResetAndFinish},
		{"JNI interleaved array bounds", testInterleavedArrayBounds},
		{"native resource limits", testEngineResourceLimits},
	};
	try {
		for (const auto& [name, test] : cases) {
			test();
			std::cout << "PASS " << name << '\n';
		}
	} catch (const std::exception& error) {
		std::cerr << "FAIL " << error.what() << '\n';
		return 1;
	}
	return 0;
}
