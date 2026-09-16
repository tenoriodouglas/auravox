#include "AudioEngine.h"

#include <android/log.h>
#include <thread>

#define LOG_TAG "AuraVox"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace av {

bool AudioEngine::start(int32_t inputDeviceId, bool communication) {
    std::lock_guard<std::mutex> lock(lifecycleLock_);
    if (running_.load(std::memory_order_acquire)) return true;

    // Remembered so a disconnect reopens on the same microphone instead of
    // silently falling back to the built-in one
    requestedDeviceId_ = inputDeviceId;
    requestedCommunication_ = communication;

    if (!openStreams(inputDeviceId, communication)) {
        closeStreams();
        return false;
    }

    chain_.prepare(sampleRate_);
    player_.prepare(sampleRate_, kMaxBlockFrames);
    mixer_.prepare(sampleRate_);
    metronome_.prepare(sampleRate_);

    micBuf_.assign(kMaxBlockFrames, 0.0f);
    clickBuf_.assign(kMaxBlockFrames, 0.0f);
    voiceBuf_.assign((size_t) kMaxBlockFrames * 2, 0.0f);
    trackBuf_.assign((size_t) kMaxBlockFrames * 2, 0.0f);
    recBuf_.assign((size_t) kMaxBlockFrames * 2, 0.0f);
    drainBuf_.assign((size_t) framesPerBurst_ * 8, 0.0f);

    // One burst of slack is what keeps a callback that fires early from
    // finding a half-filled capture buffer
    inputFifo_.prepare(std::max(framesPerBurst_ * 16, kMaxBlockFrames * 2));
    inputFifo_.clear();
    fifoPolicy_.configure(framesPerBurst_);
    lastOutputXRun_ = 0;
    denormalsSet_.store(false, std::memory_order_relaxed);

    // Re-publish every parameter so the freshly prepared DSP picks up the
    // values the UI set while the engine was down
    params_.touchRange(0, kParamCount);
    primed_.store(false, std::memory_order_release);
    xruns_.store(0, std::memory_order_relaxed);

    // Input must be started first or the first callbacks find an empty stream
    oboe::Result r = input_->requestStart();
    if (r != oboe::Result::OK) {
        LOGW("input start failed: %s", oboe::convertToText(r));
        closeStreams();
        return false;
    }

    r = output_->requestStart();
    if (r != oboe::Result::OK) {
        LOGW("output start failed: %s", oboe::convertToText(r));
        closeStreams();
        return false;
    }

    running_.store(true, std::memory_order_release);
    updateLatency();
    return true;
}

/**
 * Opens the microphone, walking down from the fast path.
 *
 * The first attempt is the exclusive low-latency capture every wired setup
 * wants. A Bluetooth headset mic runs over SCO, which has no fast path at all,
 * so asking for one silently lands back on the built-in microphone. The lower
 * rungs give that up in exchange for actually opening the device the singer
 * chose.
 */
bool AudioEngine::openInput(int32_t inputDeviceId, bool communication) {
    struct Attempt {
        oboe::PerformanceMode performance;
        oboe::SharingMode sharing;
        oboe::InputPreset preset;
        bool lowLatency;
    };
    static const Attempt kFast[] = {
        {oboe::PerformanceMode::LowLatency, oboe::SharingMode::Exclusive,
         oboe::InputPreset::VoicePerformance, true},
        {oboe::PerformanceMode::LowLatency, oboe::SharingMode::Shared,
         oboe::InputPreset::VoicePerformance, true},
        {oboe::PerformanceMode::None, oboe::SharingMode::Shared,
         oboe::InputPreset::VoiceCommunication, false},
    };
    static const Attempt kCommunication[] = {
        {oboe::PerformanceMode::None, oboe::SharingMode::Shared,
         oboe::InputPreset::VoiceCommunication, false},
        {oboe::PerformanceMode::None, oboe::SharingMode::Shared,
         oboe::InputPreset::VoicePerformance, false},
    };

    const Attempt *ladder = communication ? kCommunication : kFast;
    const int count = communication ? 2 : 3;

    for (int i = 0; i < count; ++i) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Input)
               ->setPerformanceMode(ladder[i].performance)
               ->setSharingMode(ladder[i].sharing)
               ->setFormat(oboe::AudioFormat::Float)
               ->setChannelCount(oboe::ChannelCount::Mono)
               ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
               // VoicePerformance skips AEC, AGC and noise suppression. Those
               // three are what make the stock mic path unusable for singing.
               ->setInputPreset(ladder[i].preset);
        if (inputDeviceId > 0) builder.setDeviceId(inputDeviceId);

        const oboe::Result r = builder.openStream(input_);
        if (r == oboe::Result::OK) {
            lowLatencyInput_ = ladder[i].lowLatency &&
                input_->getPerformanceMode() == oboe::PerformanceMode::LowLatency;
            openedDeviceId_ = input_->getDeviceId();
            LOGI("input open: device %d, %s, preset %d",
                 openedDeviceId_,
                 lowLatencyInput_ ? "low latency" : "normal",
                 (int) ladder[i].preset);
            return true;
        }
        LOGW("input attempt %d failed: %s", i, oboe::convertToText(r));
    }
    return false;
}

