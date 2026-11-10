#pragma once

#include <array>
#include <cstdint>
#include <limits>

class SourceFrameMapper final {
public:
	static constexpr int MAX_PENDING_SPANS = 3;
	static constexpr int64_t SOURCE_UNITS_PER_INPUT_FRAME = 100;

	void reset() {
		spanCount_ = 0;
	}

	bool map(int64_t sourceStartFrame, int inputFrames, int outputFrames, int playbackRatePercent,
			int64_t loopStartFrame, int64_t loopEndFrame, int64_t* outputSourceFrames,
			int outputCapacity) {
		if (!validRequest(sourceStartFrame, inputFrames, outputFrames, playbackRatePercent,
				loopStartFrame, loopEndFrame, outputSourceFrames, outputCapacity)) return false;

		auto planned = *this;
		if (!planned.mapPlanned(sourceStartFrame, inputFrames, outputFrames, playbackRatePercent,
				loopStartFrame, loopEndFrame, outputSourceFrames, outputCapacity)) return false;
		*this = planned;
		return true;
	}

private:
	struct Span {
		int64_t sourceStartFrame;
		int64_t sourceLengthUnits;
		int64_t offsetUnits;
		int64_t loopStartFrame;
		int64_t loopEndFrame;
	};

	bool validRequest(int64_t sourceStartFrame, int inputFrames, int outputFrames, int playbackRatePercent,
			int64_t loopStartFrame, int64_t loopEndFrame, const int64_t* outputSourceFrames,
			int outputCapacity) const {
		const bool noLoop = loopStartFrame == -1 && loopEndFrame == -1;
		const bool validLoop = loopStartFrame >= 0 && loopEndFrame > loopStartFrame;
		return sourceStartFrame >= 0 && inputFrames > 0 && outputFrames >= 0 &&
				playbackRatePercent >= 25 && playbackRatePercent <= 200 &&
				(sourceStartFrame <= std::numeric_limits<int64_t>::max() - inputFrames) &&
				(noLoop || validLoop) && (outputFrames == 0 || outputSourceFrames != nullptr) &&
				outputCapacity >= outputFrames;
	}

	bool mapPlanned(int64_t sourceStartFrame, int inputFrames, int outputFrames, int playbackRatePercent,
			int64_t loopStartFrame, int64_t loopEndFrame, int64_t* outputSourceFrames,
			int outputCapacity) {
		normalize();
		if (spanCount_ == MAX_PENDING_SPANS) return false;

		int64_t pendingUnits = 0;
		for (int index = 0; index < spanCount_; ++index) {
			const auto offset = index == 0 ? spans_[index].offsetUnits : 0;
			pendingUnits += spans_[index].sourceLengthUnits - offset;
		}
		const int64_t inputUnits = static_cast<int64_t>(inputFrames) * SOURCE_UNITS_PER_INPUT_FRAME;
		const int64_t totalUnits = pendingUnits + inputUnits;
		const int64_t expectedFrames = totalUnits / playbackRatePercent;
		if (expectedFrames != outputFrames || expectedFrames > outputCapacity) return false;

		spans_[spanCount_++] = {sourceStartFrame, inputUnits, 0, loopStartFrame, loopEndFrame};
		for (int frame = 0; frame < outputFrames; ++frame) {
			normalize();
			if (spanCount_ == 0) return false;
			auto& span = spans_[0];
			if (span.offsetUnits < 0 || span.offsetUnits >= span.sourceLengthUnits) return false;
			const auto sourceOffset = span.offsetUnits / SOURCE_UNITS_PER_INPUT_FRAME;
			if (span.sourceStartFrame > std::numeric_limits<int64_t>::max() - sourceOffset) return false;
			auto sourceFrame = span.sourceStartFrame + sourceOffset;
			if (span.loopEndFrame > span.loopStartFrame && sourceFrame >= span.loopEndFrame) {
				const auto loopFrames = span.loopEndFrame - span.loopStartFrame;
				sourceFrame = span.loopStartFrame + (sourceFrame - span.loopStartFrame) % loopFrames;
			}
			outputSourceFrames[frame] = sourceFrame;
			span.offsetUnits += playbackRatePercent;
		}
		normalize();
		return true;
	}

	void normalize() {
		while (spanCount_ > 0 && spans_[0].offsetUnits >= spans_[0].sourceLengthUnits) {
			const int64_t remainderUnits = spans_[0].offsetUnits - spans_[0].sourceLengthUnits;
			for (int index = 1; index < spanCount_; ++index) spans_[index - 1] = spans_[index];
			--spanCount_;
			if (spanCount_ > 0) spans_[0].offsetUnits = remainderUnits;
		}
	}

	std::array<Span, MAX_PENDING_SPANS> spans_{};
	int spanCount_ = 0;
};
