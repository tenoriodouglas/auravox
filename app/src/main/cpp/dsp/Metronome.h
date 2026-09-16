#pragma once

#include <atomic>
#include <cmath>
#include "Biquad.h"

namespace av {

/**
 * Count-in clicks, generated on the audio thread so the first downbeat lands
 * exactly where the track starts instead of a UI timer's best guess.
 */
class Metronome {
public:
    void prepare(int sampleRate) {
        sr_ = sampleRate;
        decay_ = std::exp(-1.0f / (0.035f * (float) sampleRate));
        reset();
    }

    float gain = 0.6f;

    /** Starts a count-in of `beats` clicks at `bpm`. */
    void start(float bpm, int beats) {
        if (bpm < 30.0f) bpm = 30.0f;
        periodSamples_ = (int) ((60.0f / bpm) * (float) sr_);
        remaining_.store(beats, std::memory_order_relaxed);
        counter_ = 0;
        beatIndex_ = 0;
        env_ = 0.0f;
        running_.store(true, std::memory_order_release);
        pendingClick_ = true;
    }

    void stop() { running_.store(false, std::memory_order_release); env_ = 0.0f; }
    bool running() const { return running_.load(std::memory_order_acquire); }
    int beatsLeft() const { return remaining_.load(std::memory_order_relaxed); }

    /** True on the block where the count-in just ended, exactly once. */
    bool consumeFinished() { return finished_.exchange(false, std::memory_order_acq_rel); }

    inline float process() noexcept {
        if (!running_.load(std::memory_order_relaxed) && env_ < 1e-5f) return 0.0f;

        if (pendingClick_) {
            env_ = 1.0f;
            // The first beat of the bar gets a higher click so the singer can
            // feel where the bar starts, not just that time is passing
            phaseStep_ = 2.0f * kPi * ((beatIndex_ % 4 == 0) ? 1600.0f : 1000.0f) / (float) sr_;
            phase_ = 0.0f;
            pendingClick_ = false;
        }

        const float out = std::sin(phase_) * env_ * gain;
        phase_ += phaseStep_;
        if (phase_ > 2.0f * kPi) phase_ -= 2.0f * kPi;
        env_ *= decay_;

        if (running_.load(std::memory_order_relaxed) && ++counter_ >= periodSamples_) {
            counter_ = 0;
            ++beatIndex_;
            const int left = remaining_.fetch_sub(1, std::memory_order_relaxed) - 1;
            if (left <= 0) {
                running_.store(false, std::memory_order_release);
                finished_.store(true, std::memory_order_release);
            } else {
                pendingClick_ = true;
            }
        }
        return out;
    }

    void reset() {
        running_.store(false, std::memory_order_relaxed);
        finished_.store(false, std::memory_order_relaxed);
        remaining_.store(0, std::memory_order_relaxed);
        counter_ = 0; beatIndex_ = 0; env_ = 0.0f; phase_ = 0.0f;
        pendingClick_ = false;
    }

private:
    int sr_ = 48000;
    int periodSamples_ = 24000;
    int counter_ = 0, beatIndex_ = 0;
    float env_ = 0.0f, decay_ = 0.99f, phase_ = 0.0f, phaseStep_ = 0.1f;
    bool pendingClick_ = false;
    std::atomic<bool> running_{false};
    std::atomic<bool> finished_{false};
    std::atomic<int> remaining_{0};
};

}  // namespace av
