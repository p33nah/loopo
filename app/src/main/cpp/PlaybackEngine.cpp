#include "PlaybackEngine.h"

#include <algorithm>
#include <chrono>

#if !defined(NDEBUG)
#include <android/log.h>
#endif

namespace {

int64_t outputLatencyFrames(oboe::AudioStream& stream) {
	const auto bufferFrames = stream.getBufferSizeInFrames();
	const auto burstFrames = stream.getFramesPerBurst();
	const auto sampleRate = stream.getSampleRate();
	if (bufferFrames <= 0 || burstFrames <= 0 || sampleRate <= 0) return -1;
	return static_cast<int64_t>(bufferFrames) + burstFrames;
}

}

PlaybackEngine::PlaybackEngine(int sampleRate, int channelCount, int capacityFrames)
		: sampleRate_(sampleRate), channelCount_(channelCount), ring_(channelCount, capacityFrames),
		  stretcher_(sampleRate, channelCount),
		  sourcePositionScratch_(capacityFrames),
		  callbackSourceFrames_(capacityFrames), audiblePositionHistory_(capacityFrames) {}

PlaybackEngine::~PlaybackEngine() {
	close();
}

bool PlaybackEngine::open() {
	outputLatencyFrames_.store(-1, std::memory_order_release);
	oboe::AudioStreamBuilder builder;
	builder.setDirection(oboe::Direction::Output);
	builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
	builder.setSharingMode(oboe::SharingMode::Shared);
	builder.setFormat(oboe::AudioFormat::Float);
	builder.setChannelCount(2);
	builder.setSampleRate(sampleRate_);
	builder.setUsage(oboe::Usage::Media);
	builder.setContentType(oboe::ContentType::Music);
	builder.setDataCallback(shared_from_this());
	if (builder.openStream(stream_) != oboe::Result::OK || !stream_) return false;
	if (stream_->getFormat() != oboe::AudioFormat::Float || stream_->getChannelCount() <= 0) {
		stream_->close();
		stream_.reset();
		return false;
	}
	outputLatencyFrames_.store(outputLatencyFrames(*stream_), std::memory_order_release);
#if !defined(NDEBUG)
	__android_log_print(ANDROID_LOG_DEBUG, "LoopoLatency",
			"sampleRate=%d bufferFrames=%d burstFrames=%d latencyFrames=%lld",
			stream_->getSampleRate(), stream_->getBufferSizeInFrames(), stream_->getFramesPerBurst(),
			static_cast<long long>(outputLatencyFrames_.load(std::memory_order_acquire)));
#endif
	return true;
}

void PlaybackEngine::close() {
	outputLatencyFrames_.store(-1, std::memory_order_release);
	if (stream_) {
		playing_.store(false, std::memory_order_release);
		stream_->requestStop();
		stream_->close();
		stream_.reset();
	}
	sourceFrameMapper_.reset();
}

bool PlaybackEngine::play() {
	if (!stream_ || ended_.load(std::memory_order_acquire)) return false;
	playing_.store(true, std::memory_order_release);
	const auto result = stream_->requestStart();
	if (result != oboe::Result::OK) playing_.store(false, std::memory_order_release);
	return result == oboe::Result::OK;
}

bool PlaybackEngine::pauseAndWait() {
	if (!stream_) return false;
	playing_.store(false, std::memory_order_release);
	const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(500);
	while (true) {
		const auto current = stream_->getState();
		if (current == oboe::StreamState::Open || current == oboe::StreamState::Paused ||
				current == oboe::StreamState::Flushed || current == oboe::StreamState::Stopped ||
				current == oboe::StreamState::Closed) return true;
		const auto remaining = std::chrono::duration_cast<std::chrono::nanoseconds>(deadline - std::chrono::steady_clock::now());
		if (remaining.count() <= 0) return false;
		if (current == oboe::StreamState::Starting || current == oboe::StreamState::Pausing ||
				current == oboe::StreamState::Flushing || current == oboe::StreamState::Stopping) {
			oboe::StreamState next = current;
			if (stream_->waitForStateChange(current, &next, remaining.count()) != oboe::Result::OK) return false;
			continue;
		}
		if (stream_->requestPause() != oboe::Result::OK) return false;
	}
}

bool PlaybackEngine::resetAtFrame(int64_t sourceFrame) {
	if (!pauseAndWait()) return false;
	if (stream_->getState() == oboe::StreamState::Paused) {
		const auto result = stream_->getAudioApi() == oboe::AudioApi::OpenSLES
				? stream_->requestFlush() : stream_->flush(500'000'000);
		if (result != oboe::Result::OK) return false;
	}
	ring_.clear();
	stretcher_.reset();
	sourceFrameMapper_.reset();
	sourcePosition_.store(sourceFrame, std::memory_order_release);
	basePosition_.store(sourceFrame, std::memory_order_release);
	renderedOutputFrames_.store(0, std::memory_order_release);
	inputEnded_.store(false, std::memory_order_release);
	ended_.store(false, std::memory_order_release);
	return true;
}

int PlaybackEngine::prerollFrames(float playbackRate) const {
	return stretcher_.prerollFrames(playbackRate);
}

bool PlaybackEngine::prime(const float* samples, int frames, float playbackRate) {
	if (inputEnded_.load(std::memory_order_acquire)) return false;
	return stretcher_.prime(samples, frames, playbackRate);
}

