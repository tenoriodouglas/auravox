# JNI entry points are looked up by name at runtime, so they must survive
# shrinking even though nothing in Kotlin calls them.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.auravox.audio.NativeAudio { *; }
