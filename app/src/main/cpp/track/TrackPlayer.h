#pragma once

#include <atomic>
#include <cmath>
#include "../Params.h"
#include "../dsp/TimeScale.h"
#include "../dsp/VocalRemover.h"
#include "RingBuffer.h"

namespace av {

/**
 * Backing track playback: key shift, tempo, vocal removal and the playhead
 * everything else syncs to.
 *
 * The decoder thread pushes PCM into the ring; the audio thread pulls through
 * the stretcher. Nothing here allocates or locks once prepare() has run.
 */
class TrackPlayer {
public:
    void prepare(int deviceRate, int maxBlockFrames) {
        deviceRate_ = deviceRate;
        ring_.prepare(deviceRate * 6);  // 6 s of slack for decoder hiccups
        scale_.prepare(deviceRate);
        remover_.prepare(deviceRate);
        (void) maxBlockFrames;
        reset();
    }

    static void applyDefaults(ParamStore &p) {
        p.set(kTrackGain, 0.9f);
        p.set(kTrackKeyShift, 0.0f);
        p.set(kTrackTempo, 1.0f);
        p.set(kTrackVocalRemove, 0.0f);
        p.set(kTrackDuck, 0.0f);
        p.set(kTrackWidth, 1.0f);
        p.set(kLoopStartMs, 0.0f);
        p.set(kLoopEndMs, 0.0f);
        p.set(kLoopEnabled, 0.0f);
    }

    // --- decoder thread ---

    /** Announces a new source. Call while stopped. */
    void setSource(int trackRate, int64_t totalFrames) {
        trackRate_ = trackRate > 0 ? trackRate : deviceRate_;
        totalFrames_.store(totalFrames, std::memory_order_relaxed);
        rateDirty_.store(true, std::memory_order_release);
    }

    int pushPcm(const float *interleaved, int frames) {
        return ring_.push(interleaved, frames);
    }

    int ringSpace() const { return ring_.space(); }
    int ringAvailable() const { return ring_.available(); }

    void setEndOfStream(bool eof) { eof_.store(eof, std::memory_order_release); }

    // --- control thread ---

    /** Drops everything buffered and restarts the playhead at `frame`. */
    void seekTo(int64_t frame) {
        playing_.store(false, std::memory_order_release);
        flushPending_.store(true, std::memory_order_release);
        baseFrame_.store(frame, std::memory_order_release);
        finished_.store(false, std::memory_order_release);
    }

    void play() { playing_.store(true, std::memory_order_release); }
    void pause() { playing_.store(false, std::memory_order_release); }
    bool isPlaying() const { return playing_.load(std::memory_order_acquire); }
    bool finished() const { return finished_.load(std::memory_order_acquire); }

    void reset() {
        ring_.clear();
        scale_.reset();
        remover_.reset();
        playing_.store(false, std::memory_order_relaxed);
        finished_.store(false, std::memory_order_relaxed);
        eof_.store(false, std::memory_order_relaxed);
        loopWrap_.store(false, std::memory_order_relaxed);
        flushPending_.store(false, std::memory_order_relaxed);
        baseFrame_.store(0, std::memory_order_relaxed);
        positionFrames_.store(0.0, std::memory_order_relaxed);
        underruns_.store(0, std::memory_order_relaxed);
        loaded_.store(false, std::memory_order_relaxed);
    }

    void setLoaded(bool v) { loaded_.store(v, std::memory_order_release); }
    bool isLoaded() const { return loaded_.load(std::memory_order_acquire); }

    /** Playhead in source frames. Sample accurate, never drifts. */
    double positionFrames() const { return positionFrames_.load(std::memory_order_relaxed); }

    double positionMs() const {
        return positionFrames() * 1000.0 / (double) trackRate_;
    }

    int64_t totalFrames() const { return totalFrames_.load(std::memory_order_relaxed); }
    int trackRate() const { return trackRate_; }
    int underruns() const { return underruns_.load(std::memory_order_relaxed); }

    /**
     * True once the playhead has run past the loop end. Seeking needs the
     * decoder, which lives on the Kotlin side, so the audio thread raises the
     * flag and the transport polls it.
     */
    bool consumeLoopWrap() { return loopWrap_.exchange(false, std::memory_order_acq_rel); }
    float loopStartMs() const { return loopStart_; }

    /** Sidechain level the ducker reads, updated once per block. */
    float lastPeak() const { return peak_.load(std::memory_order_relaxed); }

    /** How hard the singer should push the track down. Owned here, used by the mixer. */
    float duckAmount() const { return duck_; }

    // --- audio thread ---

