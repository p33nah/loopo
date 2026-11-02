#include "PlaybackProducer.h"

#include <algorithm>

PlaybackProducer::PlaybackProducer(int sampleRate, int channelCount, int capacityFrames)
		: channelCount_(channelCount), ring_(channelCount, capacityFrames),
		  stretcher_(sampleRate, channelCount), sourcePositionScratch_(capacityFrames) {}

int PlaybackProducer::prerollFrames(float playbackRate) const {
	return stretcher_.prerollFrames(playbackRate);
}

bool PlaybackProducer::prime(const float* samples, int frames, float playbackRate) {
	return stretcher_.prime(samples, frames, playbackRate);
}

int PlaybackProducer::write(const float* samples, int inputFrames, int outputFrames, int64_t sourceStartFrame,
		int playbackRatePercent, int64_t loopStartFrame, int64_t loopEndFrame) {
	if (!samples || inputFrames <= 0 || outputFrames > ring_.availableToWrite() ||
			outputFrames > static_cast<int>(sourcePositionScratch_.size())) return 0;
	auto nextSourceFrameMapper = sourceFrameMapper_;
	if (!nextSourceFrameMapper.map(sourceStartFrame, inputFrames, outputFrames, playbackRatePercent,
			loopStartFrame, loopEndFrame, sourcePositionScratch_.data(),
			static_cast<int>(sourcePositionScratch_.size()))) return 0;
	if (!stretcher_.process(samples, inputFrames, outputFrames)) return 0;
	if (ring_.write(stretcher_.output(), outputFrames, sourcePositionScratch_.data()) != outputFrames) return 0;
	sourceFrameMapper_ = nextSourceFrameMapper;
	return inputFrames;
}

bool PlaybackProducer::finish(int playbackRatePercent, int remainderUnits, int64_t endFrame) {
	const int expectedFrames = stretcher_.finishFrameCount(playbackRatePercent, remainderUnits);
	if (expectedFrames <= 0 || expectedFrames > ring_.availableToWrite() ||
			expectedFrames > static_cast<int>(sourcePositionScratch_.size())) return false;
	const auto tail = stretcher_.finish(playbackRatePercent, remainderUnits);
	if (tail.empty()) return false;
	const auto frames = static_cast<int>(tail.size() / channelCount_);
	if (frames != expectedFrames) return false;
	std::fill_n(sourcePositionScratch_.data(), frames, endFrame);
	if (ring_.write(tail.data(), frames, sourcePositionScratch_.data()) != frames) return false;
	sourceFrameMapper_.reset();
	return true;
}

void PlaybackProducer::reset() {
	ring_.clear();
	stretcher_.reset();
	sourceFrameMapper_.reset();
}

int PlaybackProducer::writeSpace() const {
	return ring_.availableToWrite();
}

int PlaybackProducer::read(float* output, int requestedFrames, int outputChannels, int64_t* sourceFrames) {
	return ring_.read(output, requestedFrames, outputChannels, sourceFrames);
}

int PlaybackProducer::queuedFrames() const {
	return ring_.availableToRead();
}
