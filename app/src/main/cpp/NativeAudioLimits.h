#pragma once

#include <cstdint>

inline bool isSupportedEngineConfiguration(int sampleRate, int channels, int capacityFrames) {
	return sampleRate >= 8'000 && sampleRate <= 192'000 && channels >= 1 && channels <= 2 &&
			capacityFrames > 0 && static_cast<int64_t>(capacityFrames) <= static_cast<int64_t>(sampleRate) * 2;
}
