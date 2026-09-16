#pragma once

#include <cmath>
#include "Biquad.h"

namespace av {

/**
 * Channel vocoder: the voice shapes a carrier instead of being heard directly.
 *
 * Sixteen log-spaced bands. Each band measures the energy of the voice and
 * applies it to the same band of the carrier, so the formants that carry the
 * words ride on top of whatever is playing. Two cascaded band-passes per band
 * rather than one: the steeper skirts are the difference between words and
 * mush.
 *
 * The carrier is either an internal saw pair tuned to the sung note, or the
 * backing track itself, which is the talkbox sound.
 *
 * Consonants have no pitch, so a band-passed carrier has nothing to shape for
 * them. The sibilance path fixes that by routing high-band energy through
 * noise; without it the vocoder swallows every S and T.
 */
class Vocoder {
public:
    void prepare(int sampleRate) {
        sr_ = sampleRate;
        for (int i = 0; i < kBands; ++i) {
            const float f = kLowHz * std::pow(kHighHz / kLowHz,
                                              (float) i / (float) (kBands - 1));
            modA_[i].bandPass(sr_, f, kQ);
            modB_[i].bandPass(sr_, f, kQ);
            carA_[i].bandPass(sr_, f, kQ);
            carB_[i].bandPass(sr_, f, kQ);
        }
        attack_ = timeCoef(2.0f, sr_);
        release_ = timeCoef(16.0f, sr_);
        sibilance_.highPass(sr_, 4500.0f, 0.707f);
        reset();
    }

    float mix = 0.0f;         // 0 = off, 1 = only the vocoded signal
    float carrierTrack = 0.0f; // 0 = internal synth, 1 = the backing track
    float sibilanceAmount = 0.6f;
    float spread = 0.15f;     // detune between the two saws, in semitones

    /** Note the synth carrier holds while the singer is on pitch. */
    void setFrequency(float hz) {
        if (hz > 40.0f && hz < 2000.0f) freq_ = hz;
    }

    /**
     * voice is the modulator, carrierIn the backing track sample. Returns the
     * wet signal; the caller blends it with the dry voice.
     */
    inline float process(float voice, float carrierIn) noexcept {
        if (mix <= 0.001f) return 0.0f;

        // Internal carrier: two saws a few cents apart beat against each other
        // so a held note does not sound like a test tone. The detune factor is
        // a constant between parameter changes, and exp2 is far too expensive
        // to evaluate once per sample for it.
        if (spread != cachedSpread_) {
            cachedSpread_ = spread;
            detune_ = std::exp2(spread / 12.0f);
        }
        const float step = freq_ / (float) sr_;
        phaseA_ += step;
        phaseB_ += step * detune_;
        if (phaseA_ >= 1.0f) phaseA_ -= 1.0f;
        if (phaseB_ >= 1.0f) phaseB_ -= 1.0f;
        const float synth = (phaseA_ * 2.0f - 1.0f) * 0.5f +
                            (phaseB_ * 2.0f - 1.0f) * 0.5f;

        const float carrier = synth * (1.0f - carrierTrack) + carrierIn * carrierTrack * 2.0f;

        float out = 0.0f;
        for (int i = 0; i < kBands; ++i) {
            const float m = modB_[i].process(modA_[i].process(voice));
            const float rect = std::fabs(m);
            env_[i] += (rect - env_[i]) * (rect > env_[i] ? attack_ : release_);

            const float c = carB_[i].process(carA_[i].process(carrier));
            out += c * env_[i] * kBandGain;
        }

        // Unvoiced sounds carry no pitch, so they need a noise carrier
        const float hiss = sibilance_.process(voice);
        noise_ = noise_ * 1103515245u + 12345u;
        const float white = (float) ((noise_ >> 9) & 0x7FFFFF) / 4194304.0f - 1.0f;
        hissEnv_ += (std::fabs(hiss) - hissEnv_) * attack_;
        out += white * hissEnv_ * sibilanceAmount * 2.0f;

        return out;
    }

    void reset() {
        for (int i = 0; i < kBands; ++i) {
            modA_[i].reset(); modB_[i].reset();
            carA_[i].reset(); carB_[i].reset();
            env_[i] = 0.0f;
        }
        sibilance_.reset();
        phaseA_ = 0.0f;
        phaseB_ = 0.37f;
        hissEnv_ = 0.0f;
        noise_ = 22222u;
    }

private:
    static constexpr int kBands = 16;
    static constexpr float kLowHz = 130.0f;
    static constexpr float kHighHz = 7000.0f;
    static constexpr float kQ = 4.5f;
    // Two band-passes in series lose level; this puts the bank back at unity
    static constexpr float kBandGain = 3.2f;

    int sr_ = 48000;
    Biquad modA_[kBands], modB_[kBands], carA_[kBands], carB_[kBands];
    Biquad sibilance_;
    float env_[kBands] = {0.0f};
    float attack_ = 0.1f, release_ = 0.02f;
    float freq_ = 110.0f, phaseA_ = 0.0f, phaseB_ = 0.0f;
    float cachedSpread_ = -1.0f, detune_ = 1.0f;
    float hissEnv_ = 0.0f;
    unsigned noise_ = 22222u;
};

}  // namespace av
