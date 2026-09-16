#pragma once

#include <algorithm>
#include <atomic>
#include <vector>
#include "../Params.h"
#include "Dynamics.h"

namespace av {

/**
 * Sums the voice and the backing track into the monitor mix and, separately,
 * into the take being recorded.
 *
 * The two mixes are not the same signal. What the singer hears has to come out
 * now; what gets recorded has to line up with the track the singer was
 * actually reacting to, which was played `alignSamples` earlier. Recording the
 * monitor mix instead is why home karaoke takes come back with the vocal
 * dragging behind the beat.
 */
class Mixer {
public:
    void prepare(int sampleRate, float maxAlignMs = 600.0f) {
        sr_ = sampleRate;
        monitorDuck_.prepare(sampleRate);
        recordDuck_.prepare(sampleRate);
        monitorLimiter_.prepare(sampleRate);
        recordLimiter_.prepare(sampleRate);

        const int n = (int) (maxAlignMs * 0.001f * (float) sampleRate) + 4;
        delay_.assign((size_t) n * 2, 0.0f);
        delayFrames_ = n;
        reset();
    }

    static void applyDefaults(ParamStore &p) {
        p.set(kMasterGain, 1.0f);
        p.set(kLatencyTrimMs, 0.0f);
        p.set(kMetronomeGain, 0.6f);
        p.set(kMonitorVoice, 1.0f);
    }

    /**
     * How far the recorded track lags the monitor, in frames.
     *
     * Written from whichever thread last measured the latency, read by the
     * callback, hence the atomic.
     */
    void setAlignSamples(int n) {
        alignSamples_.store(std::min(std::max(n, 0), delayFrames_ - 1),
                            std::memory_order_relaxed);
    }

    int alignSamples() const { return alignSamples_.load(std::memory_order_relaxed); }

    /**
     * Audio thread. voice and track are interleaved stereo, click is mono and
     * may be null. monitor is written always; record may be null.
     */
    void process(ParamStore &params, const float *voice, const float *track,
                 const float *click, float *monitor, float *record,
                 int frames, float duckAmount) noexcept {
        float v;
        if (params.consume(kMasterGain, v)) master_ = v;
        if (params.consume(kMetronomeGain, v)) clickGain_ = v;
        if (params.consume(kMonitorVoice, v)) monitorVoice_ = v;
        monitorDuck_.amount = duckAmount;
        recordDuck_.amount = duckAmount;
        const int align = alignSamples_.load(std::memory_order_relaxed);

        for (int i = 0; i < frames; ++i) {
            const float vl = voice[i * 2], vr = voice[i * 2 + 1];
            const float tl = track[i * 2], tr = track[i * 2 + 1];
            const float sidechain = (vl + vr) * 0.5f;

            // The take always gets the full voice; only what reaches the
            // headphones is attenuated. Singing on a speaker means turning the
            // monitor off, not throwing the take away.
            const float dm = monitorDuck_.gainFor(sidechain);
            float ml = vl * monitorVoice_ + tl * dm;
            float mr = vr * monitorVoice_ + tr * dm;

            if (click) { ml += click[i] * clickGain_; mr += click[i] * clickGain_; }

            ml *= master_;
            mr *= master_;
            monitorLimiter_.process(ml, mr);
            monitor[i * 2] = ml;
            monitor[i * 2 + 1] = mr;

            // Push the live track into the delay and read back the slice the
            // singer was hearing when this block of voice was captured
            const int w = writeIdx_;
            delay_[(size_t) w * 2] = tl;
            delay_[(size_t) w * 2 + 1] = tr;
            int r = w - align;
            if (r < 0) r += delayFrames_;
            const float dl = delay_[(size_t) r * 2];
            const float dr = delay_[(size_t) r * 2 + 1];
            if (++writeIdx_ >= delayFrames_) writeIdx_ = 0;

            if (record) {
                const float dg = recordDuck_.gainFor(sidechain);
                float rl = (vl + dl * dg) * master_;
                float rr = (vr + dr * dg) * master_;
                recordLimiter_.process(rl, rr);
                record[i * 2] = rl;
                record[i * 2 + 1] = rr;
            }

            const float a = std::fmax(std::fabs(ml), std::fabs(mr));
            if (a > peak_) peak_ = a;
        }
        lastPeak_ = peak_;
        peak_ = 0.0f;
    }

    float peak() const { return lastPeak_; }

    void reset() {
        std::fill(delay_.begin(), delay_.end(), 0.0f);
        writeIdx_ = 0;
        peak_ = 0.0f;
        lastPeak_ = 0.0f;
        monitorDuck_.reset(); recordDuck_.reset();
        monitorLimiter_.reset(); recordLimiter_.reset();
    }

private:
    int sr_ = 48000;
    std::vector<float> delay_;
    int delayFrames_ = 0, writeIdx_ = 0;
    std::atomic<int> alignSamples_{0};
    float master_ = 1.0f, clickGain_ = 0.6f, monitorVoice_ = 1.0f;
    float peak_ = 0.0f, lastPeak_ = 0.0f;

    Ducker monitorDuck_, recordDuck_;
    Limiter monitorLimiter_, recordLimiter_;
};

}  // namespace av
