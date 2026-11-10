#pragma once

#include <atomic>
#include <memory>
#include <vector>

#include <oboe/Oboe.h>

#include "PlaybackProducer.h"

class PlaybackEngine final : public oboe::AudioStreamDataCallback,
							 public oboe::AudioStreamErrorCallback,
							 public std::enable_shared_from_this<PlaybackEngine> {
public:
	PlaybackEngine(int sampleRate, int channelCount, int capacityFrames);
	~PlaybackEngine() override;

	bool open();
	void close();
	bool play();
	bool pauseAndWait();
	bool resetAtFrame(int64_t sourceFrame);
	int prerollFrames(float playbackRate) const;
	bool prime(const float* samples, int frames, float playbackRate);
	int write(const float* samples, int inputFrames, int outputFrames, int64_t sourceStartFrame,
			int playbackRatePercent, int64_t loopStartFrame, int64_t loopEndFrame);
	bool finish(int playbackRatePercent, int remainderUnits, int64_t endFrame);
	int writeSpace() const;
	int channelCount() const;
	void setEndOfStream(bool ended);
	int queuedFrames() const;
	int64_t positionFrame() const;
	double estimatedLatencyMillis();
	int64_t underrunCount() const;
	int32_t outputDeviceId() const;
	int takeStreamFailure();
	bool outputNeedsReopen() const;
	bool reopenOutput();
	bool isPlaying() const;
	bool isEnded() const;

	oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
										  int32_t numFrames) override;
	void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
	const int sampleRate_;
	const int channelCount_;
	PlaybackProducer producer_;
	std::vector<int64_t> callbackSourceFrames_;
	std::vector<std::atomic<int64_t>> audiblePositionHistory_;
	std::shared_ptr<oboe::AudioStream> stream_;
	std::atomic<oboe::AudioStream*> activeStream_{nullptr};
	std::atomic<bool> playing_{false};
	std::atomic<bool> inputEnded_{false};
	std::atomic<bool> ended_{false};
	std::atomic<int64_t> sourcePosition_{0};
	std::atomic<int64_t> basePosition_{0};
	std::atomic<int64_t> renderedOutputFrames_{0};
	std::atomic<int64_t> underruns_{0};
	std::atomic<int64_t> outputLatencyFrames_{-1};
	std::atomic<int> pendingStreamFailure_{static_cast<int>(oboe::Result::OK)};
	std::atomic<bool> streamNeedsReopen_{false};
};
