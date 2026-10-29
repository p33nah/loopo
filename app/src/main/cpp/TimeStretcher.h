#pragma once

#include <algorithm>
#include <array>
#include <cmath>
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

	int process(const float* interleaved, int inputFrames, int outputFrames) {
		if (!primed_ || !interleaved || inputFrames <= 0 || inputFrames > MAX_INPUT_FRAMES ||
				outputFrames <= 0 || outputFrames > MAX_OUTPUT_FRAMES) return 0;
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
		return outputFrames;
	}

	std::vector<float> finish(float playbackRate, double outputRemainder) {
		std::vector<float> tail;
		const int inputFrames = stretcher_.inputLatency();
		tail.reserve(static_cast<size_t>(inputFrames / playbackRate + stretcher_.outputLatency()) * channelCount_);
		std::vector<float> silence(MAX_INPUT_FRAMES * channelCount_, 0.0f);
		int framesRemaining = inputFrames;
		while (framesRemaining > 0) {
			const int chunkFrames = std::min(MAX_INPUT_FRAMES, framesRemaining);
			const double requestedOutput = chunkFrames / playbackRate + outputRemainder;
			const int outputFrames = static_cast<int>(requestedOutput);
			outputRemainder = requestedOutput - outputFrames;
			if (process(silence.data(), chunkFrames, outputFrames) != outputFrames) return {};
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
