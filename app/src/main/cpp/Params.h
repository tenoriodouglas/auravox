#pragma once

#include <atomic>

namespace av {

/**
 * Parameter ids. Must stay in sync with Param.kt — the numbers are the
 * contract between the two sides, so ids are never reordered, only appended
 * inside the gaps each section reserves.
 */
enum ParamId {
    // --- vocal chain: 0..47 ---
    kBypass = 0,
    kInputGain,
    kVocalGain,
    kEqLow,
    kEqMid,
    kEqHigh,
    kGateThreshold,
    kCompThreshold,
    kCompRatio,
    kCompMakeup,
    kCompAttack,
    kCompRelease,
    kDeEss,
    kPitchAmount,
    kRetuneMs,
    kKeyRoot,
    kScale,
    kTranspose,
    kFormant,
    kHarmonyMix,
    kHarmony1,
    kHarmony2,
    kHarmony3,
    kHarmonyVoices,
    kHarmonySpread,
    kDoublerMix,
    kDoublerSpread,
    kDoublerDetune,
    kDelayMix,
    kDelayTimeMs,
    kDelayFeedback,
    kDelayPingPong,
    kReverbMix,
    kReverbSize,
    kReverbDamp,
    kReverbPreDelay,
    kVocalWidth,
    kVocoderMix,
    kVocoderCarrier,
    kVocoderSibilance,

    // --- backing track: 48..63 ---
    kTrackGain = 48,
    kTrackKeyShift,
    kTrackTempo,
    kTrackVocalRemove,
    kTrackDuck,
    kTrackWidth,
    kLoopStartMs,
    kLoopEndMs,
    kLoopEnabled,

    // --- master and transport: 64..79 ---
    kMasterGain = 64,
    kLatencyTrimMs,
    kMetronomeGain,
    kMonitorVoice,

    kParamCount = 80
};

/**
 * Lock-free parameter store shared by the UI and audio threads.
 *
 * Writers publish a value and raise a dirty flag; the audio thread clears the
 * flag and applies the value once per block. Each consumer owns a disjoint id
 * range, so two consumers never race for the same flag.
 */
class ParamStore {
public:
    ParamStore() {
        for (int i = 0; i < kParamCount; ++i) {
            values_[i].store(0.0f, std::memory_order_relaxed);
            dirty_[i].store(false, std::memory_order_relaxed);
        }
    }

    void set(int id, float v) {
        if (id < 0 || id >= kParamCount) return;
        values_[id].store(v, std::memory_order_relaxed);
        dirty_[id].store(true, std::memory_order_release);
    }

    float get(int id) const {
        if (id < 0 || id >= kParamCount) return 0.0f;
        return values_[id].load(std::memory_order_relaxed);
    }

    /** Audio thread. True once per change; clears the flag. */
    inline bool consume(int id, float &out) noexcept {
        if (!dirty_[id].exchange(false, std::memory_order_acquire)) return false;
        out = values_[id].load(std::memory_order_relaxed);
        return true;
    }

    /** Forces every id in [from, to) to be re-applied on the next block. */
    void touchRange(int from, int to) {
        for (int i = from; i < to && i < kParamCount; ++i) {
            dirty_[i].store(true, std::memory_order_release);
        }
    }

private:
    std::atomic<float> values_[kParamCount];
    std::atomic<bool> dirty_[kParamCount];
};

}  // namespace av
