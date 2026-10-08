#pragma once

#include "session_performance.h"

#include <atomic>
#include <cstdint>
#include <condition_variable>
#include <deque>
#include <functional>
#include <future>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <utility>
#include <unordered_map>

namespace retro::runtime {

struct LibretroSessionConfig;

// JNI command results are stable numeric values. A JNI create call returns a
// positive handle on success or the negated value of one of these results on
// failure, so creation failures do not require a second error channel.
enum class Result : std::int32_t {
    kOk = 0,
    kInvalidHandle = 1,
    kInvalidState = 2,
    kSessionClosed = 3,
    // Retained for callers compiled against the initial placeholder runtime.
    kLibretroLoadingNotImplemented = 4,
    kInternalError = 5,
    kInvalidArgument = 6,
    kCoreLoadFailed = 7,
    kCoreMissingSymbol = 8,
    kCoreApiMismatch = 9,
    kContentLoadFailed = 10,
    kCoreOperationFailed = 11,
    kUnsupported = 12,
};

const char* resultName(Result result) noexcept;

enum class Lifecycle : std::int32_t {
    kCreated = 0,
    kRunning = 1,
    kPaused = 2,
    kClosed = 3,
};

using Handle = std::uint64_t;

// The host depends only on these operations. All operations are invoked on
// the session's dedicated worker thread. The initial two fields preserve the
// existing injection seam; run_frame is optional for lifecycle-only fakes.
struct CoreOperations {
    std::function<Result()> load;
    std::function<Result()> unload;
    std::function<Result()> run_frame;
    std::function<Result(const std::string&)> save_native;
    std::function<Result(const std::string&)> load_native;
    std::function<Result(const std::string&)> save_state;
    std::function<Result(const std::string&)> load_state;
    std::function<double()> audio_sample_rate;
    std::function<double()> frames_per_second;

    static CoreOperations unimplemented();
};

struct CreateResult {
    Handle handle = 0;
    Result status = Result::kInternalError;
};

class Session final {
public:
    explicit Session(CoreOperations operations = CoreOperations::unimplemented());
    ~Session();

    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;

    Result start() noexcept;
    Result pause() noexcept;
    Result resume() noexcept;
    Result runFrame() noexcept;
    Result saveNative(const std::string& path) noexcept;
    Result loadNative(const std::string& path) noexcept;
    Result saveState(const std::string& path) noexcept;
    Result loadState(const std::string& path) noexcept;
    Result close() noexcept;
    int audioSampleRate() const noexcept;
    double framesPerSecond() const noexcept;
    PerformanceSnapshot performance() const noexcept;

    Lifecycle snapshot() const noexcept;

private:
    enum class CommandType : std::uint8_t {
        kStart,
        kPause,
        kResume,
        kRunFrame,
        kSaveNative,
        kLoadNative,
        kSaveState,
        kLoadState,
        kClose,
    };

    struct Command {
        CommandType type;
        std::string path;
        std::promise<Result> completion;
    };

    struct State {
        explicit State(CoreOperations operations_in) : operations(std::move(operations_in)) {}

        CoreOperations operations;
        std::atomic<Lifecycle> lifecycle{Lifecycle::kCreated};
        std::atomic<int> audio_sample_rate_hz{44100};
        std::atomic<double> frames_per_second{0.0};
        SessionPerformance performance;

        std::mutex queue_mutex;
        std::condition_variable queue_cv;
        std::deque<Command> queue;
        bool close_enqueued = false;
        std::atomic<bool> close_requested{false};

        std::mutex worker_mutex;
        std::condition_variable worker_done_cv;
        std::thread::id worker_id;
        std::atomic<bool> worker_done{false};
        Result close_result = Result::kInternalError;
    };

    Result submit(CommandType type) noexcept;
    Result submit(CommandType type, const std::string& path) noexcept;
    Result requestCloseFromWorker() noexcept;
    static Result process(State& state, CommandType type, const std::string& path) noexcept;
    static void run(std::shared_ptr<State> state) noexcept;
    static bool isWorkerThread(const std::shared_ptr<State>& state) noexcept;

    std::shared_ptr<State> state_;
    std::mutex close_mutex_;
};

class SessionManager final {
public:
    explicit SessionManager(CoreOperations operations = CoreOperations::unimplemented());
    ~SessionManager() = default;

    CreateResult create() noexcept;
    CreateResult create(const LibretroSessionConfig& config) noexcept;
    Result close(Handle handle) noexcept;
    Result start(Handle handle) noexcept;
    Result pause(Handle handle) noexcept;
    Result resume(Handle handle) noexcept;
    Result runFrame(Handle handle) noexcept;
    Result saveNative(Handle handle, const std::string& path) noexcept;
    Result loadNative(Handle handle, const std::string& path) noexcept;
    Result saveState(Handle handle, const std::string& path) noexcept;
    Result loadState(Handle handle, const std::string& path) noexcept;
    int audioSampleRate(Handle handle) const noexcept;
    double framesPerSecond(Handle handle) const noexcept;
    std::optional<PerformanceSnapshot> performance(Handle handle) const noexcept;

    // Snapshot is intentionally a C++ test/diagnostic seam; it is not exposed
    // through JNI so the platform API remains small.
    std::optional<Lifecycle> snapshot(Handle handle) const noexcept;

private:
    std::shared_ptr<Session> find(Handle handle) const noexcept;

    CoreOperations operations_;
    mutable std::mutex sessions_mutex_;
    std::unordered_map<Handle, std::shared_ptr<Session>> sessions_;
    std::atomic<Handle> next_handle_{1};
};

const char* buildId() noexcept;

}  // namespace retro::runtime
