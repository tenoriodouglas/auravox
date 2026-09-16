#pragma once

#include <atomic>
#include "../Params.h"
#include "Dynamics.h"
#include "Spatial.h"
#include "VoiceProcessor.h"
#include "Vocoder.h"

namespace av {

/**
 * The vocal chain: mono mic in, stereo voice bus out.
 *
 * Parameters arrive from the UI thread as atomics and are applied once per
 * block, never per sample. That keeps the inner loop free of atomic loads
 * while still responding within one buffer, about 4 ms.
 */
class FxChain {
public:
    void prepare(int sampleRate) {
        sr_ = sampleRate;
        gate_.prepare(sampleRate);
        eq_.prepare(sampleRate);
        deEsser_.prepare(sampleRate);
        voice_.prepare(sampleRate);
        comp_.prepare(sampleRate);
        doubler_.prepare(sampleRate);
        vocoder_.prepare(sampleRate);
        delay_.prepare(sampleRate);
        reverb_.prepare(sampleRate);
        reset();
    }

    static void applyDefaults(ParamStore &p) {
        p.set(kInputGain, 1.0f);
        p.set(kVocalGain, 1.0f);
        p.set(kEqLow, 0.0f);
        p.set(kEqMid, 2.0f);
        p.set(kEqHigh, 2.0f);
        p.set(kGateThreshold, -50.0f);
        p.set(kCompThreshold, -18.0f);
        p.set(kCompRatio, 3.0f);
        p.set(kCompMakeup, 4.0f);
        p.set(kCompAttack, 8.0f);
        p.set(kCompRelease, 120.0f);
        p.set(kDeEss, 0.3f);
        p.set(kPitchAmount, 0.0f);
        p.set(kRetuneMs, 40.0f);
        p.set(kKeyRoot, 0.0f);
        p.set(kScale, 0.0f);
        p.set(kTranspose, 0.0f);
        p.set(kFormant, 1.0f);
        p.set(kHarmonyMix, 0.0f);
        p.set(kHarmony1, 2.0f);
        p.set(kHarmony2, 4.0f);
        p.set(kHarmony3, -3.0f);
        p.set(kHarmonyVoices, 2.0f);
        p.set(kHarmonySpread, 0.8f);
        p.set(kDoublerMix, 0.0f);
        p.set(kDoublerSpread, 1.0f);
        p.set(kDoublerDetune, 0.5f);
        p.set(kDelayMix, 0.0f);
        p.set(kDelayTimeMs, 250.0f);
        p.set(kDelayFeedback, 0.3f);
        p.set(kDelayPingPong, 1.0f);
        p.set(kReverbMix, 0.15f);
        p.set(kReverbSize, 0.7f);
        p.set(kReverbDamp, 0.4f);
        p.set(kReverbPreDelay, 20.0f);
        p.set(kVocalWidth, 1.0f);
        p.set(kVocoderMix, 0.0f);
        p.set(kVocoderCarrier, 0.0f);
        p.set(kVocoderSibilance, 0.6f);
        p.set(kPitchGuide, 1.0f);
    }

    /**
     * Audio thread. Mono in, interleaved stereo out.
     *
     * carrierLR is the backing track, already rendered. The vocoder can use it
     * as its carrier, which is the talkbox sound; pass null when there is no
     * track and it falls back to the internal synth.
     */
    void process(ParamStore &params, const float *in, const float *carrierLR,
                 float *outLR, int frames) noexcept {
        consume(params);

        // Hoisted: the detector only produces a new estimate every 21 ms, so
        // pushing it per sample is a call and a branch for nothing
        vocoder_.setFrequency(voice_.detectedHz());

        float peak = 0.0f;
        for (int i = 0; i < frames; ++i) {
            float s = in[i] * inputGain_;

            if (bypass_) {
                outLR[i * 2] = s;
                outLR[i * 2 + 1] = s;
                const float ab = std::fabs(s);
                if (ab > peak) peak = ab;
                continue;
            }

            s = gate_.process(s);
            s = eq_.process(s);
            s = deEsser_.process(s);

            float harm[3];
            float lead = voice_.process(s, harm);
            lead = comp_.process(lead);

            if (vocoder_.mix > 0.001f) {
                const float carrier = carrierLR
                    ? (carrierLR[i * 2] + carrierLR[i * 2 + 1]) * 0.5f : 0.0f;
                const float wet = vocoder_.process(lead, carrier);
                lead = lead * (1.0f - vocoder_.mix) + wet * vocoder_.mix;
            }

            float l = lead, r = lead;

            // Harmonies sit off to the sides so the lead keeps the centre
            float send = lead;
            for (int v = 0; v < 3; ++v) {
                if (harm[v] == 0.0f) continue;
                const float pan = kHarmonyPan[v] * harmonySpread_;
                const float gl = std::sqrt(0.5f * (1.0f - pan));
                const float gr = std::sqrt(0.5f * (1.0f + pan));
                l += harm[v] * gl;
                r += harm[v] * gr;
                send += harm[v] * 0.5f;
            }

            doubler_.process(lead, l, r);
            delay_.process(send, l, r);
            reverb_.process(send, l, r);

            if (width_ != 1.0f) applyWidth(l, r, width_);

            l *= vocalGain_;
            r *= vocalGain_;

            outLR[i * 2] = l;
            outLR[i * 2 + 1] = r;

            const float ab = std::fmax(std::fabs(l), std::fabs(r));
            if (ab > peak) peak = ab;
        }

        peakLevel_.store(peak, std::memory_order_relaxed);
        pitchHz_.store(voice_.detectedHz(), std::memory_order_relaxed);
        pitchMidi_.store(voice_.detectedMidi(), std::memory_order_relaxed);
        confidence_.store(voice_.confidence(), std::memory_order_relaxed);
        note_.store(voice_.currentNote(), std::memory_order_relaxed);
        centsOff_.store(voice_.centsOff(), std::memory_order_relaxed);
        gainReduction_.store(comp_.gainReductionDb(), std::memory_order_relaxed);
    }

