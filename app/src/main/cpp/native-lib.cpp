#include <jni.h>
#include <memory>
#include <string>
#include <vector>

#include "AudioEngine.h"
#include "karaoke/Analysis.h"

// Single engine instance. The activity is the only owner, so a handle would
// buy nothing but a chance to pass a stale pointer from Kotlin.
static std::unique_ptr<av::AudioEngine> gEngine;

// Staging buffer for PCM coming from the decoder thread. Reused so the JNI
// boundary never allocates while the track is streaming.
static std::vector<float> gStaging;

#define ENGINE_OR(ret) if (!gEngine) return ret; auto &e = *gEngine

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_auravox_audio_NativeAudio_nativeCreate(JNIEnv *, jobject) {
    if (!gEngine) {
        gEngine = std::make_unique<av::AudioEngine>();
        av::FxChain::applyDefaults(gEngine->params());
        av::TrackPlayer::applyDefaults(gEngine->params());
        av::Mixer::applyDefaults(gEngine->params());
    }
    if (gStaging.empty()) gStaging.resize(16384);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_auravox_audio_NativeAudio_nativeDestroy(JNIEnv *, jobject) {
    gEngine.reset();
}

JNIEXPORT jboolean JNICALL
Java_com_auravox_audio_NativeAudio_nativeStart(JNIEnv *, jobject) {
    return gEngine && gEngine->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_auravox_audio_NativeAudio_nativeStop(JNIEnv *, jobject) {
    if (gEngine) gEngine->stop();
}

JNIEXPORT jboolean JNICALL
Java_com_auravox_audio_NativeAudio_nativeIsRunning(JNIEnv *, jobject) {
    return gEngine && gEngine->isRunning() ? JNI_TRUE : JNI_FALSE;
}

// --- parameters ---

JNIEXPORT void JNICALL
Java_com_auravox_audio_NativeAudio_nativeSetParam(JNIEnv *, jobject, jint id, jfloat value) {
    if (gEngine) gEngine->params().set(id, value);
}

JNIEXPORT jfloat JNICALL
Java_com_auravox_audio_NativeAudio_nativeGetParam(JNIEnv *, jobject, jint id) {
    return gEngine ? gEngine->params().get(id) : 0.0f;
}

// --- meters ---

JNIEXPORT jfloat JNICALL
Java_com_auravox_audio_NativeAudio_nativeGetLevel(JNIEnv *, jobject) {
    ENGINE_OR(0.0f); return e.chain().peakLevel();
}

JNIEXPORT jfloat JNICALL
Java_com_auravox_audio_NativeAudio_nativeGetOutputLevel(JNIEnv *, jobject) {
    ENGINE_OR(0.0f); return e.outputLevel();
}

JNIEXPORT jfloat JNICALL
Java_com_auravox_audio_NativeAudio_nativeGetPitchHz(JNIEnv *, jobject) {
    ENGINE_OR(0.0f); return e.chain().pitchHz();
}

JNIEXPORT jfloat JNICALL
Java_com_auravox_audio_NativeAudio_nativeGetPitchMidi(JNIEnv *, jobject) {
    ENGINE_OR(-1.0f); return e.chain().pitchMidi();
}

JNIEXPORT jint JNICALL
Java_com_auravox_audio_NativeAudio_nativeGetNote(JNIEnv *, jobject) {
    ENGINE_OR(-1); return e.chain().note();
}

JNIEXPORT jfloat JNICALL
Java_com_auravox_audio_NativeAudio_nativeGetCentsOff(JNIEnv *, jobject) {
    ENGINE_OR(0.0f); return e.chain().centsOff();
}

JNIEXPORT jfloat JNICALL
Java_com_auravox_audio_NativeAudio_nativeGetGainReduction(JNIEnv *, jobject) {
    ENGINE_OR(0.0f); return e.chain().gainReductionDb();
}

JNIEXPORT jfloat JNICALL
Java_com_auravox_audio_NativeAudio_nativeGetLatencyMs(JNIEnv *, jobject) {
    ENGINE_OR(0.0f); return e.latencyMs();
}

JNIEXPORT jfloat JNICALL
Java_com_auravox_audio_NativeAudio_nativeGetAlignMs(JNIEnv *, jobject) {
    ENGINE_OR(0.0f); return e.alignMs();
}

JNIEXPORT jint JNICALL
Java_com_auravox_audio_NativeAudio_nativeGetSampleRate(JNIEnv *, jobject) {
    ENGINE_OR(0); return e.sampleRate();
}

JNIEXPORT jint JNICALL
Java_com_auravox_audio_NativeAudio_nativeGetXruns(JNIEnv *, jobject) {
    ENGINE_OR(0); return e.xrunCount();
}

// --- recording ---

JNIEXPORT jboolean JNICALL
Java_com_auravox_audio_NativeAudio_nativeStartRecording(JNIEnv *env, jobject, jstring jPath) {
    if (!gEngine) return JNI_FALSE;
    const char *path = env->GetStringUTFChars(jPath, nullptr);
    const bool ok = gEngine->startRecording(std::string(path));
    env->ReleaseStringUTFChars(jPath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_auravox_audio_NativeAudio_nativeStopRecording(JNIEnv *, jobject) {
    if (gEngine) gEngine->stopRecording();
}

// --- backing track ---

JNIEXPORT void JNICALL
Java_com_auravox_audio_NativeAudio_nativeTrackSetSource(JNIEnv *, jobject,
                                                        jint sampleRate, jlong totalFrames) {
    if (gEngine) {
        gEngine->player().setSource(sampleRate, totalFrames);
        gEngine->player().setLoaded(totalFrames > 0);
    }
}

/** Decoder thread. Returns how many frames the ring accepted. */
JNIEXPORT jint JNICALL
Java_com_auravox_audio_NativeAudio_nativeTrackPush(JNIEnv *env, jobject,
                                                   jfloatArray data, jint frames) {
    if (!gEngine || frames <= 0) return 0;
    const jsize samples = frames * 2;
    if ((size_t) samples > gStaging.size()) gStaging.resize((size_t) samples);
    env->GetFloatArrayRegion(data, 0, samples, gStaging.data());
    return gEngine->player().pushPcm(gStaging.data(), frames);
}

JNIEXPORT jint JNICALL
Java_com_auravox_audio_NativeAudio_nativeTrackRingSpace(JNIEnv *, jobject) {
    ENGINE_OR(0); return e.player().ringSpace();
}

/** Frames already decoded and waiting. Playback should not start on an empty ring. */
JNIEXPORT jint JNICALL
Java_com_auravox_audio_NativeAudio_nativeTrackBuffered(JNIEnv *, jobject) {
    ENGINE_OR(0); return e.player().ringAvailable();
}

JNIEXPORT void JNICALL
Java_com_auravox_audio_NativeAudio_nativeTrackSetEos(JNIEnv *, jobject, jboolean eos) {
    if (gEngine) gEngine->player().setEndOfStream(eos == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_auravox_audio_NativeAudio_nativeTrackSeek(JNIEnv *, jobject, jlong frame) {
    if (gEngine) gEngine->player().seekTo(frame);
}

JNIEXPORT void JNICALL
Java_com_auravox_audio_NativeAudio_nativeTrackPlay(JNIEnv *, jobject, jfloat bpm, jint countInBeats) {
    if (gEngine) gEngine->startCountIn(bpm, countInBeats);
}

JNIEXPORT void JNICALL
Java_com_auravox_audio_NativeAudio_nativeTrackPause(JNIEnv *, jobject) {
    if (gEngine) gEngine->player().pause();
}

JNIEXPORT jboolean JNICALL
Java_com_auravox_audio_NativeAudio_nativeTrackIsPlaying(JNIEnv *, jobject) {
    return gEngine && gEngine->player().isPlaying() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_auravox_audio_NativeAudio_nativeTrackFinished(JNIEnv *, jobject) {
    return gEngine && gEngine->player().finished() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jdouble JNICALL
Java_com_auravox_audio_NativeAudio_nativeTrackPositionMs(JNIEnv *, jobject) {
    ENGINE_OR(0.0); return e.player().positionMs();
}

JNIEXPORT jint JNICALL
Java_com_auravox_audio_NativeAudio_nativeTrackUnderruns(JNIEnv *, jobject) {
    ENGINE_OR(0); return e.player().underruns();
}

JNIEXPORT jint JNICALL
Java_com_auravox_audio_NativeAudio_nativeCountInBeatsLeft(JNIEnv *, jobject) {
    ENGINE_OR(0); return e.metronome().beatsLeft();
}

// --- scoring ---

JNIEXPORT void JNICALL
Java_com_auravox_audio_NativeAudio_nativeSetMelody(JNIEnv *env, jobject,
                                                   jfloatArray triples, jint noteCount) {
    if (!gEngine) return;
    if (noteCount <= 0 || triples == nullptr) {
        gEngine->score().setMelody(nullptr, 0);
        return;
    }
    std::vector<float> notes((size_t) noteCount * 3);
    env->GetFloatArrayRegion(triples, 0, noteCount * 3, notes.data());
    gEngine->score().setMelody(notes.data(), noteCount);
}

JNIEXPORT void JNICALL
Java_com_auravox_audio_NativeAudio_nativeResetScore(JNIEnv *, jobject) {
    if (gEngine) gEngine->score().reset();
}

JNIEXPORT void JNICALL
Java_com_auravox_audio_NativeAudio_nativeSetScoringEnabled(JNIEnv *, jobject, jboolean on) {
    if (gEngine) gEngine->score().setEnabled(on == JNI_TRUE);
}

/**
 * One call returns the whole scoreboard: score, combo, max combo, the four
 * grade counters, the current target note and the live accuracy. Polling
 * eight separate methods at 60 Hz is eight JNI transitions per frame.
 */
JNIEXPORT void JNICALL
Java_com_auravox_audio_NativeAudio_nativeGetScoreState(JNIEnv *env, jobject, jfloatArray out) {
    if (!gEngine) return;
    auto &s = gEngine->score();
    float v[9];
    v[0] = s.score01();
    v[1] = (float) s.combo();
    v[2] = (float) s.maxCombo();
    v[3] = (float) s.perfectCount();
    v[4] = (float) s.greatCount();
    v[5] = (float) s.goodCount();
    v[6] = (float) s.missCount();
    v[7] = s.targetMidi();
    v[8] = s.liveAccuracy();
    env->SetFloatArrayRegion(out, 0, 9, v);
}

/**
 * Meters the performance screen polls every frame, in one transition:
 * level, output level, pitch midi, cents off, latency, align, position ms,
 * playing, finished, xruns, underruns, count-in beats left, output latency.
 */
JNIEXPORT void JNICALL
Java_com_auravox_audio_NativeAudio_nativeGetTransportState(JNIEnv *env, jobject, jfloatArray out) {
    if (!gEngine) return;
    auto &e = *gEngine;
    float v[13];
    v[0] = e.chain().peakLevel();
    v[1] = e.outputLevel();
    v[2] = e.chain().pitchMidi();
    v[3] = e.chain().centsOff();
    v[4] = e.latencyMs();
    v[5] = e.alignMs();
    v[6] = (float) e.player().positionMs();
    v[7] = e.player().isPlaying() ? 1.0f : 0.0f;
    v[8] = e.player().finished() ? 1.0f : 0.0f;
    v[9] = (float) e.xrunCount();
    v[10] = (float) e.player().underruns();
    v[11] = (float) e.metronome().beatsLeft();
    v[12] = e.outputLatencyMs();
    env->SetFloatArrayRegion(out, 0, 13, v);
}

// --- offline analysis ---

/**
 * Melody, key and tempo for an imported song. Runs on a worker thread.
 *
 * Layout of the returned array: [keyRoot, keyMode, keyConfidence, bpm,
 * bpmConfidence, noteCount, then noteCount triples of startMs, durMs, midi].
 */
JNIEXPORT jfloatArray JNICALL
Java_com_auravox_audio_NativeAudio_nativeAnalyze(JNIEnv *env, jobject,
                                                 jfloatArray mono, jint frames) {
    if (frames <= 0) return env->NewFloatArray(0);

    std::vector<float> pcm((size_t) frames);
    env->GetFloatArrayRegion(mono, 0, frames, pcm.data());

    const av::AnalysisResult r = av::analyzeSong(pcm.data(), frames);
    const int noteCount = (int) (r.notes.size() / 3);

    std::vector<float> out;
    out.reserve(6 + r.notes.size());
    out.push_back((float) r.keyRoot);
    out.push_back((float) r.keyMode);
    out.push_back(r.keyConfidence);
    out.push_back(r.bpm);
    out.push_back(r.bpmConfidence);
    out.push_back((float) noteCount);
    out.insert(out.end(), r.notes.begin(), r.notes.end());

    jfloatArray result = env->NewFloatArray((jsize) out.size());
    if (result) env->SetFloatArrayRegion(result, 0, (jsize) out.size(), out.data());
    return result;
}

}  // extern "C"
