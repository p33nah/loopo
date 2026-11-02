#pragma once

#include <cstdint>
#include <vector>

#include "PcmRingBuffer.h"
#include "SourceFrameMapper.h"
#include "TimeStretcher.h"

class PlaybackProducer final {
public:
	PlaybackProducer(int sampleRate, int channelCount, int capacityFrames);

	int prerollFrames(float playbackRate) const;
	bool prime(const float* samples, int frames, float playbackRate);
	int write(const float* samples, int inputFrames, int outputFrames, int64_t sourceStartFrame,
			int playbackRatePercent, int64_t loopStartFrame, int64_t loopEndFrame);
	bool finish(int playbackRatePercent, int remainderUnits, int64_t endFrame);
	void reset();
	int writeSpace() const;
	int read(float* output, int requestedFrames, int outputChannels, int64_t* sourceFrames);
	int queuedFrames() const;

private:
	const int channelCount_;
	PcmRingBuffer ring_;
	TimeStretcher stretcher_;
	SourceFrameMapper sourceFrameMapper_;
	std::vector<int64_t> sourcePositionScratch_;
};