    void reset() {
        gate_.reset(); eq_.reset(); deEsser_.reset(); voice_.reset();
        comp_.reset(); doubler_.reset(); vocoder_.reset();
        delay_.reset(); reverb_.reset();
    }

    /** Extra latency the PSOLA path adds, in samples. Zero when it is bypassed. */
    int lookaheadSamples() const { return voiceActive_ ? psolaLatency_ : 0; }

    float peakLevel() const { return peakLevel_.load(std::memory_order_relaxed); }
    float pitchHz() const { return pitchHz_.load(std::memory_order_relaxed); }
    float pitchMidi() const { return pitchMidi_.load(std::memory_order_relaxed); }
    float confidence() const { return confidence_.load(std::memory_order_relaxed); }
    int note() const { return note_.load(std::memory_order_relaxed); }
    float centsOff() const { return centsOff_.load(std::memory_order_relaxed); }
    float gainReductionDb() const { return gainReduction_.load(std::memory_order_relaxed); }

    VoiceProcessor &voice() { return voice_; }

    /** Melody note the song is on, fed in once per block. Negative when none. */
    void setGuideMidi(float midi) { voice_.guideMidi = midi; }

private:
    static constexpr float kHarmonyPan[3] = {-1.0f, 1.0f, -0.35f};

    void consume(ParamStore &p) noexcept {
        float v;
        for (int id = kBypass; id <= kPitchGuide; ++id) {
            if (!p.consume(id, v)) continue;
            switch (id) {
                case kBypass:         bypass_ = v > 0.5f; break;
                case kInputGain:      inputGain_ = v; break;
                case kVocalGain:      vocalGain_ = v; break;
                case kEqLow:          eq_.setLow(v); break;
                case kEqMid:          eq_.setMid(v); break;
                case kEqHigh:         eq_.setHigh(v); break;
                case kGateThreshold:  gate_.setThresholdDb(v); break;
                case kCompThreshold:  comp_.thresholdDb = v; break;
                case kCompRatio:      comp_.ratio = v; break;
                case kCompMakeup:     comp_.makeupDb = v; break;
                case kCompAttack:     comp_.setAttackMs(v); break;
                case kCompRelease:    comp_.setReleaseMs(v); break;
                case kDeEss:          deEsser_.amount = v; break;
                case kPitchAmount:    voice_.amount = v; break;
                case kRetuneMs:       voice_.setRetuneMs(v); break;
                case kKeyRoot:        voice_.keyRoot = (int) v; break;
                case kScale:          voice_.scale = (ScaleType) clampInt((int) v, 0, (int) ScaleType::Count - 1); break;
                case kTranspose:      voice_.transpose = (int) v; break;
                case kFormant:        voice_.formant = v; break;
                case kHarmonyMix:     voice_.harmonyMix = v; break;
                case kHarmony1:       voice_.harmonyDegrees[0] = (int) v; break;
                case kHarmony2:       voice_.harmonyDegrees[1] = (int) v; break;
                case kHarmony3:       voice_.harmonyDegrees[2] = (int) v; break;
                case kHarmonyVoices:  voice_.harmonyVoices = clampInt((int) v, 1, 3); break;
                case kHarmonySpread:  harmonySpread_ = v; break;
                case kDoublerMix:     doubler_.mix = v; break;
                case kDoublerSpread:  doubler_.spread = v; break;
                case kDoublerDetune:  doubler_.detune = v; break;
                case kDelayMix:       delay_.mix = v; break;
                case kDelayTimeMs:    delay_.setTimeMs(v); break;
                case kDelayFeedback:  delay_.feedback = v; break;
                case kDelayPingPong:  delay_.pingPong = v; break;
                case kReverbMix:      reverb_.mix = v; break;
                case kReverbSize:     reverb_.setRoomSize(v); break;
                case kReverbDamp:     reverb_.setDamping(v); break;
                case kReverbPreDelay: reverb_.setPreDelayMs(v, sr_); break;
                case kVocalWidth:     width_ = v; break;
                case kVocoderMix:     vocoder_.mix = v; break;
                case kVocoderCarrier: vocoder_.carrierTrack = v; break;
                case kVocoderSibilance: vocoder_.sibilanceAmount = v; break;
                case kPitchGuide:     voice_.useGuide = v > 0.5f; break;
                default: break;
            }
        }
        voiceActive_ = voice_.amount > 0.001f || voice_.harmonyMix > 0.001f ||
                       voice_.formant != 1.0f || voice_.transpose != 0;
        psolaLatency_ = (int) (sr_ / 80.0f) + 2;
    }

    static int clampInt(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    int sr_ = 48000;

    NoiseGate gate_;
    VoiceEq eq_;
    DeEsser deEsser_;
    VoiceProcessor voice_;
    Compressor comp_;
    Doubler doubler_;
    Vocoder vocoder_;
    Delay delay_;
    Reverb reverb_;

    float inputGain_ = 1.0f, vocalGain_ = 1.0f;
    float harmonySpread_ = 0.8f, width_ = 1.0f;
    bool bypass_ = false, voiceActive_ = false;
    int psolaLatency_ = 0;

    std::atomic<float> peakLevel_{0.0f};
    std::atomic<float> pitchHz_{0.0f};
    std::atomic<float> pitchMidi_{-1.0f};
    std::atomic<float> confidence_{0.0f};
    std::atomic<float> centsOff_{0.0f};
    std::atomic<float> gainReduction_{0.0f};
    std::atomic<int> note_{-1};
};

}  // namespace av
