#pragma once

#include <cmath>
#include "PitchDetector.h"
#include "PsolaShifter.h"
#include "Scale.h"

namespace av {

/**
 * Pitch tracking, scale-snapped autotune and three harmony voices.
 *
 * One YIN instance drives every shifter, so the detector cost is paid once no
 * matter how many voices are active. Harmony voices only run when their bus is
 * open, which keeps the idle cost at one shifter.
 */
class VoiceProcessor {
public:
    void prepare(int sampleRate) {
        sr_ = sampleRate;
        detector_.prepare(sampleRate);
        lead_.prepare(sampleRate);
        for (auto &h : harm_) h.prepare(sampleRate);
        setRetuneMs(40.0f);
        reset();
    }

    // --- parameters, written from the UI thread ---
    float amount = 0.0f;        // 0 = off, 1 = full snap
    float formant = 1.0f;       // 1 = natural
    int keyRoot = 0;            // 0 = C
    ScaleType scale = ScaleType::Chromatic;
    int transpose = 0;          // semitones, after snapping
    float harmonyMix = 0.0f;    // level of the harmony bus
    int harmonyDegrees[3] = {2, 4, -3};  // scale steps relative to the lead
    int harmonyVoices = 2;      // 1..3

    /**
     * Note the song's melody is on right now, or negative when there is none.
     *
     * Snapping to a scale only ever moves the voice to the nearest note in it,
     * which on a chromatic scale is at most fifty cents and on any scale can
     * still be the wrong note of the chord. The melody guide says which note
     * the singer was reaching for, so the correction lands on that one.
     */
    float guideMidi = -1.0f;
    bool useGuide = false;

    void setRetuneMs(float ms) {
        if (ms < 1.0f) ms = 1.0f;
        smoothCoef_ = timeCoef(ms, sr_);
    }

    /** Returns the lead voice; writes the harmony bus into harmonyOut[3]. */
    inline float process(float x, float *harmonyOut) noexcept {
        if (detector_.push(x)) updateTargets();

        leadRatio_ += (targetLead_ - leadRatio_) * smoothCoef_;

        lead_.pitchRatio = leadRatio_;
        lead_.formantRatio = formant;
        const float out = lead_.process(x);

        if (harmonyMix > 0.001f) {
            const int n = harmonyVoices < 1 ? 1 : (harmonyVoices > 3 ? 3 : harmonyVoices);
            // A small formant offset per voice keeps the stack from sounding
            // like one singer multiplied
            static const float kFormantTrim[3] = {1.04f, 0.97f, 1.09f};
            for (int i = 0; i < 3; ++i) {
                if (i >= n) { harmonyOut[i] = 0.0f; continue; }
                hRatio_[i] += (targetH_[i] - hRatio_[i]) * smoothCoef_;
                harm_[i].pitchRatio = hRatio_[i];
                harm_[i].formantRatio = formant * kFormantTrim[i];
                harmonyOut[i] = harm_[i].process(x) * harmonyMix;
            }
        } else {
            harmonyOut[0] = harmonyOut[1] = harmonyOut[2] = 0.0f;
        }
        return out;
    }

    float detectedHz() const { return detector_.frequency(); }
    float detectedMidi() const { return detector_.midi(); }
    float confidence() const { return detector_.confidence(); }
    bool voiced() const { return detector_.voiced(); }
    int currentNote() const { return currentNote_; }
    /** Cents between the sung pitch and the note it is closest to. */
    float centsOff() const { return centsOff_; }

    void reset() {
        detector_.reset(); lead_.reset();
        for (auto &h : harm_) h.reset();
        leadRatio_ = targetLead_ = 1.0f;
        for (int i = 0; i < 3; ++i) { hRatio_[i] = 1.0f; targetH_[i] = 1.0f; }
        currentNote_ = -1;
        centsOff_ = 0.0f;
    }

private:
    /**
     * Where the voice should land.
     *
     * The guide note is folded into whatever octave the singer is actually in,
     * so a tenor singing the melody an octave down is corrected to his own
     * octave rather than dragged up. Past three semitones the singer is on a
     * different note than the guide expects, and pulling them there would be
     * worse than leaving them alone, so the scale takes over.
     */
    float resolveTarget(float midi) const {
        const float scaleSnap = snapToScale(midi, keyRoot, scale);
        if (!useGuide || guideMidi <= 0.0f) return scaleSnap;

        const float folded = guideMidi + 12.0f * std::round((midi - guideMidi) / 12.0f);
        return std::fabs(midi - folded) <= 3.0f ? folded : scaleSnap;
    }

    void updateTargets() {
        if (!detector_.voiced()) {
            lead_.setPeriod(0.0f);
            for (auto &h : harm_) h.setPeriod(0.0f);
            targetLead_ = 1.0f;
            for (int i = 0; i < 3; ++i) targetH_[i] = 1.0f;
            currentNote_ = -1;
            return;
        }

        const float f0 = detector_.frequency();
        const float period = (float) sr_ / f0;
        lead_.setPeriod(period);
        for (auto &h : harm_) h.setPeriod(period);

        const float midi = hzToMidi(f0);
        const float snapped = resolveTarget(midi);
        currentNote_ = ((int) std::lround(snapped) % 12 + 12) % 12;
        centsOff_ = (midi - std::round(midi)) * 100.0f;

        // Blend between the sung pitch and the snapped pitch
        const float leadTarget = midi + (snapped + transpose - midi) * amount;
        targetLead_ = std::exp2((leadTarget - midi) / 12.0f);

        // Harmonies follow the scale, so every interval stays diatonic
        for (int i = 0; i < 3; ++i) {
            const float h = stepInScale(snapped, harmonyDegrees[i], keyRoot, scale) + transpose;
            targetH_[i] = std::exp2((h - midi) / 12.0f);
        }
    }

    int sr_ = 48000;
    PitchDetector detector_;
    PsolaShifter lead_, harm_[3];

    float smoothCoef_ = 0.01f;
    float leadRatio_ = 1.0f, targetLead_ = 1.0f;
    float hRatio_[3] = {1.0f, 1.0f, 1.0f};
    float targetH_[3] = {1.0f, 1.0f, 1.0f};
    int currentNote_ = -1;
    float centsOff_ = 0.0f;
};

}  // namespace av
