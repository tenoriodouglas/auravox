#include <jni.h>
#include <algorithm>
#include <memory>
#include <vector>

#include "dsp/Resampler.h"
#include "separation/Separator.h"

/*
 * The signal half of vocal separation.
 *
 * Kotlin owns the neural net, because the runtime that runs it has a Java API
 * and no stable C one worth binding. Everything either side of the net is
 * here: the transform, the masks and the rate conversion. That split means two
 * crossings of the boundary per eleven seconds of audio, which is nothing, and
 * it keeps the DSP in the place where it is already tested on the host.
 *
 * One instance, owned by the separator object on the Kotlin side. Separation
 * is a single job with a progress bar, never two at once, so a handle would
 * only create a way to pass a stale pointer.
 */

static std::unique_ptr<av::Separator> gSeparator;
static av::Resampler gResampler;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_auravox_separation_NativeSeparator_nativePrepare(
    JNIEnv *, jobject, jint nFft, jint hop, jint modelBins
) {
    gSeparator = std::make_unique<av::Separator>();
    gSeparator->prepare(nFft, hop, modelBins);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_auravox_separation_NativeSeparator_nativeRelease(JNIEnv *, jobject) {
    gSeparator.reset();
}

JNIEXPORT jint JNICALL
Java_com_auravox_separation_NativeSeparator_nativeBins(JNIEnv *, jobject) {
    return gSeparator ? gSeparator->bins() : 0;
}

JNIEXPORT jint JNICALL
Java_com_auravox_separation_NativeSeparator_nativeFrameCount(
    JNIEnv *, jobject, jint samples
) {
    return gSeparator ? gSeparator->frameCount(samples) : 0;
}

/**
 * Transforms one channel of a split and writes the magnitudes the net wants.
 *
 * Returns the frame count, or 0 when the output array is too small for it,
 * which is the caller's cue that it sized the buffer from the wrong length.
 */
JNIEXPORT jint JNICALL
Java_com_auravox_separation_NativeSeparator_nativeAnalyze(
    JNIEnv *env, jobject, jfloatArray samples, jint channel, jfloatArray magOut
) {
    if (!gSeparator) return 0;
    const jsize length = env->GetArrayLength(samples);
    const int frames = gSeparator->frameCount(length);
    const jsize needed = (jsize) frames * gSeparator->modelBins();
    if (env->GetArrayLength(magOut) < needed) return 0;

    jfloat *in = env->GetFloatArrayElements(samples, nullptr);
    jfloat *out = env->GetFloatArrayElements(magOut, nullptr);
    gSeparator->analyze(in, length, channel, out);
    env->ReleaseFloatArrayElements(samples, in, JNI_ABORT);
    env->ReleaseFloatArrayElements(magOut, out, 0);
    return frames;
}

/**
 * Masks the kept spectrum with the net's two outputs and inverts it.
 *
 * Either destination may be null when that stem is not wanted; asking for the
 * backing track alone is the common case.
 */
JNIEXPORT void JNICALL
Java_com_auravox_separation_NativeSeparator_nativeSynthesize(
    JNIEnv *env, jobject,
    jfloatArray vocalMag, jfloatArray accompMag, jint channel,
    jfloatArray vocalOut, jfloatArray accompOut
) {
    if (!gSeparator) return;

    jfloat *vm = env->GetFloatArrayElements(vocalMag, nullptr);
    jfloat *am = env->GetFloatArrayElements(accompMag, nullptr);
    jfloat *vo = vocalOut ? env->GetFloatArrayElements(vocalOut, nullptr) : nullptr;
    jfloat *ao = accompOut ? env->GetFloatArrayElements(accompOut, nullptr) : nullptr;

    // The shorter of the two, not whichever happens to be non-null first: one
    // length is applied to both inside, and writing a vocal-sized buffer into
    // a shorter accompaniment array would corrupt the heap past its end
    jsize length = -1;
    if (vocalOut) length = env->GetArrayLength(vocalOut);
    if (accompOut) {
        const jsize other = env->GetArrayLength(accompOut);
        length = length < 0 ? other : std::min(length, other);
    }
    if (length > 0) {
        gSeparator->synthesize(vm, am, channel, length, vo, ao);
    }

    env->ReleaseFloatArrayElements(vocalMag, vm, JNI_ABORT);
    env->ReleaseFloatArrayElements(accompMag, am, JNI_ABORT);
    if (vo) env->ReleaseFloatArrayElements(vocalOut, vo, 0);
    if (ao) env->ReleaseFloatArrayElements(accompOut, ao, 0);
}

/** Output frames a conversion of this many input frames yields. */
JNIEXPORT jint JNICALL
Java_com_auravox_separation_NativeSeparator_nativeResampleFrames(
    JNIEnv *, jobject, jint inFrames, jdouble inRate, jdouble outRate
) {
    av::Resampler probe;
    if (!probe.prepare(inRate, outRate)) return 0;
    return probe.outputFrames(inFrames);
}

/**
 * Converts one channel to the rate the net was trained at.
 *
 * The kernel is rebuilt only when the rates change, because a song is
 * converted in splits and rebuilding a 256-phase table per split would cost
 * more than the conversion.
 */
JNIEXPORT jint JNICALL
Java_com_auravox_separation_NativeSeparator_nativeResample(
    JNIEnv *env, jobject,
    jfloatArray in, jdouble inRate, jdouble outRate, jfloatArray out
) {
    static double lastIn = 0.0, lastOut = 0.0;
    if (inRate != lastIn || outRate != lastOut) {
        if (!gResampler.prepare(inRate, outRate)) return 0;
        lastIn = inRate;
        lastOut = outRate;
    }

    const jsize inFrames = env->GetArrayLength(in);
    const jsize outFrames = env->GetArrayLength(out);
    jfloat *src = env->GetFloatArrayElements(in, nullptr);
    jfloat *dst = env->GetFloatArrayElements(out, nullptr);
    gResampler.process(src, inFrames, dst, outFrames);
    env->ReleaseFloatArrayElements(in, src, JNI_ABORT);
    env->ReleaseFloatArrayElements(out, dst, 0);
    return outFrames;
}

}  // extern "C"