bool AudioEngine::openStreams(int32_t inputDeviceId, bool communication) {
    if (!openInput(inputDeviceId, communication)) return false;

    sampleRate_ = input_->getSampleRate();
    framesPerBurst_ = input_->getFramesPerBurst();

    // The exclusive fast path is what a wired setup wants. Over a Bluetooth
    // link the platform owns the route and will refuse it, so the second
    // attempt gives it up rather than failing to start at all.
    const oboe::SharingMode sharing[2] = {
        oboe::SharingMode::Exclusive, oboe::SharingMode::Shared
    };
    oboe::Result r = oboe::Result::ErrorInternal;
    for (int attempt = 0; attempt < 2; ++attempt) {
        oboe::AudioStreamBuilder outBuilder;
        outBuilder.setDirection(oboe::Direction::Output)
                  ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                  ->setSharingMode(sharing[attempt])
                  ->setFormat(oboe::AudioFormat::Float)
                  ->setChannelCount(oboe::ChannelCount::Stereo)
                  ->setSampleRate(sampleRate_)
                  ->setUsage(oboe::Usage::Media)
                  ->setContentType(oboe::ContentType::Music)
                  ->setDataCallback(this)
                  ->setErrorCallback(this);

        r = outBuilder.openStream(output_);
        if (r == oboe::Result::OK) break;
        LOGW("open output attempt %d failed: %s", attempt, oboe::convertToText(r));
    }
    if (r != oboe::Result::OK) return false;

    // Two bursts is the smallest size that survives normal scheduler jitter.
    // It is a starting point, not a verdict: adaptBufferSize grows it on a
    // device that cannot hold it rather than crackling for the whole song.
    output_->setBufferSizeInFrames(output_->getFramesPerBurst() * 2);

    LOGI("streams open: %d Hz, burst %d, api %s",
         sampleRate_, framesPerBurst_, oboe::convertToText(output_->getAudioApi()));
    return true;
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> lock(lifecycleLock_);
    if (!running_.load(std::memory_order_acquire)) return;

    running_.store(false, std::memory_order_release);
    wav_.stop();
    closeStreams();
}

