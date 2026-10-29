#pragma once

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <vector>

class PcmRingBuffer {
public:
	explicit PcmRingBuffer(int channelCount, int capacityFrames)
		: channelCount_(channelCount), capacityFrames_(capacityFrames), samples_(channelCount * capacityFrames, 0.0f) {}

	int write(const float* input, int frameCount) {
		const auto writeFrame = writeFrame_.load(std::memory_order_relaxed);
		const auto readFrame = readFrame_.load(std::memory_order_acquire);
		const auto queued = writeFrame - readFrame;
		const auto writable = std::min<int64_t>(frameCount, capacityFrames_ - queued);
		for (int64_t frame = 0; frame < writable; ++frame) {
			const auto target = ((writeFrame + frame) % capacityFrames_) * channelCount_;
			std::copy_n(input + frame * channelCount_, channelCount_, samples_.data() + target);
		}
		writeFrame_.store(writeFrame + writable, std::memory_order_release);
		return static_cast<int>(writable);
	}

	int read(float* output, int requestedFrames, int outputChannels) {
		const auto readFrame = readFrame_.load(std::memory_order_relaxed);
		const auto writeFrame = writeFrame_.load(std::memory_order_acquire);
		const auto readable = std::min<int64_t>(requestedFrames, writeFrame - readFrame);
		for (int64_t frame = 0; frame < readable; ++frame) {
			const auto source = ((readFrame + frame) % capacityFrames_) * channelCount_;
			for (int channel = 0; channel < outputChannels; ++channel) {
				const auto sourceChannel = channelCount_ == 1 ? 0 : std::min(channel, channelCount_ - 1);
				output[frame * outputChannels + channel] = samples_[source + sourceChannel];
			}
		}
		readFrame_.store(readFrame + readable, std::memory_order_release);
		return static_cast<int>(readable);
	}

	int availableToWrite() const {
		const auto writeFrame = writeFrame_.load(std::memory_order_relaxed);
		const auto readFrame = readFrame_.load(std::memory_order_acquire);
		return static_cast<int>(capacityFrames_ - (writeFrame - readFrame));
	}

	int availableToRead() const {
		const auto readFrame = readFrame_.load(std::memory_order_relaxed);
		const auto writeFrame = writeFrame_.load(std::memory_order_acquire);
		return static_cast<int>(writeFrame - readFrame);
	}

	void clear() {
		readFrame_.store(0, std::memory_order_relaxed);
		writeFrame_.store(0, std::memory_order_release);
	}

private:
	const int channelCount_;
	const int64_t capacityFrames_;
	std::vector<float> samples_;
	alignas(64) std::atomic<int64_t> readFrame_{0};
	alignas(64) std::atomic<int64_t> writeFrame_{0};
};
