#pragma once

#include <algorithm>
#include <atomic>
#include <cstring>   // must precede Oboe: FullDuplexStream.h uses memset
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <oboe/Oboe.h>

#include "Params.h"
#include "WavWriter.h"
#include "dsp/FxChain.h"
#include "dsp/Metronome.h"
#include "dsp/Mixer.h"
#include "karaoke/ScoreTracker.h"
#include "track/TrackPlayer.h"

namespace av {

/**
 * Full-duplex karaoke engine: mono mic in, stereo backing track, stereo out.
 *
 * The output stream owns the callback. On each callback it pulls whatever the
 * input stream has ready with a zero timeout, runs the vocal chain, renders
 * the backing track and mixes both. Driving everything from one callback
 * avoids the extra buffer a second callback plus a FIFO would cost.
 */
class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    AudioEngine() = default;
    ~AudioEngine() override { stop(); }

    bool start();
    void stop();
    bool isRunning() const { return running_.load(std::memory_order_acquire); }

    ParamStore &params() { return params_; }
    FxChain &chain() { return chain_; }
    TrackPlayer &player() { return player_; }
    ScoreTracker &score() { return score_; }
    Metronome &metronome() { return metronome_; }

    bool startRecording(const std::string &path);
    void stopRecording();
    bool isRecording() const { return wav_.active(); }

    /** Counts `beats` in at `bpm`, then starts the track on the exact downbeat. */
    void startCountIn(float bpm, int beats);

    int sampleRate() const { return sampleRate_; }
    int framesPerBurst() const { return framesPerBurst_; }

    /** Round-trip latency in ms: input + output streams. */
    float latencyMs() const { return latencyMs_.load(std::memory_order_relaxed); }

    /**
     * Output-only latency. The display reads this: what the listener hears now
     * left the mixer this long ago, so lyrics and the pitch lane have to be
     * drawn that far behind the playhead to sit under the sound.
     */
    float outputLatencyMs() const { return outLatencyMs_.load(std::memory_order_relaxed); }

    /** Total voice-to-track offset the recorder and the scorer correct for. */
    float alignMs() const { return alignMs_.load(std::memory_order_relaxed); }

    /** Callbacks that missed their deadline since start. Non-zero means glitches. */
    int xrunCount() const { return xruns_.load(std::memory_order_relaxed); }

    float outputLevel() const { return mixer_.peak(); }

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream,
                                          void *audioData,
                                          int32_t numFrames) override;

    // oboe::AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    static constexpr int kMaxBlockFrames = 4096;

    bool openStreams();
    void closeStreams();
    void updateLatency();

    std::shared_ptr<oboe::AudioStream> input_;
    std::shared_ptr<oboe::AudioStream> output_;

    ParamStore params_;
    FxChain chain_;
    TrackPlayer player_;
    Mixer mixer_;
    Metronome metronome_;
    ScoreTracker score_;
    WavWriter wav_;

    std::mutex lifecycleLock_;
    std::atomic<bool> running_{false};
    std::atomic<bool> denormalsSet_{false};
    std::atomic<bool> primed_{false};
    std::atomic<float> latencyMs_{0.0f};
    std::atomic<float> outLatencyMs_{0.0f};
    std::atomic<float> alignMs_{0.0f};
    std::atomic<int> xruns_{0};

    int sampleRate_ = 48000;
    int framesPerBurst_ = 192;
    int latencyPollCounter_ = 0;

    std::vector<float> micBuf_, voiceBuf_, trackBuf_, clickBuf_, recBuf_, drainBuf_;
};

}  // namespace av