    /** Renders into an interleaved stereo buffer. Silence when not playing. */
    void render(ParamStore &params, float *outLR, int frames) noexcept {
        consume(params);

        if (flushPending_.exchange(false, std::memory_order_acquire)) {
            ring_.clear();
            scale_.reset();
            remover_.reset();
        }

        if (!playing_.load(std::memory_order_relaxed)) {
            std::fill(outLR, outLR + frames * 2, 0.0f);
            peak_.store(0.0f, std::memory_order_relaxed);
            return;
        }

        const int produced = scale_.render(ring_, outLR, frames);
        if (produced < frames) {
            if (eof_.load(std::memory_order_relaxed) && ring_.available() == 0) {
                finished_.store(true, std::memory_order_release);
                playing_.store(false, std::memory_order_release);
            } else {
                underruns_.fetch_add(1, std::memory_order_relaxed);
            }
        }

        float peak = 0.0f;
        for (int i = 0; i < frames; ++i) {
            float l = outLR[i * 2] * gain_;
            float r = outLR[i * 2 + 1] * gain_;
            remover_.process(l, r);
            if (width_ != 1.0f) applyWidth(l, r, width_);
            outLR[i * 2] = l;
            outLR[i * 2 + 1] = r;
            const float a = std::fmax(std::fabs(l), std::fabs(r));
            if (a > peak) peak = a;
        }
        peak_.store(peak, std::memory_order_relaxed);

        const double pos = (double) baseFrame_.load(std::memory_order_relaxed) +
                           scale_.sourcePosition();
        positionFrames_.store(pos, std::memory_order_relaxed);

        if (loopEnabled_ && loopEnd_ > loopStart_ + 200.0f) {
            const double ms = pos * 1000.0 / (double) trackRate_;
            if (ms >= (double) loopEnd_) {
                // Stop at the loop point so the bar after it is never heard;
                // the transport seeks back and starts again
                playing_.store(false, std::memory_order_release);
                loopWrap_.store(true, std::memory_order_release);
            }
        }
    }

private:
    void consume(ParamStore &p) noexcept {
        float v;
        bool ratesChanged = rateDirty_.exchange(false, std::memory_order_acquire);
        for (int id = kTrackGain; id <= kLoopEnabled; ++id) {
            if (!p.consume(id, v)) continue;
            switch (id) {
                case kTrackGain:        gain_ = v; break;
                case kTrackKeyShift:    keyShift_ = v; ratesChanged = true; break;
                case kTrackTempo:       tempo_ = v < 0.5f ? 0.5f : (v > 2.0f ? 2.0f : v); ratesChanged = true; break;
                case kTrackVocalRemove: remover_.amount = v; break;
                case kTrackDuck:        duck_ = v; break;
                case kTrackWidth:       width_ = v; break;
                case kLoopStartMs:      loopStart_ = v; break;
                case kLoopEndMs:        loopEnd_ = v; break;
                case kLoopEnabled:      loopEnabled_ = v > 0.5f; break;
                default: break;
            }
        }
        if (ratesChanged) updateRates();
    }

    /**
     * resampleRate carries the rate conversion and the pitch change;
     * consumeRate undoes the duration change the pitch move caused and applies
     * the tempo on top. See TimeScale for the derivation.
     */
    void updateRates() noexcept {
        const float pitch = std::exp2(keyShift_ / 12.0f);
        const float conv = (float) trackRate_ / (float) deviceRate_;
        scale_.resampleRate = conv * pitch;
        scale_.consumeRate = tempo_ / pitch;
    }

    SpscRing ring_;
    TimeScale scale_;
    VocalRemover remover_;

    int deviceRate_ = 48000;
    int trackRate_ = 48000;
    float gain_ = 0.9f, keyShift_ = 0.0f, tempo_ = 1.0f, width_ = 1.0f;
    float duck_ = 0.0f;
    float loopStart_ = 0.0f, loopEnd_ = 0.0f;
    bool loopEnabled_ = false;

    std::atomic<bool> playing_{false};
    std::atomic<bool> finished_{false};
    std::atomic<bool> eof_{false};
    std::atomic<bool> flushPending_{false};
    std::atomic<bool> rateDirty_{true};
    std::atomic<bool> loaded_{false};
    std::atomic<int64_t> baseFrame_{0};
    std::atomic<int64_t> totalFrames_{0};
    std::atomic<double> positionFrames_{0.0};
    std::atomic<float> peak_{0.0f};
    std::atomic<int> underruns_{0};
    std::atomic<bool> loopWrap_{false};
};

}  // namespace av
