#include "libretro_core.h"

#include <atomic>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <mutex>
#include <thread>
#include <cmath>

namespace {

using retro::runtime::AudioSamples;
using retro::runtime::CoreOperations;
using retro::runtime::CreateResult;
using retro::runtime::LibretroPixelFormat;
using retro::runtime::LibretroSessionConfig;
using retro::runtime::Result;
using retro::runtime::Session;
using retro::runtime::SessionManager;
using retro::runtime::VideoFrame;

void expect(const bool condition, const char* message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(EXIT_FAILURE);
    }
}

}  // namespace

int main(int argc, char** argv) {
    expect(argc == 2, "synthetic core path is provided by the test runner");

    const std::filesystem::path content_path =
        std::filesystem::temp_directory_path() / "retro-runtime-synthetic-content.bin";
    {
        std::ofstream output(content_path, std::ios::binary | std::ios::trunc);
        expect(static_cast<bool>(output), "synthetic content file opens");
        output.write("SYNTHETIC-CONTENT", 18);
        expect(static_cast<bool>(output), "synthetic content file writes");
    }

    std::mutex callback_mutex;
    std::thread::id callback_thread;
    unsigned video_frames = 0;
    unsigned audio_frames = 0;
    unsigned input_polls = 0;
    unsigned input_queries = 0;
    bool saw_message = false;
    bool saw_log = false;
    VideoFrame last_video;
    AudioSamples last_audio;

    LibretroSessionConfig config;
    config.core_path = argv[1];
    config.content_path = content_path.string();
    config.system_directory = "/synthetic/system";
    config.save_directory = "/synthetic/save";
    config.video_callback = [&](const VideoFrame& frame) {
        std::lock_guard lock(callback_mutex);
        callback_thread = std::this_thread::get_id();
        ++video_frames;
        last_video = frame;
    };
    config.audio_callback = [&](const AudioSamples& samples) {
        std::lock_guard lock(callback_mutex);
        callback_thread = std::this_thread::get_id();
        audio_frames += static_cast<unsigned>(samples.frames);
        last_audio = samples;
    };
    config.input_poll_callback = [&] {
        std::lock_guard lock(callback_mutex);
        callback_thread = std::this_thread::get_id();
        ++input_polls;
    };
    config.input_state_callback = [&](unsigned, unsigned, unsigned, unsigned id) -> std::int16_t {
        std::lock_guard lock(callback_mutex);
        callback_thread = std::this_thread::get_id();
        ++input_queries;
        return id == RETRO_DEVICE_ID_JOYPAD_A ? 1 : 0;
    };
    config.message_callback = [&](const std::string&, unsigned) { saw_message = true; };
    config.log_callback = [&](int, const std::string&) { saw_log = true; };

    SessionManager manager;
    const CreateResult created = manager.create(config);
    expect(created.status == Result::kOk, "dynamic synthetic core creates");
    expect(manager.start(created.handle) == Result::kOk, "core initializes and loads content");
    expect(manager.snapshot(created.handle).value() == retro::runtime::Lifecycle::kRunning,
           "loaded core enters running state");
    expect(std::abs(manager.framesPerSecond(created.handle) - 60.0) < 0.001,
           "reported core timing reaches the session host");
    expect(manager.runFrame(created.handle) == Result::kOk, "retro_run executes on worker");
    const auto performance = manager.performance(created.handle);
    expect(performance.has_value(), "runtime performance snapshot is available");
    expect(performance->frame_count == 1 && performance->frames_executed == 1 &&
               performance->frame_failures == 0,
           "successful frame is counted without a synthetic estimate");
    expect(performance->startup_ns >= 0 && performance->first_frame_ns >= 0 &&
               performance->frame_mean_ns >= 0 && performance->frame_p95_upper_bound_ns >= 0,
           "startup and frame timing fields are measured");

    {
        std::lock_guard lock(callback_mutex);
        expect(video_frames == 1, "video callback receives one frame");
        expect(last_video.width == 2 && last_video.height == 2, "video dimensions cross ABI boundary");
        expect(last_video.pitch == 8 && last_video.pixels.size() == 16, "video data is copied with pitch");
        expect(last_video.format == LibretroPixelFormat::kXrgb8888, "pixel format negotiation is retained");
        expect(audio_frames == 2 && last_audio.interleaved_stereo.size() == 4,
               "audio batch callback receives stereo samples");
        expect(input_polls == 1 && input_queries == 1, "input callbacks cross ABI boundary");
        expect(callback_thread != std::this_thread::get_id(), "callbacks run off the caller thread");
    }
    expect(saw_message, "environment message callback is handled");
    expect(saw_log, "environment log callback is handled");

    const std::filesystem::path native_save_path =
        std::filesystem::temp_directory_path() / "retro-runtime-synthetic-save.bin";
    const std::filesystem::path state_path =
        std::filesystem::temp_directory_path() / "retro-runtime-synthetic-state.bin";
    expect(manager.saveNative(created.handle, native_save_path.string()) == Result::kOk,
           "native save payload is written on the core thread");
    expect(manager.saveState(created.handle, state_path.string()) == Result::kOk,
           "serialized state payload is written on the core thread");
    expect(std::filesystem::file_size(native_save_path) == 4, "native save payload size is bounded");
    expect(std::filesystem::file_size(state_path) > 0, "save state payload is non-empty");
    expect(manager.loadState(created.handle, state_path.string()) == Result::kOk,
           "serialized state payload is loaded on the core thread");

    expect(manager.pause(created.handle) == Result::kOk, "loaded core pauses");
    expect(manager.runFrame(created.handle) == Result::kInvalidState, "paused core rejects frame execution");
    expect(manager.resume(created.handle) == Result::kOk, "loaded core resumes");
    expect(manager.close(created.handle) == Result::kOk, "dynamic core unloads and closes");
    expect(manager.close(created.handle) == Result::kInvalidHandle, "closed dynamic handle is removed");

    LibretroSessionConfig missing_core = config;
    missing_core.core_path += ".missing";
    const CreateResult missing = manager.create(missing_core);
    expect(missing.handle == 0 && missing.status == Result::kCoreLoadFailed,
           "missing core path maps deterministically");

    LibretroSessionConfig missing_content = config;
    missing_content.content_path += ".missing";
    const CreateResult content_session = manager.create(missing_content);
    expect(content_session.status == Result::kOk, "content is opened by the emulation thread");
    expect(manager.start(content_session.handle) == Result::kContentLoadFailed,
           "missing content maps deterministically");
    expect(manager.close(content_session.handle) == Result::kOk, "failed load still closes cleanly");

    // Regression test for the old self-wait/self-join deadlock: a callback
    // requests close from the worker, then an external caller waits for the
    // same close to finish.
    Session* raw_session = nullptr;
    std::atomic<unsigned> unloads{0};
    CoreOperations reentrant{
        []() { return Result::kOk; },
        [&]() {
            ++unloads;
            return Result::kOk;
        },
        [&]() { return raw_session->close(); },
    };
    auto reentrant_session = std::make_shared<Session>(std::move(reentrant));
    raw_session = reentrant_session.get();
    expect(reentrant_session->start() == Result::kOk, "reentrant test session starts");
    expect(reentrant_session->runFrame() == Result::kOk, "worker callback can request close safely");
    const Result reentrant_close = reentrant_session->close();
    expect(reentrant_close == Result::kOk || reentrant_close == Result::kSessionClosed,
           "external close observes worker-requested close");
    expect(unloads == 1, "reentrant close unloads once");

    std::error_code remove_error;
    std::filesystem::remove(content_path, remove_error);
    expect(!remove_error, "temporary synthetic content is removed");
    std::filesystem::remove(native_save_path, remove_error);
    expect(!remove_error, "temporary native save is removed");
    std::filesystem::remove(state_path, remove_error);
    expect(!remove_error, "temporary save state is removed");

    std::cout << "retro_runtime_harness: PASS\n";
    return EXIT_SUCCESS;
}
