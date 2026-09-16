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
#include "dsp/InputFifo.h"
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

    /**
     * Opens the streams and starts.
     *
     * `inputDeviceId` is an Android AudioDeviceInfo id, or 0 for whatever the
     * system picks. A Bluetooth headset mic only appears once the platform has
     * routed communication audio to it, and it will never open on the fast
     * capture path, so `communication` relaxes the request for it.
     */
    bool start(int32_t inputDeviceId = 0, bool communication = false);
    void stop();
    bool isRunning() const { return running_.load(std::memory_order_acquire); }

    ParamStore &params() { return params_; }
    FxChain &chain() { return chain_; }
    TrackPlayer &player() { return player_; }
    ScoreTracker &score() { return score_; }
    Metronome &metronome() { return metronome_; }

    /**
     * Starts a take. `mixPath` gets the full mix, `stemPath` the processed
     * voice alone. The mix is what the next layer plays against, and what
     * makes overdub cost nothing: every pass bounces down to one file.
     */
    bool startRecording(const std::string &mixPath, const std::string &stemPath);
    void stopRecording();
    bool isRecording() const { return wav_.active(); }

    /**
     * Song time of the first frame of the source now loaded.
     *
     * A bounced mix starts one alignment offset before the song did, because
     * the take carries the track already delayed. Without this the second
     * layer would score and read lyrics a round trip early.
     */
    void setSongOffsetMs(double ms) { songOffsetMs_.store(ms, std::memory_order_relaxed); }
    double songOffsetMs() const { return songOffsetMs_.load(std::memory_order_relaxed); }

    /** Song position, offset applied. This is the one the UI and scoring use. */
    double songMs() const { return player_.positionMs() + songOffsetMs(); }

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

    /** Capture slack currently held, in frames. Grows on a jittery device. */
    int inputCushionFrames() const { return fifoPolicy_.cushion; }

    /** Input device actually opened, so the UI can show what is being heard. */
    int32_t inputDeviceId() const { return openedDeviceId_; }
    bool lowLatencyInput() const { return lowLatencyInput_; }

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream,
                                          void *audioData,
                                          int32_t numFrames) override;

    // oboe::AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    static constexpr int kMaxBlockFrames = 4096;

    bool openStreams(int32_t inputDeviceId, bool communication);
    bool openInput(int32_t inputDeviceId, bool communication);
    void adaptBufferSize();
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
    WavWriter stemWav_;

    std::mutex lifecycleLock_;
    std::atomic<bool> running_{false};
    std::atomic<bool> denormalsSet_{false};
    std::atomic<bool> primed_{false};
    std::atomic<float> latencyMs_{0.0f};
    std::atomic<float> outLatencyMs_{0.0f};
    std::atomic<float> alignMs_{0.0f};
    std::atomic<double> songOffsetMs_{0.0};
    std::atomic<int> xruns_{0};

    int sampleRate_ = 48000;
    int framesPerBurst_ = 192;
    int latencyPollCounter_ = 0;
    int32_t openedDeviceId_ = 0;
    int32_t requestedDeviceId_ = 0;
    bool requestedCommunication_ = false;
    int lastOutputXRun_ = 0;
    bool lowLatencyInput_ = true;

    InputFifo inputFifo_;
    FifoPolicy fifoPolicy_;

    std::vector<float> micBuf_, voiceBuf_, trackBuf_, clickBuf_, recBuf_, drainBuf_;
};

}  // namespace av
