#pragma once

#include <cstdint>

inline bool containsInterleavedFrames(int64_t offsetFrames, int64_t frameCount,
		int64_t channelCount, int64_t sampleCount) {
	if (offsetFrames < 0 || frameCount <= 0 || channelCount <= 0 || sampleCount < 0) return false;
	const auto availableFrames = sampleCount / channelCount;
	return offsetFrames <= availableFrames && frameCount <= availableFrames - offsetFrames;
}
