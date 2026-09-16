#pragma once

#include "Biquad.h"

namespace av {

/** Downward expander with hysteresis, kills room noise between phrases. */
class NoiseGate {
public:
    void prepare(int sampleRate) {
        attack_ = timeCoef(3.0f, sampleRate);
        release_ = timeCoef(150.0f, sampleRate);
        envCoef_ = timeCoef(8.0f, sampleRate);
        reset();
    }

    void setThresholdDb(float db) {
        openLevel_ = dbToLin(db);
        closeLevel_ = dbToLin(db - 6.0f);  // hysteresis stops chattering
    }

    inline float process(float x) noexcept {
        const float rect = std::fabs(x);
        env_ += (rect - env_) * envCoef_;

        if (open_) {
            if (env_ < closeLevel_) open_ = false;
        } else {
            if (env_ > openLevel_) open_ = true;
        }

        const float target = open_ ? 1.0f : 0.0f;
        gain_ += (target - gain_) * (target > gain_ ? attack_ : release_);
        return x * gain_;
    }

    bool isOpen() const { return open_; }
    void reset() { env_ = 0.0f; gain_ = 0.0f; open_ = false; }

private:
    float env_ = 0.0f, gain_ = 0.0f;
    float attack_ = 0.1f, release_ = 0.01f, envCoef_ = 0.1f;
    float openLevel_ = 0.003f, closeLevel_ = 0.0015f;
    bool open_ = false;
};

/** Feed-forward compressor, soft knee, with makeup gain. */
class Compressor {
public:
    void prepare(int sampleRate) {
        sr_ = sampleRate;
        setAttackMs(8.0f);
        setReleaseMs(120.0f);
        reset();
    }

    void setAttackMs(float ms) { attack_ = timeCoef(ms, sr_); }
    void setReleaseMs(float ms) { release_ = timeCoef(ms, sr_); }

    float thresholdDb = -18.0f;
    float ratio = 3.0f;
    float makeupDb = 4.0f;
    float kneeDb = 6.0f;

    inline float process(float x) noexcept {
        const float rect = std::fabs(x);
        env_ += (rect - env_) * (rect > env_ ? attack_ : release_);

        const float levelDb = linToDb(env_);
        const float over = levelDb - thresholdDb;

        float grDb;
        if (over <= -kneeDb * 0.5f) {
            grDb = 0.0f;
        } else if (over >= kneeDb * 0.5f) {
            grDb = -over * (1.0f - 1.0f / ratio);
        } else {
            // Quadratic interpolation across the knee
            const float t = over + kneeDb * 0.5f;
            grDb = -(1.0f - 1.0f / ratio) * t * t / (2.0f * kneeDb);
        }
        gainReduction_ = grDb;
        return x * dbToLin(grDb + makeupDb);
    }

    float gainReductionDb() const { return gainReduction_; }
    void reset() { env_ = 0.0f; gainReduction_ = 0.0f; }

private:
    int sr_ = 48000;
    float env_ = 0.0f, attack_ = 0.1f, release_ = 0.01f;
    float gainReduction_ = 0.0f;
};

/** Band-split de-esser: detects sibilance, ducks only the high band. */
class DeEsser {
public:
    void prepare(int sampleRate) {
        split_.prepare(sampleRate, 5500.0f);
        attack_ = timeCoef(1.5f, sampleRate);
        release_ = timeCoef(40.0f, sampleRate);
        reset();
    }

    float amount = 0.0f;  // 0..1

    inline float process(float x) noexcept {
        if (amount <= 0.001f) return x;

        float low, high;
        split_.process(x, low, high);

        const float rect = std::fabs(high);
        env_ += (rect - env_) * (rect > env_ ? attack_ : release_);

        // Duck the high band only, so the body of the voice stays intact
        float gr = 1.0f - env_ * amount * 8.0f;
        if (gr < 0.15f) gr = 0.15f;
        return low + high * gr;
    }

    void reset() { split_.reset(); env_ = 0.0f; }

private:
    LR4 split_;
    float env_ = 0.0f, attack_ = 0.2f, release_ = 0.01f;
};

/** Soft limiter on the output bus, shared gain across both channels. */
class Limiter {
public:
    void prepare(int sampleRate) {
        release_ = timeCoef(60.0f, sampleRate);
        attack_ = timeCoef(0.5f, sampleRate);
        reset();
    }

    float ceiling = 0.95f;

    inline void process(float &l, float &r) noexcept {
        const float rect = std::fmax(std::fabs(l), std::fabs(r));
        env_ += (rect - env_) * (rect > env_ ? attack_ : release_);

        float g = 1.0f;
        if (env_ > ceiling) g = ceiling / env_;
        gain_ += (g - gain_) * (g < gain_ ? attack_ : release_);

        l = clampf(l * gain_, -ceiling, ceiling);
        r = clampf(r * gain_, -ceiling, ceiling);
    }

    float gain() const { return gain_; }
    void reset() { env_ = 0.0f; gain_ = 1.0f; }

private:
    float env_ = 0.0f, gain_ = 1.0f, attack_ = 0.5f, release_ = 0.01f;
};

/**
 * Sidechain ducker: pulls the backing track down while the singer is on mic.
 *
 * Voloco has no equivalent; on a phone speaker it is the difference between
 * hearing the words and hearing the mix.
 */
class Ducker {
public:
    void prepare(int sampleRate) {
        attack_ = timeCoef(12.0f, sampleRate);
        release_ = timeCoef(320.0f, sampleRate);
        reset();
    }

    float amount = 0.0f;         // 0 = off, 1 = up to -12 dB
    float thresholdDb = -32.0f;

    /** Returns the gain the track should take for this sample. */
    inline float gainFor(float sidechain) noexcept {
        const float rect = std::fabs(sidechain);
        env_ += (rect - env_) * (rect > env_ ? attack_ : release_);
        if (amount <= 0.001f) return 1.0f;

        const float over = linToDb(env_) - thresholdDb;
        if (over <= 0.0f) return 1.0f;
        const float duckDb = -clampf(over, 0.0f, 12.0f) * amount;
        return dbToLin(duckDb);
    }

    void reset() { env_ = 0.0f; }

private:
    float env_ = 0.0f, attack_ = 0.1f, release_ = 0.01f;
};

/** 4-band vocal EQ: rumble cut, low shelf, presence peak, air shelf. */
class VoiceEq {
public:
    void prepare(int sampleRate) {
        sr_ = sampleRate;
        hp_.highPass(sr_, 80.0f, 0.707f);
        setLow(0.0f); setMid(0.0f); setHigh(0.0f);
    }

    void setLow(float db) { low_.lowShelf(sr_, 200.0f, db); }
    void setMid(float db) { mid_.peaking(sr_, 3000.0f, 1.0f, db); }
    void setHigh(float db) { high_.highShelf(sr_, 8000.0f, db); }

    inline float process(float x) noexcept {
        return high_.process(mid_.process(low_.process(hp_.process(x))));
    }

    void reset() { hp_.reset(); low_.reset(); mid_.reset(); high_.reset(); }

private:
    int sr_ = 48000;
    Biquad hp_, low_, mid_, high_;
};

}  // namespace av
