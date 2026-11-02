#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <vector>

#include "third_party/signalsmith-stretch/signalsmith-stretch.h"

class TimeStretcher final {
public:
	TimeStretcher(int sampleRate, int channelCount)
			: sampleRate_(sampleRate), channelCount_(channelCount), stretcher_(1),
			  inputLeft_(MAX_PREROLL_FRAMES), inputRight_(MAX_PREROLL_FRAMES),
			  outputLeft_(MAX_OUTPUT_FRAMES), outputRight_(MAX_OUTPUT_FRAMES),
			  outputInterleaved_(MAX_OUTPUT_FRAMES * channelCount) {
		stretcher_.presetDefault(channelCount_, sampleRate_);
	}

	int prerollFrames(float playbackRate) const {
		if (!std::isfinite(playbackRate) || playbackRate < 0.25f || playbackRate > 2.0f) return 0;
		return stretcher_.outputSeekLength(playbackRate);
	}

	bool prime(const float* interleaved, int frameCount, float playbackRate) {
		if (!interleaved || frameCount <= 0 || frameCount > MAX_PREROLL_FRAMES ||
				!std::isfinite(playbackRate) || playbackRate < 0.25f || playbackRate > 2.0f) return false;
		for (int frame = 0; frame < frameCount; ++frame) {
			inputLeft_[frame] = interleaved[frame * channelCount_];
			if (channelCount_ == 2) inputRight_[frame] = interleaved[frame * channelCount_ + 1];
		}
		const std::array<float*, 2> input{inputLeft_.data(), inputRight_.data()};
		stretcher_.outputSeek(input.data(), frameCount);
		primed_ = true;
		return true;
	}

	bool process(const float* interleaved, int inputFrames, int outputFrames) {
		if (!primed_ || !interleaved || inputFrames <= 0 || inputFrames > MAX_INPUT_FRAMES ||
				outputFrames < 0 || outputFrames > MAX_OUTPUT_FRAMES) return false;
		for (int frame = 0; frame < inputFrames; ++frame) {
			inputLeft_[frame] = interleaved[frame * channelCount_];
			if (channelCount_ == 2) inputRight_[frame] = interleaved[frame * channelCount_ + 1];
		}
		std::array<const float*, 2> input{inputLeft_.data(), inputRight_.data()};
		std::array<float*, 2> output{outputLeft_.data(), outputRight_.data()};
		stretcher_.process(input.data(), inputFrames, output.data(), outputFrames);
		for (int frame = 0; frame < outputFrames; ++frame) {
			outputInterleaved_[frame * channelCount_] = outputLeft_[frame];
			if (channelCount_ == 2) outputInterleaved_[frame * channelCount_ + 1] = outputRight_[frame];
		}
		return true;
	}

	int finishFrameCount(int playbackRatePercent, int remainderUnits) const {
		if (!primed_ || playbackRatePercent < 25 || playbackRatePercent > 200 ||
				remainderUnits < 0 || remainderUnits >= playbackRatePercent) return -1;
		int outputFrameCount = 0;
		int remainder = remainderUnits;
		int inputFramesRemaining = stretcher_.inputLatency();
		while (inputFramesRemaining > 0) {
			const int chunkFrames = std::min(MAX_INPUT_FRAMES, inputFramesRemaining);
			const int64_t totalUnits = static_cast<int64_t>(chunkFrames) * 100 + remainder;
			const int outputFrames = static_cast<int>(totalUnits / playbackRatePercent);
			if (outputFrames < 0 || outputFrames > MAX_OUTPUT_FRAMES ||
					outputFrameCount > std::numeric_limits<int>::max() - outputFrames) return -1;
			outputFrameCount += outputFrames;
			remainder = static_cast<int>(totalUnits % playbackRatePercent);
			inputFramesRemaining -= chunkFrames;
		}
		const int flushFrames = stretcher_.outputLatency();
		if (flushFrames < 0 || flushFrames > MAX_OUTPUT_FRAMES ||
				outputFrameCount > std::numeric_limits<int>::max() - flushFrames) return -1;
		return outputFrameCount + flushFrames;
	}

	std::vector<float> finish(int playbackRatePercent, int remainderUnits) {
		const int expectedFrames = finishFrameCount(playbackRatePercent, remainderUnits);
		if (expectedFrames < 0) return {};
		std::vector<float> tail;
		const int inputFrames = stretcher_.inputLatency();
		tail.reserve(static_cast<size_t>(expectedFrames) * channelCount_);
		std::vector<float> silence(MAX_INPUT_FRAMES * channelCount_, 0.0f);
		int framesRemaining = inputFrames;
		while (framesRemaining > 0) {
			const int chunkFrames = std::min(MAX_INPUT_FRAMES, framesRemaining);
			const int64_t totalUnits = static_cast<int64_t>(chunkFrames) * 100 + remainderUnits;
			const int outputFrames = static_cast<int>(totalUnits / playbackRatePercent);
			remainderUnits = static_cast<int>(totalUnits % playbackRatePercent);
			if (!process(silence.data(), chunkFrames, outputFrames)) return {};
			tail.insert(tail.end(), outputInterleaved_.begin(), outputInterleaved_.begin() + outputFrames * channelCount_);
			framesRemaining -= chunkFrames;
		}
		const int flushFrames = stretcher_.outputLatency();
		if (flushFrames > MAX_OUTPUT_FRAMES) return {};
		std::array<float*, 2> output{outputLeft_.data(), outputRight_.data()};
		stretcher_.flush(output.data(), flushFrames);
		for (int frame = 0; frame < flushFrames; ++frame) {
			outputInterleaved_[frame * channelCount_] = outputLeft_[frame];
			if (channelCount_ == 2) outputInterleaved_[frame * channelCount_ + 1] = outputRight_[frame];
		}
		tail.insert(tail.end(), outputInterleaved_.begin(), outputInterleaved_.begin() + flushFrames * channelCount_);
		primed_ = false;
		if (tail.size() != static_cast<size_t>(expectedFrames) * channelCount_) return {};
		return tail;
	}

	const float* output() const { return outputInterleaved_.data(); }
	void reset() {
		stretcher_.reset();
		primed_ = false;
	}
private:
	static constexpr int MAX_INPUT_FRAMES = 2'048;
	static constexpr int MAX_PREROLL_FRAMES = 65'536;
	static constexpr int MAX_OUTPUT_FRAMES = 65'536;

	const int sampleRate_;
	const int channelCount_;
	signalsmith::stretch::SignalsmithStretch<float> stretcher_;
	std::vector<float> inputLeft_;
	std::vector<float> inputRight_;
	std::vector<float> outputLeft_;
	std::vector<float> outputRight_;
	std::vector<float> outputInterleaved_;
	bool primed_ = false;
};
