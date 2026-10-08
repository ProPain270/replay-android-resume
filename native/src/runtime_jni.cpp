#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>

#include <memory>
#include <algorithm>
#include <mutex>
#include <optional>
#include <cstdint>
#include <unordered_map>
#include <string>

#include "android_runtime_bindings.h"
#include "libretro_core.h"
#include "session_host.h"

namespace {

retro::runtime::SessionManager& sessionManager() {
    // Function-local construction avoids static initialization order issues;
    // the manager owns all live session handles for this loaded library.
    static auto* manager = new retro::runtime::SessionManager();
    return *manager;
}

jint toJniResult(const retro::runtime::Result result) noexcept {
    return static_cast<jint>(result);
}

retro::runtime::Handle decodeHandle(const jlong handle) noexcept {
    return handle > 0 ? static_cast<retro::runtime::Handle>(handle) : 0;
}

struct SessionBindings {
    std::shared_ptr<retro::runtime::AndroidVideoSurface> video;
    std::shared_ptr<retro::runtime::AndroidAudioBuffer> audio;
    std::shared_ptr<retro::runtime::AndroidInputState> input;
};

std::mutex& bindingsMutex() {
    static auto* mutex = new std::mutex();
    return *mutex;
}

std::unordered_map<retro::runtime::Handle, SessionBindings>& bindings() {
    static auto* map = new std::unordered_map<retro::runtime::Handle, SessionBindings>();
    return *map;
}

void removeBindings(const retro::runtime::Handle handle) noexcept {
    std::lock_guard lock(bindingsMutex());
    bindings().erase(handle);
}

std::optional<SessionBindings> findBindings(const retro::runtime::Handle handle) {
    std::lock_guard lock(bindingsMutex());
    const auto found = bindings().find(handle);
    return found == bindings().end() ? std::nullopt : std::optional<SessionBindings>(found->second);
}

bool copyJniString(JNIEnv* env, jstring value, std::string* destination, const bool nullable) noexcept {
    if (env == nullptr || destination == nullptr) return false;
    if (value == nullptr) {
        if (!nullable) return false;
        destination->clear();
        return true;
    }
    const char* utf8 = env->GetStringUTFChars(value, nullptr);
    if (utf8 == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    try {
        *destination = utf8;
    } catch (...) {
        env->ReleaseStringUTFChars(value, utf8);
        return false;
    }
    env->ReleaseStringUTFChars(value, utf8);
    return true;
}

jlong encodeCreateResult(const retro::runtime::CreateResult& created) noexcept {
    if (created.status == retro::runtime::Result::kOk && created.handle > 0) {
        return static_cast<jlong>(created.handle);
    }
    return -static_cast<jlong>(created.status);
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_codex_libretroplatform_runtime_host_NativeRuntime_nativeBuildId(
    JNIEnv* env,
    jobject /* instance */) {
    return env == nullptr ? nullptr : env->NewStringUTF(retro::runtime::buildId());
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_codex_libretroplatform_runtime_host_NativeRuntime_nativeSessionCreate(
    JNIEnv* /* env */,
    jobject /* instance */) {
    return encodeCreateResult(sessionManager().create());
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_codex_libretroplatform_runtime_host_NativeRuntime_nativeSessionCreateWithCore(
    JNIEnv* env,
    jobject /* instance */,
    jstring core_path,
    jstring content_path,
    jstring system_directory,
    jstring save_directory) {
    try {
        retro::runtime::LibretroSessionConfig config;
        auto video = std::make_shared<retro::runtime::AndroidVideoSurface>();
        auto audio = std::make_shared<retro::runtime::AndroidAudioBuffer>();
        auto input = std::make_shared<retro::runtime::AndroidInputState>();
        if (!copyJniString(env, core_path, &config.core_path, false) ||
            !copyJniString(env, content_path, &config.content_path, false) ||
            !copyJniString(env, system_directory, &config.system_directory, true) ||
            !copyJniString(env, save_directory, &config.save_directory, true)) {
            return -static_cast<jlong>(retro::runtime::Result::kInvalidArgument);
        }
        config.video_callback = [video](const retro::runtime::VideoFrame& frame) { video->render(frame); };
        config.audio_callback = [audio](const retro::runtime::AudioSamples& samples) { audio->append(samples); };
        config.input_state_callback = [input](unsigned, unsigned device, unsigned index, unsigned id) {
            return input->read(device, index, id);
        };
        config.log_callback = [](int level, const std::string& message) {
            const int priority = level >= 3 ? ANDROID_LOG_ERROR :
                level == 2 ? ANDROID_LOG_WARN :
                level == 1 ? ANDROID_LOG_INFO : ANDROID_LOG_DEBUG;
            __android_log_print(priority, "retro_runtime", "%s", message.c_str());
        };
        const retro::runtime::CreateResult created = sessionManager().create(config);
        if (created.status == retro::runtime::Result::kOk && created.handle > 0) {
            std::lock_guard lock(bindingsMutex());
            bindings().emplace(created.handle, SessionBindings{std::move(video), std::move(audio), std::move(input)});
        }
        return encodeCreateResult(created);
    } catch (...) {
        return -static_cast<jlong>(retro::runtime::Result::kInternalError);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_codex_libretroplatform_runtime_host_NativeRuntime_nativeSessionClose(
    JNIEnv* /* env */,
    jobject /* instance */,
    const jlong handle) {
    const auto decoded = decodeHandle(handle);
    const jint result = toJniResult(sessionManager().close(decoded));
    if (result == static_cast<jint>(retro::runtime::Result::kOk) ||
        result == static_cast<jint>(retro::runtime::Result::kSessionClosed)) {
        removeBindings(decoded);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_codex_libretroplatform_runtime_host_NativeRuntime_nativeSessionAttachSurface(
    JNIEnv* env,
    jobject /* instance */,
    const jlong handle,
    jobject surface) {
    if (env == nullptr || surface == nullptr) return static_cast<jint>(retro::runtime::Result::kInvalidArgument);
    const auto found = findBindings(decodeHandle(handle));
    if (!found.has_value()) return static_cast<jint>(retro::runtime::Result::kInvalidHandle);
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return static_cast<jint>(retro::runtime::Result::kInvalidArgument);
    found->video->attach(window);
    ANativeWindow_release(window);
    return static_cast<jint>(retro::runtime::Result::kOk);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_codex_libretroplatform_runtime_host_NativeRuntime_nativeSessionDetachSurface(
    JNIEnv* /* env */,
    jobject /* instance */,
    const jlong handle) {
    const auto found = findBindings(decodeHandle(handle));
    if (!found.has_value()) return static_cast<jint>(retro::runtime::Result::kInvalidHandle);
    found->video->detach();
    return static_cast<jint>(retro::runtime::Result::kOk);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_codex_libretroplatform_runtime_host_NativeRuntime_nativeSessionSetInputMask(
    JNIEnv* /* env */,
    jobject /* instance */,
    const jlong handle,
    const jint mask) {
    const auto found = findBindings(decodeHandle(handle));
    if (!found.has_value()) return static_cast<jint>(retro::runtime::Result::kInvalidHandle);
    found->input->setMask(static_cast<std::uint16_t>(mask));
    return static_cast<jint>(retro::runtime::Result::kOk);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_codex_libretroplatform_runtime_host_NativeRuntime_nativeSessionSetAnalog(
    JNIEnv* /* env */,
    jobject /* instance */,
    const jlong handle,
    const jint axis_id,
    const jint value) {
    const auto found = findBindings(decodeHandle(handle));
    if (!found.has_value()) return static_cast<jint>(retro::runtime::Result::kInvalidHandle);
    if (axis_id < 0 || axis_id >= 4) return static_cast<jint>(retro::runtime::Result::kInvalidArgument);
    const auto clamped = std::max(-32768, std::min(32767, value));
    found->input->setAnalog(static_cast<unsigned>(axis_id), static_cast<std::int16_t>(clamped));
    return static_cast<jint>(retro::runtime::Result::kOk);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_codex_libretroplatform_runtime_host_NativeRuntime_nativeSessionReadAudio(
    JNIEnv* env,
    jobject /* instance */,
    const jlong handle,
    jshortArray destination) {
    if (env == nullptr || destination == nullptr) return 0;
    const auto found = findBindings(decodeHandle(handle));
    if (!found.has_value()) return -static_cast<jint>(retro::runtime::Result::kInvalidHandle);
    const jsize length = env->GetArrayLength(destination);
    if (length <= 1) return 0;
    jshort* values = env->GetShortArrayElements(destination, nullptr);
    if (values == nullptr) return 0;
    const std::size_t frames = found->audio->read(reinterpret_cast<std::int16_t*>(values), static_cast<std::size_t>(length / 2));
    env->ReleaseShortArrayElements(destination, values, 0);
    return static_cast<jint>(frames);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_codex_libretroplatform_runtime_host_NativeRuntime_nativeSessionStart(
    JNIEnv* /* env */,
    jobject /* instance */,
    const jlong handle) {
    return toJniResult(sessionManager().start(decodeHandle(handle)));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_codex_libretroplatform_runtime_host_NativeRuntime_nativeSessionPause(
    JNIEnv* /* env */,
    jobject /* instance */,
    const jlong handle) {
    return toJniResult(sessionManager().pause(decodeHandle(handle)));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_codex_libretroplatform_runtime_host_NativeRuntime_nativeSessionResume(
    JNIEnv* /* env */,
    jobject /* instance */,
    const jlong handle) {
    return toJniResult(sessionManager().resume(decodeHandle(handle)));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_codex_libretroplatform_runtime_host_NativeRuntime_nativeSessionAudioSampleRate(
    JNIEnv* /* env */,
    jobject /* instance */,
    const jlong handle) {
    return sessionManager().audioSampleRate(decodeHandle(handle));
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_codex_libretroplatform_runtime_host_NativeRuntime_nativeSessionVideoGeometry(
    JNIEnv* env,
    jobject /* instance */,
    const jlong handle) {
    if (env == nullptr) return nullptr;
    const auto found = findBindings(decodeHandle(handle));
    if (!found.has_value()) return nullptr;
    const auto geometry = found->video->geometry();
    jintArray result = env->NewIntArray(2);
    if (result == nullptr) return nullptr;
    const jint values[] = {static_cast<jint>(geometry.width), static_cast<jint>(geometry.height)};
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_dev_codex_libretroplatform_runtime_host_NativeRuntime_nativeSessionFramesPerSecond(
    JNIEnv* /* env */,
    jobject /* instance */,
    const jlong handle) {
    return sessionManager().framesPerSecond(decodeHandle(handle));
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_codex_libretroplatform_runtime_host_NativeRuntime_nativeSessionPerformance(
    JNIEnv* env,
    jobject /* instance */,
    const jlong handle) {
    if (env == nullptr) return nullptr;
    const auto decoded = decodeHandle(handle);
    const auto session = sessionManager().performance(decoded);
    const auto bound = findBindings(decoded);
    if (!session.has_value() || !bound.has_value()) return nullptr;
    const auto video = bound->video->performance();
    const auto first_video_ns = video.first_posted_at_ns < 0 || session->started_at_ns < 0 ? -1 :
        std::max<std::int64_t>(0, video.first_posted_at_ns - session->started_at_ns);
    // Versioned ABI; all durations are integer nanoseconds. Keep in sync with
    // NativePerformanceSnapshot.kt. Never encode an unavailable value as zero.
    const jlong values[] = {
        1,
        static_cast<jlong>(session->frame_count),
        static_cast<jlong>(session->frames_executed),
        static_cast<jlong>(session->frame_failures),
        session->frame_mean_ns, session->frame_p95_upper_bound_ns, session->frame_max_ns,
        session->startup_ns, session->first_frame_ns, session->session_duration_ns,
        static_cast<jlong>(video.video_callbacks),
        static_cast<jlong>(video.frames_rendered),
        static_cast<jlong>(video.frames_dropped),
        static_cast<jlong>(video.duplicate_frames),
        first_video_ns,
    };
    constexpr jsize size = sizeof(values) / sizeof(values[0]);
    auto result = env->NewLongArray(size);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, size, values);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_codex_libretroplatform_runtime_host_NativeRuntime_nativeSessionSaveNative(
    JNIEnv* env,
    jobject /* instance */,
    const jlong handle,
    jstring path) {
    std::string decoded;
    if (!copyJniString(env, path, &decoded, false)) {
        return toJniResult(retro::runtime::Result::kInvalidArgument);
    }
    return toJniResult(sessionManager().saveNative(decodeHandle(handle), decoded));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_codex_libretroplatform_runtime_host_NativeRuntime_nativeSessionLoadNative(
    JNIEnv* env,
    jobject /* instance */,
    const jlong handle,
    jstring path) {
    std::string decoded;
    if (!copyJniString(env, path, &decoded, false)) {
        return toJniResult(retro::runtime::Result::kInvalidArgument);
    }
    return toJniResult(sessionManager().loadNative(decodeHandle(handle), decoded));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_codex_libretroplatform_runtime_host_NativeRuntime_nativeSessionSaveState(
    JNIEnv* env,
    jobject /* instance */,
    const jlong handle,
    jstring path) {
    std::string decoded;
    if (!copyJniString(env, path, &decoded, false)) {
        return toJniResult(retro::runtime::Result::kInvalidArgument);
    }
    return toJniResult(sessionManager().saveState(decodeHandle(handle), decoded));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_codex_libretroplatform_runtime_host_NativeRuntime_nativeSessionLoadState(
    JNIEnv* env,
    jobject /* instance */,
    const jlong handle,
    jstring path) {
    std::string decoded;
    if (!copyJniString(env, path, &decoded, false)) {
        return toJniResult(retro::runtime::Result::kInvalidArgument);
    }
    return toJniResult(sessionManager().loadState(decodeHandle(handle), decoded));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_codex_libretroplatform_runtime_host_NativeRuntime_nativeSessionRunFrame(
    JNIEnv* /* env */,
    jobject /* instance */,
    const jlong handle) {
    return toJniResult(sessionManager().runFrame(decodeHandle(handle)));
}