void AudioEngine::closeStreams() {
    if (output_) {
        output_->stop();
        output_->close();
        output_.reset();
    }
    if (input_) {
        input_->stop();
        input_->close();
        input_.reset();
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream * /*stream*/,
                                                   void *audioData,
                                                   int32_t numFrames) {
    auto *out = static_cast<float *>(audioData);
    const int frames = std::min<int>(numFrames, kMaxBlockFrames);

    // A callback bigger than the scratch buffers would otherwise leave the
    // tail of the output untouched, and whatever the driver left there is
    // noise. Non-low-latency paths -- the Bluetooth one especially -- hand out
    // far larger blocks than the fast path ever does.
    if (frames < numFrames) {
        std::fill(out + (size_t) frames * 2, out + (size_t) numFrames * 2, 0.0f);
    }

    // Denormals cost roughly 100x on reverb tails. Set once per callback thread.
    if (!denormalsSet_.exchange(true, std::memory_order_relaxed)) {
        enableFlushDenormals();
    }

    if (!input_ || !running_.load(std::memory_order_relaxed)) {
        std::fill(out, out + numFrames * 2, 0.0f);
        return oboe::DataCallbackResult::Continue;
    }

    // First callback: drain whatever piled up between the two stream starts,
    // otherwise that backlog becomes permanent latency.
    if (!primed_.exchange(true, std::memory_order_relaxed)) {
        int32_t drained;
        do {
            auto res = input_->read(drainBuf_.data(), (int32_t) drainBuf_.size(), 0);
            drained = res ? res.value() : 0;
        } while (drained > 0);
        inputFifo_.clear();
        fifoPolicy_.reset();
        std::fill(out, out + numFrames * 2, 0.0f);
        return oboe::DataCallbackResult::Continue;
    }

    // Take everything the capture side has ready, then serve this block out of
    // the FIFO. Reading the stream directly is what splices zeros into the
    // voice whenever the callback beats the capture burst by a hair.
    while (inputFifo_.space() > framesPerBurst_) {
        const int want = std::min((int) drainBuf_.size(), inputFifo_.space());
        auto res = input_->read(drainBuf_.data(), want, 0 /* no timeout */);
        const int32_t got = res ? res.value() : 0;
        if (got <= 0) break;
        inputFifo_.write(drainBuf_.data(), got);
    }

    fifoPolicy_.trim(inputFifo_, frames);
    if (!fifoPolicy_.serve(inputFifo_, micBuf_.data(), frames)) {
        xruns_.fetch_add(1, std::memory_order_relaxed);
    }

    // The track is rendered first so the vocoder can use it as its carrier.
    // The melody note under the playhead goes in as the autotune target, so
    // the correction lands on the note the singer was reaching for.
    player_.render(params_, trackBuf_.data(), frames);
    chain_.setGuideMidi(player_.isPlaying() ? score_.targetMidi() : -1.0f);
    chain_.process(params_, micBuf_.data(), trackBuf_.data(), voiceBuf_.data(), frames);

    const bool clicking = metronome_.running();
    if (clicking) {
        for (int i = 0; i < frames; ++i) clickBuf_[i] = metronome_.process();
        if (metronome_.consumeFinished()) player_.play();
    }

    const bool recording = wav_.active();
    mixer_.process(params_, voiceBuf_.data(), trackBuf_.data(),
                   clicking ? clickBuf_.data() : nullptr,
                   out, recording ? recBuf_.data() : nullptr,
                   frames, player_.duckAmount());

    if (recording) {
        wav_.push(recBuf_.data(), frames);
        stemWav_.push(voiceBuf_.data(), frames);
    }

    // The singer reacts to what they heard, not to what is being written now.
    // Scoring the raw playhead marks every take as late by the round trip.
    if (player_.isPlaying()) {
        const float tempo = params_.get(kTrackTempo);
        const double sungAt = this->songMs() -
                              (double) alignMs_.load(std::memory_order_relaxed) *
                              (tempo > 0.01f ? tempo : 1.0f);
        score_.update(sungAt, chain_.pitchMidi(), chain_.confidence(), frames);
    }

    // Latency changes as the buffer size adapts; poll roughly twice a second
    if (++latencyPollCounter_ * frames > sampleRate_ / 2) {
        latencyPollCounter_ = 0;
        adaptBufferSize();
        updateLatency();
    }

    return oboe::DataCallbackResult::Continue;
}

/**
 * Grows the output buffer when the device proves it cannot hold the current
 * one.
 *
 * Two bursts is right on most phones and impossible on some. Leaving it fixed
 * means those devices crackle for the whole session; one extra burst per
 * underrun settles within a second or two and costs a few milliseconds.
 */
void AudioEngine::adaptBufferSize() {
    if (!output_) return;
    auto xr = output_->getXRunCount();
    if (!xr) return;

    const int current = xr.value();
    if (current <= lastOutputXRun_) return;
    lastOutputXRun_ = current;

    const int32_t burst = output_->getFramesPerBurst();
    const int32_t size = output_->getBufferSizeInFrames();
    const int32_t cap = output_->getBufferCapacityInFrames();
    if (size + burst > cap) return;

    output_->setBufferSizeInFrames(size + burst);
    LOGI("output buffer grown to %d frames after %d underruns", size + burst, current);
}

void AudioEngine::updateLatency() {
    float total = 0.0f, outOnly = 0.0f;
    if (input_) {
        auto l = input_->calculateLatencyMillis();
        if (l) total += (float) l.value();
    }
    if (output_) {
        auto l = output_->calculateLatencyMillis();
        if (l) outOnly = (float) l.value();
    }
    total += outOnly;
    latencyMs_.store(total, std::memory_order_relaxed);
    outLatencyMs_.store(outOnly, std::memory_order_relaxed);

    // The PSOLA path adds its own delay on top, but only while it is engaged,
    // and the input cushion is latency the streams never report
    const float lookaheadMs = 1000.0f * (float) chain_.lookaheadSamples() / (float) sampleRate_;
    total += 1000.0f * (float) fifoPolicy_.cushion / (float) sampleRate_;
    const float trim = params_.get(kLatencyTrimMs);
    const float align = std::max(0.0f, total + lookaheadMs + trim);
    alignMs_.store(align, std::memory_order_relaxed);
    mixer_.setAlignSamples((int) (align * 0.001f * (float) sampleRate_));
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result error) {
    LOGW("stream error: %s", oboe::convertToText(error));

    // Disconnect happens when headphones are unplugged. Reopen on another
    // thread; restarting from inside the error callback would deadlock.
    if (error == oboe::Result::ErrorDisconnected) {
        std::thread([this] {
            const int32_t device = requestedDeviceId_;
            const bool communication = requestedCommunication_;
            stop();
            start(device, communication);
        }).detach();
    }
}

bool AudioEngine::startRecording(const std::string &mixPath, const std::string &stemPath) {
    if (!running_.load(std::memory_order_acquire)) return false;
    // The alignment is already refreshed twice a second on the audio thread;
    // querying the streams from here would race with that for a value that is
    // at most half a second stale.

    if (!wav_.start(mixPath, sampleRate_)) return false;
    if (!stemPath.empty() && !stemWav_.start(stemPath, sampleRate_)) {
        wav_.stop();
        return false;
    }
    return true;
}

void AudioEngine::stopRecording() {
    wav_.stop();
    stemWav_.stop();
}

void AudioEngine::startCountIn(float bpm, int beats) {
    if (beats <= 0) {
        player_.play();
        return;
    }
    metronome_.start(bpm, beats);
}

}  // namespace av
