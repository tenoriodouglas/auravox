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
 *
 * Three things decide whether a correction sounds professional, and all of
 * them are about when the pitch moves rather than where it lands:
 *
 *  - A committed note. Snapping to whatever is nearest every frame makes a
 *    voice sitting between two notes flip back and forth, which is heard as a
 *    warble. Once a note is chosen it is held until the voice is clearly
 *    closer to another one.
 *  - A fast attack. A singer reaches a new note from below or above, and the
 *    approach is the part that sounds out of tune. Correcting hard for the
 *    first few milliseconds and then at the chosen rate fixes the entry
 *    without freezing the sustain.
 *  - Vibrato that survives. Correction aims at the running average of the
 *    sung pitch rather than its instant value, so the singer's own vibrato
 *    passes through while the note it sits on is pulled into tune. A separate
 *    generator adds vibrato to a voice that has none.
 */
class VoiceProcessor {
public:
    void prepare(int sampleRate) {
        sr_ = sampleRate;
        detector_.prepare(sampleRate);
        lead_.prepare(sampleRate);
        for (auto &h : harm_) h.prepare(sampleRate);
        setRetuneMs(40.0f);
        setOnsetMs(8.0f);
        // Average slow enough to pass a 4-7 Hz vibrato and fast enough to
        // follow a melody: a 1.2 Hz corner at the detector's frame rate
        const float frameRate = (float) sampleRate / (float) detector_.hopSamples();
        avgCoef_ = 1.0f - std::exp(-2.0f * (float) M_PI * 1.2f / frameRate);
        // Upper edge of the vibrato band: passes 5 Hz, holds back the scatter
        wobbleCoef_ = 1.0f - std::exp(-2.0f * (float) M_PI * 8.0f / frameRate);
        setVibrato(0.0f, 5.0f);
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

    /** Retune time for the first moments of a new note. */
    void setOnsetMs(float ms) {
        if (ms < 0.5f) ms = 0.5f;
        onsetCoef_ = timeCoef(ms, sr_);
        onsetLen_ = (int) (ms * 0.003f * (float) sr_);  // hold it for ~3 time constants
    }

    /**
     * Added vibrato, as a table of ratio multipliers over one cycle.
     *
     * Built here rather than in process() because an exp2 per sample for a
     * value that only depends on the phase is the kind of cost that shows up
     * as an underrun on a slow phone.
     */
    void setVibrato(float depthSemis, float rateHz) {
        vibDepth_ = depthSemis < 0.0f ? 0.0f : (depthSemis > 2.0f ? 2.0f : depthSemis);
        vibRate_ = rateHz < 0.1f ? 0.1f : (rateHz > 12.0f ? 12.0f : rateHz);
        vibInc_ = (float) kVibTable * vibRate_ / (float) sr_;
        for (int i = 0; i < kVibTable; ++i) {
            const float s = std::sin(2.0f * (float) M_PI * (float) i / (float) kVibTable);
            vibTable_[i] = std::exp2(vibDepth_ * s / 12.0f);
        }
        vibOn_ = vibDepth_ > 0.001f;
    }

    /** Returns the lead voice; writes the harmony bus into harmonyOut[3]. */
    inline float process(float x, float *harmonyOut) noexcept {
        if (detector_.push(x)) updateTargets();

        // The attack window uses the fast coefficient, then it decays to the
        // rate the user asked for
        float coef = smoothCoef_;
        if (onsetLeft_ > 0) {
            --onsetLeft_;
            if (onsetCoef_ > coef) coef = onsetCoef_;
        }

        float vib = 1.0f;
        if (vibOn_) {
            vibPhase_ += vibInc_;
            while (vibPhase_ >= (float) kVibTable) vibPhase_ -= (float) kVibTable;
            vib = vibTable_[(int) vibPhase_];
        }

        leadRatio_ += (targetLead_ - leadRatio_) * coef;
        lead_.pitchRatio = leadRatio_ * vib;
        lead_.formantRatio = formant;
        const float out = lead_.process(x);

        if (harmonyMix > 0.001f) {
            const int n = harmonyVoices < 1 ? 1 : (harmonyVoices > 3 ? 3 : harmonyVoices);
            // A small formant offset per voice keeps the stack from sounding
            // like one singer multiplied
            static const float kFormantTrim[3] = {1.04f, 0.97f, 1.09f};
            for (int i = 0; i < 3; ++i) {
                if (i >= n) { harmonyOut[i] = 0.0f; continue; }
                hRatio_[i] += (targetH_[i] - hRatio_[i]) * coef;
                harm_[i].pitchRatio = hRatio_[i] * vib;
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
    /** Note the correction is currently aiming at, or -1. */
    float committedMidi() const { return committed_; }
    /** Semitones the lead is currently being moved by. */
    float correctionSemis() const { return 12.0f * std::log2(leadRatio_); }

    void reset() {
        detector_.reset(); lead_.reset();
        for (auto &h : harm_) h.reset();
        currentNote_ = -1;
        centsOff_ = 0.0f;
        committed_ = -1.0f;
        avgMidi_ = -1.0f;
        wobbleSmooth_ = 0.0f;
        onsetLeft_ = 0;
        vibPhase_ = 0.0f;
        leadRatio_ = targetLead_ = 1.0f;
        for (int i = 0; i < 3; ++i) { hRatio_[i] = 1.0f; targetH_[i] = 1.0f; }
    }

private:
    /**
     * Note to correct to, held until the voice clearly belongs to another.
     *
     * The guide note is folded into whatever octave the singer is actually in,
     * so a tenor singing the melody an octave down is corrected to his own
     * octave rather than dragged up. Past three semitones the singer is on a
     * different note than the guide expects, and pulling them there would be
     * worse than leaving them alone, so the scale takes over.
     */
    float candidateFor(float midi) const {
        const float scaleSnap = snapToScale(midi, keyRoot, scale);
        if (!useGuide || guideMidi <= 0.0f) return scaleSnap;

        const float folded = guideMidi + 12.0f * std::round((midi - guideMidi) / 12.0f);
        return std::fabs(midi - folded) <= 3.0f ? folded : scaleSnap;
    }

    /** True when the note changed, so the caller can open the attack window. */
    bool commit(float midi) {
        const float cand = candidateFor(midi);
        if (committed_ < 0.0f) { committed_ = cand; return true; }
        if (std::fabs(cand - committed_) < 0.01f) return false;

        // Switch only once the voice is meaningfully closer to the new note.
        // Without this margin a pitch sitting on the midpoint alternates every
        // frame and the correction warbles a semitone wide.
        const float toCand = std::fabs(midi - cand);
        const float toHeld = std::fabs(midi - committed_);
        if (toHeld - toCand < kHysteresis) return false;

        committed_ = cand;
        return true;
    }

    void updateTargets() {
        if (!detector_.voiced()) {
            lead_.setPeriod(0.0f);
            for (auto &h : harm_) h.setPeriod(0.0f);
            currentNote_ = -1;
            // A breath ends the note, so the next one attacks from scratch
            committed_ = -1.0f;
            avgMidi_ = -1.0f;
            wobbleSmooth_ = 0.0f;
            targetLead_ = 1.0f;
            for (int i = 0; i < 3; ++i) targetH_[i] = 1.0f;
            return;
        }

        const float f0 = detector_.frequency();
        const float period = (float) sr_ / f0;
        lead_.setPeriod(period);
        for (auto &h : harm_) h.setPeriod(period);

        const float midi = hzToMidi(f0);
        if (commit(midi)) {
            onsetLeft_ = onsetLen_;
            // A new note carries no deviation yet. Leaving the old average in
            // place would read the whole interval as wobble and add it on top
            // of the note being moved to.
            avgMidi_ = midi;
            wobbleSmooth_ = 0.0f;
        }
        const float snapped = committed_;
        currentNote_ = ((int) std::lround(snapped) % 12 + 12) % 12;
        centsOff_ = (midi - std::round(midi)) * 100.0f;

        /*
         * Correct the centre of the note and move the singer's own modulation
         * onto it untouched.
         *
         * Aiming at the instant pitch instead would try to hold the voice
         * still, which does not work. On a steady note the shifter lands on
         * the requested pitch to within a cent, but holding a moving voice
         * still means laying grains of a changing waveform at a fixed spacing,
         * and the result is not periodic: a tracker reading it back hears a
         * blend of the spacing and the wobble inside the grains. Measured on a
         * 4 Hz vibrato, aiming at the instant pitch removes about half the
         * swing when handed the true instantaneous period and none of it when
         * handed a period one detector frame old, so there is nothing to be
         * gained by trying.
         *
         * Separating the two is also the better instrument. A singer's vibrato
         * is the part worth keeping; what wants fixing is the note it sits on.
         */
        if (avgMidi_ < 0.0f) avgMidi_ = midi;
        avgMidi_ += (midi - avgMidi_) * avgCoef_;

        /*
         * Wobble is what is left after the centre is taken out, band-limited
         * so that it is vibrato and nothing else.
         *
         * The slow average removes the note; this second smoother removes the
         * detector's own frame-to-frame scatter, and the deadband removes what
         * survives it. Both matter: the wobble is added to the target, so any
         * scatter in it lands on the synthesis spacing, and a spacing that
         * rattles against the period is precisely what breeds a subharmonic.
         * Letting a few cents of measurement noise through doubled the ghost
         * an octave below on the shifts where the two are already closest to
         * beating, a fifth down and an octave up.
         */
        float wobble = midi - avgMidi_;
        if (wobble > kMaxWobble) wobble = kMaxWobble;
        else if (wobble < -kMaxWobble) wobble = -kMaxWobble;
        wobbleSmooth_ += (wobble - wobbleSmooth_) * wobbleCoef_;
        wobble = std::fabs(wobbleSmooth_) > kWobbleFloor ? wobbleSmooth_ : 0.0f;
        const float centre = avgMidi_ + (snapped + (float) transpose - avgMidi_) * amount;
        const float target = centre + wobble;
        targetLead_ = std::exp2((target - midi) / 12.0f);

        // Harmonies follow the scale, so every interval stays diatonic
        for (int i = 0; i < 3; ++i) {
            const float h = stepInScale(snapped, harmonyDegrees[i], keyRoot, scale)
                          + (float) transpose + wobble;
            targetH_[i] = std::exp2((h - midi) / 12.0f);
        }
    }

    static constexpr float kHysteresis = 0.30f;  // semitones of advantage to switch note
    static constexpr int kVibTable = 256;
    static constexpr float kMaxWobble = 1.0f;   // semitones of vibrato the target follows
    static constexpr float kWobbleFloor = 0.04f;  // below this it is measurement noise

    int sr_ = 48000;
    PitchDetector detector_;
    PsolaShifter lead_, harm_[3];

    float smoothCoef_ = 0.01f;
    float onsetCoef_ = 0.2f;
    int onsetLen_ = 0, onsetLeft_ = 0;
    float leadRatio_ = 1.0f, targetLead_ = 1.0f;
    float hRatio_[3] = {1.0f, 1.0f, 1.0f};
    float targetH_[3] = {1.0f, 1.0f, 1.0f};
    int currentNote_ = -1;
    float centsOff_ = 0.0f;
    float committed_ = -1.0f;
    float avgMidi_ = -1.0f, avgCoef_ = 0.15f;
    float wobbleSmooth_ = 0.0f, wobbleCoef_ = 0.65f;

    bool vibOn_ = false;
    float vibDepth_ = 0.0f, vibRate_ = 5.0f, vibInc_ = 0.0f, vibPhase_ = 0.0f;
    float vibTable_[kVibTable] = {};
};

}  // namespace av
