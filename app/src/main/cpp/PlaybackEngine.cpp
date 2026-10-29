#include "PlaybackEngine.h"

#include <algorithm>
#include <chrono>
#include <cmath>

PlaybackEngine::PlaybackEngine(int sampleRate, int channelCount, int capacityFrames)
		: sampleRate_(sampleRate), channelCount_(channelCount), ring_(channelCount, capacityFrames) {}

PlaybackEngine::~PlaybackEngine() {
	close();
}

bool PlaybackEngine::open() {
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
	return true;
}

void PlaybackEngine::close() {
	if (!stream_) return;
	playing_.store(false, std::memory_order_release);
	stream_->requestStop();
	stream_->close();
	stream_.reset();
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
	if (stream_->getState() == oboe::StreamState::Paused &&
			stream_->flush(500'000'000) != oboe::Result::OK) return false;
	ring_.clear();
	sourcePosition_.store(sourceFrame, std::memory_order_release);
	basePosition_.store(sourceFrame, std::memory_order_release);
	inputEnded_.store(false, std::memory_order_release);
	ended_.store(false, std::memory_order_release);
	return true;
}

int PlaybackEngine::write(const float* samples, int frames) {
	if (!samples || frames <= 0 || inputEnded_.load(std::memory_order_acquire)) return 0;
	return ring_.write(samples, frames);
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
	const auto renderedFrame = sourcePosition_.load(std::memory_order_acquire);
	const auto latencyMillis = estimatedLatencyMillis_.load(std::memory_order_acquire);
	if (latencyMillis < 0.0) return renderedFrame;
	const auto latencyFrames = static_cast<int64_t>(std::llround(latencyMillis * sampleRate_ / 1000.0));
	return std::max(basePosition_.load(std::memory_order_acquire), renderedFrame - latencyFrames);
}

double PlaybackEngine::estimatedLatencyMillis() {
	if (!stream_) return -1.0;
	const auto latency = stream_->calculateLatencyMillis();
	if (latency && std::isfinite(latency.value()) && latency.value() >= 0.0) {
		estimatedLatencyMillis_.store(latency.value(), std::memory_order_release);
	}
	return estimatedLatencyMillis_.load(std::memory_order_acquire);
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

	const auto framesRead = ring_.read(output, numFrames, outputChannels);
	sourcePosition_.fetch_add(framesRead, std::memory_order_relaxed);
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