int PlaybackEngine::write(const float* samples, int inputFrames, int outputFrames, int64_t sourceStartFrame,
		int playbackRatePercent, int64_t loopStartFrame, int64_t loopEndFrame) {
	if (!samples || inputFrames <= 0 || inputEnded_.load(std::memory_order_acquire) ||
			outputFrames > ring_.availableToWrite()) return 0;
	auto nextSourceFrameMapper = sourceFrameMapper_;
	if (!nextSourceFrameMapper.map(sourceStartFrame, inputFrames, outputFrames, playbackRatePercent,
			loopStartFrame, loopEndFrame, sourcePositionScratch_.data(),
			static_cast<int>(sourcePositionScratch_.size()))) return 0;
	if (!stretcher_.process(samples, inputFrames, outputFrames)) return 0;
	if (ring_.write(stretcher_.output(), outputFrames, sourcePositionScratch_.data()) != outputFrames) return 0;
	sourceFrameMapper_ = nextSourceFrameMapper;
	return inputFrames;
}

bool PlaybackEngine::finish(int playbackRatePercent, int remainderUnits, int64_t endFrame) {
	if (inputEnded_.load(std::memory_order_acquire)) return false;
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
	inputEnded_.store(true, std::memory_order_release);
	return true;
}

int PlaybackEngine::writeSpace() const {
	return ring_.availableToWrite();
}

int PlaybackEngine::channelCount() const {
	return channelCount_;
}

void PlaybackEngine::setEndOfStream(bool ended) {
	inputEnded_.store(ended, std::memory_order_release);
}

int PlaybackEngine::queuedFrames() const {
	return ring_.availableToRead();
}

int64_t PlaybackEngine::positionFrame() const {
	const auto renderedOutputFrames = renderedOutputFrames_.load(std::memory_order_acquire);
	if (renderedOutputFrames <= 0) return basePosition_.load(std::memory_order_acquire);
	const auto estimatedLatencyFrames = outputLatencyFrames_.load(std::memory_order_acquire);
	if (estimatedLatencyFrames < 0) return sourcePosition_.load(std::memory_order_acquire);
	const auto latencyFrames = std::min<int64_t>(
		estimatedLatencyFrames,
		static_cast<int64_t>(audiblePositionHistory_.size() - 1));
	if (renderedOutputFrames <= latencyFrames) return basePosition_.load(std::memory_order_acquire);
	const auto outputFrame = renderedOutputFrames - latencyFrames - 1;
	const auto position = audiblePositionHistory_[outputFrame % audiblePositionHistory_.size()].load(std::memory_order_acquire);
	return position >= 0 ? position : sourcePosition_.load(std::memory_order_acquire);
}

double PlaybackEngine::estimatedLatencyMillis() {
	if (!stream_) return -1.0;
	const auto latencyFrames = outputLatencyFrames(*stream_);
	outputLatencyFrames_.store(latencyFrames, std::memory_order_release);
	const auto sampleRate = stream_->getSampleRate();
	if (latencyFrames < 0 || sampleRate <= 0) return -1.0;
	return static_cast<double>(latencyFrames) * 1000.0 / sampleRate;
}

int64_t PlaybackEngine::underrunCount() const {
	return underruns_.load(std::memory_order_relaxed);
}

bool PlaybackEngine::isPlaying() const {
	return playing_.load(std::memory_order_acquire);
}

bool PlaybackEngine::isEnded() const {
	return ended_.load(std::memory_order_acquire);
}

oboe::DataCallbackResult PlaybackEngine::onAudioReady(oboe::AudioStream* stream, void* audioData,
											 int32_t numFrames) {
	if (!stream || !audioData) return oboe::DataCallbackResult::Stop;
	const auto outputChannels = stream->getChannelCount();
	auto* output = static_cast<float*>(audioData);
	const auto sampleCount = static_cast<size_t>(numFrames) * outputChannels;
	std::fill_n(output, sampleCount, 0.0f);
	if (!playing_.load(std::memory_order_acquire)) return oboe::DataCallbackResult::Continue;
	if (static_cast<size_t>(numFrames) > callbackSourceFrames_.size()) {
		underruns_.fetch_add(1, std::memory_order_relaxed);
		return oboe::DataCallbackResult::Continue;
	}

	const auto framesRead = ring_.read(output, numFrames, outputChannels, callbackSourceFrames_.data());
	const auto outputStartFrame = renderedOutputFrames_.load(std::memory_order_relaxed);
	auto sourcePosition = sourcePosition_.load(std::memory_order_relaxed);
	for (int32_t frame = 0; frame < numFrames; ++frame) {
		if (frame < framesRead) sourcePosition = callbackSourceFrames_[frame] + 1;
		audiblePositionHistory_[(outputStartFrame + frame) % audiblePositionHistory_.size()].store(
			sourcePosition, std::memory_order_relaxed);
	}
	sourcePosition_.store(sourcePosition, std::memory_order_release);
	renderedOutputFrames_.store(outputStartFrame + numFrames, std::memory_order_release);
	if (framesRead < numFrames) {
		if (inputEnded_.load(std::memory_order_acquire) && ring_.availableToRead() == 0) {
			playing_.store(false, std::memory_order_release);
			ended_.store(true, std::memory_order_release);
			return oboe::DataCallbackResult::Stop;
		}
		underruns_.fetch_add(1, std::memory_order_relaxed);
	}
	return oboe::DataCallbackResult::Continue;
}
