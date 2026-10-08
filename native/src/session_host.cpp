#include "session_host.h"

#include "libretro_core.h"

#include <cmath>
#include <utility>

namespace retro::runtime {

namespace {

constexpr const char* kBuildId = "retro-runtime-session-host/2;libretro-loader=dynamic";

Result safeOperation(const std::function<Result()>& operation) noexcept {
    try {
        if (!operation) {
            return Result::kInternalError;
        }
        return operation();
    } catch (...) {
        return Result::kInternalError;
    }
}

}  // namespace

const char* resultName(const Result result) noexcept {
    switch (result) {
        case Result::kOk:
            return "ok";
        case Result::kInvalidHandle:
            return "invalid_handle";
        case Result::kInvalidState:
            return "invalid_state";
        case Result::kSessionClosed:
            return "session_closed";
        case Result::kLibretroLoadingNotImplemented:
            return "libretro_loading_not_implemented";
        case Result::kInternalError:
            return "internal_error";
        case Result::kInvalidArgument:
            return "invalid_argument";
        case Result::kCoreLoadFailed:
            return "core_load_failed";
        case Result::kCoreMissingSymbol:
            return "core_missing_symbol";
        case Result::kCoreApiMismatch:
            return "core_api_mismatch";
        case Result::kContentLoadFailed:
            return "content_load_failed";
        case Result::kCoreOperationFailed:
            return "core_operation_failed";
        case Result::kUnsupported:
            return "unsupported";
    }
    return "unknown";
}

CoreOperations CoreOperations::unimplemented() {
    return CoreOperations{
        []() noexcept { return Result::kLibretroLoadingNotImplemented; },
        []() noexcept { return Result::kOk; },
        []() noexcept { return Result::kLibretroLoadingNotImplemented; },
        [](const std::string&) noexcept { return Result::kUnsupported; },
        [](const std::string&) noexcept { return Result::kUnsupported; },
        [](const std::string&) noexcept { return Result::kUnsupported; },
        [](const std::string&) noexcept { return Result::kUnsupported; },
    };
}

Session::Session(CoreOperations operations)
    : state_(std::make_shared<State>(std::move(operations))) {
    const std::shared_ptr<State> state = state_;
    std::thread([state]() noexcept { Session::run(state); }).detach();
}

Session::~Session() {
    // State is captured by the detached worker, so this is safe even when a
    // core callback caused the last Session reference to be released on the
    // emulation thread. close() waits for an external caller and only requests
    // shutdown when called re-entrantly by the worker itself.
    (void)close();
}

Lifecycle Session::snapshot() const noexcept {
    const std::shared_ptr<State> state = state_;
    return state == nullptr ? Lifecycle::kClosed : state->lifecycle.load(std::memory_order_acquire);
}

int Session::audioSampleRate() const noexcept {
    const std::shared_ptr<State> state = state_;
    return state == nullptr ? 44100 : state->audio_sample_rate_hz.load(std::memory_order_acquire);
}

PerformanceSnapshot Session::performance() const noexcept {
    return state_->performance.snapshot(monotonicNanos());
}

double Session::framesPerSecond() const noexcept {
    return state_->frames_per_second.load(std::memory_order_acquire);
}

Result Session::start() noexcept {
    return submit(CommandType::kStart);
}

Result Session::pause() noexcept {
    return submit(CommandType::kPause);
}

Result Session::resume() noexcept {
    return submit(CommandType::kResume);
}

Result Session::runFrame() noexcept {
    return submit(CommandType::kRunFrame);
}

Result Session::saveNative(const std::string& path) noexcept {
    return submit(CommandType::kSaveNative, path);
}

Result Session::loadNative(const std::string& path) noexcept {
    return submit(CommandType::kLoadNative, path);
}

Result Session::saveState(const std::string& path) noexcept {
    return submit(CommandType::kSaveState, path);
}

Result Session::loadState(const std::string& path) noexcept {
    return submit(CommandType::kLoadState, path);
}

bool Session::isWorkerThread(const std::shared_ptr<State>& state) noexcept {
    if (state == nullptr) return false;
    std::lock_guard lock(state->worker_mutex);
    return !state->worker_done && state->worker_id == std::this_thread::get_id();
}

Result Session::requestCloseFromWorker() noexcept {
    const std::shared_ptr<State> state = state_;
    if (state == nullptr) return Result::kSessionClosed;
    {
        std::lock_guard queue_lock(state->queue_mutex);
        if (state->worker_done || state->lifecycle.load(std::memory_order_acquire) == Lifecycle::kClosed) {
            return Result::kSessionClosed;
        }
        state->close_enqueued = true;
        // The worker observes this flag even if allocating a queue node would
        // fail. That makes the shutdown path bounded and allocation-free.
    }
    state->close_requested.store(true, std::memory_order_release);
    state->queue_cv.notify_one();
    return Result::kOk;
}

Result Session::close() noexcept {
    const std::shared_ptr<State> state = state_;
    if (state == nullptr) return Result::kSessionClosed;

    // Never wait for or join the worker from a Libretro callback. The worker
    // will drain already queued commands and synthesize the close command.
    if (isWorkerThread(state)) {
        return requestCloseFromWorker();
    }

    std::lock_guard close_lock(close_mutex_);
    {
        std::lock_guard queue_lock(state->queue_mutex);
        if (state->worker_done) {
            return Result::kSessionClosed;
        }
        state->close_enqueued = true;
        state->close_requested.store(true, std::memory_order_release);
    }
    state->queue_cv.notify_one();

    std::unique_lock worker_lock(state->worker_mutex);
    state->worker_done_cv.wait(worker_lock, [&state] {
        return state->worker_done.load(std::memory_order_acquire);
    });
    return state->close_result;
}

Result Session::submit(const CommandType type) noexcept {
    const std::shared_ptr<State> state = state_;
    if (state == nullptr) return Result::kSessionClosed;
    if (isWorkerThread(state)) {
        // Synchronous re-entry would wait on the command currently being
        // processed. Callers must request lifecycle changes from outside a
        // Libretro callback; close() is the one safe re-entrant exception.
        return Result::kInvalidState;
    }

    try {
        std::promise<Result> promise;
        std::future<Result> completion = promise.get_future();
        {
            std::lock_guard queue_lock(state->queue_mutex);
            if (state->close_enqueued || state->lifecycle.load(std::memory_order_acquire) == Lifecycle::kClosed) {
                return Result::kSessionClosed;
            }
            state->queue.push_back(Command{type, {}, std::move(promise)});
        }
        state->queue_cv.notify_one();
        return completion.get();
    } catch (...) {
        return Result::kInternalError;
    }
}

Result Session::submit(const CommandType type, const std::string& path) noexcept {
    const std::shared_ptr<State> state = state_;
    if (state == nullptr) return Result::kSessionClosed;
    if (isWorkerThread(state)) return Result::kInvalidState;

    try {
        std::promise<Result> promise;
        std::future<Result> completion = promise.get_future();
        {
            std::lock_guard queue_lock(state->queue_mutex);
            if (state->close_enqueued || state->lifecycle.load(std::memory_order_acquire) == Lifecycle::kClosed) {
                return Result::kSessionClosed;
            }
            state->queue.push_back(Command{type, path, std::move(promise)});
        }
        state->queue_cv.notify_one();
        return completion.get();
    } catch (...) {
        return Result::kInternalError;
    }
}

Result Session::process(State& state, const CommandType type, const std::string& path) noexcept {
    switch (type) {
        case CommandType::kStart: {
            if (state.lifecycle.load(std::memory_order_relaxed) != Lifecycle::kCreated) {
                return Result::kInvalidState;
            }
            state.performance.begin(monotonicNanos());
            const Result result = safeOperation(state.operations.load);
            state.performance.loaded(monotonicNanos(), result == Result::kOk);
            if (result == Result::kOk) {
                if (state.operations.audio_sample_rate) {
                    try {
                        const double sample_rate = state.operations.audio_sample_rate();
                        if (std::isfinite(sample_rate) && sample_rate >= 8000.0 && sample_rate <= 192000.0) {
                            state.audio_sample_rate_hz.store(static_cast<int>(sample_rate + 0.5), std::memory_order_release);
                        }
                    } catch (...) {
                        // Keep the safe 44.1 kHz default if timing metadata is unavailable.
                    }
                }
                if (state.operations.frames_per_second) {
                    try {
                        const double fps = state.operations.frames_per_second();
                        if (std::isfinite(fps) && fps >= 1.0 && fps <= 1000.0) {
                            state.frames_per_second.store(fps, std::memory_order_release);
                        }
                    } catch (...) {
                        // Zero explicitly signals unavailable metadata to JNI.
                    }
                }
                state.lifecycle.store(Lifecycle::kRunning, std::memory_order_release);
            }
            return result;
        }
        case CommandType::kPause:
            if (state.lifecycle.load(std::memory_order_relaxed) != Lifecycle::kRunning) {
                return Result::kInvalidState;
            }
            state.lifecycle.store(Lifecycle::kPaused, std::memory_order_release);
            return Result::kOk;
        case CommandType::kResume:
            if (state.lifecycle.load(std::memory_order_relaxed) != Lifecycle::kPaused) {
                return Result::kInvalidState;
            }
            state.lifecycle.store(Lifecycle::kRunning, std::memory_order_release);
            return Result::kOk;
        case CommandType::kRunFrame: {
            if (state.lifecycle.load(std::memory_order_relaxed) != Lifecycle::kRunning) {
                return Result::kInvalidState;
            }
            const auto started_at = monotonicNanos();
            const auto result = safeOperation(state.operations.run_frame);
            const auto ended_at = monotonicNanos();
            state.performance.frame(ended_at - started_at, ended_at, result == Result::kOk);
            return result;
        }
        case CommandType::kSaveNative:
            if (state.lifecycle.load(std::memory_order_relaxed) != Lifecycle::kRunning &&
                state.lifecycle.load(std::memory_order_relaxed) != Lifecycle::kPaused) {
                return Result::kInvalidState;
            }
            if (!state.operations.save_native) return Result::kUnsupported;
            try {
                return state.operations.save_native(path);
            } catch (...) {
                return Result::kInternalError;
            }
        case CommandType::kLoadNative:
            if (state.lifecycle.load(std::memory_order_relaxed) != Lifecycle::kRunning &&
                state.lifecycle.load(std::memory_order_relaxed) != Lifecycle::kPaused) {
                return Result::kInvalidState;
            }
            if (!state.operations.load_native) return Result::kUnsupported;
            try {
                return state.operations.load_native(path);
            } catch (...) {
                return Result::kInternalError;
            }
        case CommandType::kSaveState:
            if (state.lifecycle.load(std::memory_order_relaxed) != Lifecycle::kRunning &&
                state.lifecycle.load(std::memory_order_relaxed) != Lifecycle::kPaused) {
                return Result::kInvalidState;
            }
            if (!state.operations.save_state) return Result::kUnsupported;
            try {
                return state.operations.save_state(path);
            } catch (...) {
                return Result::kInternalError;
            }
        case CommandType::kLoadState:
            if (state.lifecycle.load(std::memory_order_relaxed) != Lifecycle::kRunning &&
                state.lifecycle.load(std::memory_order_relaxed) != Lifecycle::kPaused) {
                return Result::kInvalidState;
            }
            if (!state.operations.load_state) return Result::kUnsupported;
            try {
                return state.operations.load_state(path);
            } catch (...) {
                return Result::kInternalError;
            }
        case CommandType::kClose: {
            const Lifecycle prior = state.lifecycle.load(std::memory_order_relaxed);
            Result result = Result::kOk;
            if (prior == Lifecycle::kRunning || prior == Lifecycle::kPaused) {
                result = safeOperation(state.operations.unload);
            }
            state.lifecycle.store(Lifecycle::kClosed, std::memory_order_release);
            state.performance.stop(monotonicNanos());
            return result;
        }
    }
    return Result::kInternalError;
}

void Session::run(std::shared_ptr<State> state) noexcept {
    {
        std::lock_guard worker_lock(state->worker_mutex);
        state->worker_id = std::this_thread::get_id();
    }

    Result terminal_result = Result::kOk;
    try {
        for (;;) {
            Command command{CommandType::kClose, {}, std::promise<Result>()};
            {
                std::unique_lock queue_lock(state->queue_mutex);
                state->queue_cv.wait(queue_lock, [&state] {
                    return !state->queue.empty() || state->close_requested.load(std::memory_order_acquire);
                });
                if (state->queue.empty()) {
                    command.type = CommandType::kClose;
                } else {
                    command = std::move(state->queue.front());
                    state->queue.pop_front();
                }
            }

            const bool should_stop = command.type == CommandType::kClose;
            const Result result = process(*state, command.type, command.path);
            if (should_stop) terminal_result = result;
            try {
                command.completion.set_value(result);
            } catch (...) {
                // A caller that abandoned its future must not compromise the
                // worker's teardown path.
            }
            if (should_stop) break;
        }
    } catch (...) {
        terminal_result = Result::kInternalError;
        state->performance.stop(monotonicNanos());
        state->lifecycle.store(Lifecycle::kClosed, std::memory_order_release);
        // Wake any synchronous command callers that were queued when an
        // unexpected allocation/core exception escaped the worker loop.
        std::deque<Command> abandoned;
        {
            std::lock_guard queue_lock(state->queue_mutex);
            abandoned.swap(state->queue);
        }
        for (auto& command : abandoned) {
            try {
                command.completion.set_value(Result::kInternalError);
            } catch (...) {
            }
        }
    }

    {
        std::lock_guard worker_lock(state->worker_mutex);
        state->close_result = terminal_result;
        state->worker_done = true;
        state->worker_id = std::thread::id{};
    }
    state->worker_done_cv.notify_all();
}

SessionManager::SessionManager(CoreOperations operations)
    : operations_(std::move(operations)) {}

CreateResult SessionManager::create() noexcept {
    try {
        auto session = std::make_shared<Session>(operations_);
        std::lock_guard lock(sessions_mutex_);

        Handle handle = next_handle_.fetch_add(1, std::memory_order_relaxed);
        if (handle == 0) {
            handle = next_handle_.fetch_add(1, std::memory_order_relaxed);
        }
        while (handle == 0 || sessions_.contains(handle)) {
            handle = next_handle_.fetch_add(1, std::memory_order_relaxed);
            if (handle == 0) {
                return CreateResult{0, Result::kInternalError};
            }
        }
        sessions_.emplace(handle, std::move(session));
        return CreateResult{handle, Result::kOk};
    } catch (...) {
        return CreateResult{0, Result::kInternalError};
    }
}

CreateResult SessionManager::create(const LibretroSessionConfig& config) noexcept {
    try {
        Result status = Result::kInternalError;
        CoreOperations operations = makeLibretroOperations(config, &status);
        if (status != Result::kOk) {
            return CreateResult{0, status};
        }
        auto session = std::make_shared<Session>(std::move(operations));
        std::lock_guard lock(sessions_mutex_);

        Handle handle = next_handle_.fetch_add(1, std::memory_order_relaxed);
        if (handle == 0) handle = next_handle_.fetch_add(1, std::memory_order_relaxed);
        while (handle == 0 || sessions_.contains(handle)) {
            handle = next_handle_.fetch_add(1, std::memory_order_relaxed);
            if (handle == 0) return CreateResult{0, Result::kInternalError};
        }
        sessions_.emplace(handle, std::move(session));
        return CreateResult{handle, Result::kOk};
    } catch (...) {
        return CreateResult{0, Result::kInternalError};
    }
}

std::shared_ptr<Session> SessionManager::find(const Handle handle) const noexcept {
    if (handle == 0) {
        return nullptr;
    }
    std::lock_guard lock(sessions_mutex_);
    const auto found = sessions_.find(handle);
    return found == sessions_.end() ? nullptr : found->second;
}

Result SessionManager::close(const Handle handle) noexcept {
    const auto session = find(handle);
    if (!session) {
        return Result::kInvalidHandle;
    }
    const Result result = session->close();
    std::lock_guard lock(sessions_mutex_);
    sessions_.erase(handle);
    return result;
}

Result SessionManager::start(const Handle handle) noexcept {
    const auto session = find(handle);
    return session ? session->start() : Result::kInvalidHandle;
}

Result SessionManager::pause(const Handle handle) noexcept {
    const auto session = find(handle);
    return session ? session->pause() : Result::kInvalidHandle;
}

Result SessionManager::resume(const Handle handle) noexcept {
    const auto session = find(handle);
    return session ? session->resume() : Result::kInvalidHandle;
}

Result SessionManager::runFrame(const Handle handle) noexcept {
    const auto session = find(handle);
    return session ? session->runFrame() : Result::kInvalidHandle;
}

Result SessionManager::saveNative(const Handle handle, const std::string& path) noexcept {
    const auto session = find(handle);
    return session ? session->saveNative(path) : Result::kInvalidHandle;
}

Result SessionManager::loadNative(const Handle handle, const std::string& path) noexcept {
    const auto session = find(handle);
    return session ? session->loadNative(path) : Result::kInvalidHandle;
}

Result SessionManager::saveState(const Handle handle, const std::string& path) noexcept {
    const auto session = find(handle);
    return session ? session->saveState(path) : Result::kInvalidHandle;
}

Result SessionManager::loadState(const Handle handle, const std::string& path) noexcept {
    const auto session = find(handle);
    return session ? session->loadState(path) : Result::kInvalidHandle;
}

int SessionManager::audioSampleRate(const Handle handle) const noexcept {
    const auto session = find(handle);
    return session ? session->audioSampleRate() : 0;
}

std::optional<PerformanceSnapshot> SessionManager::performance(const Handle handle) const noexcept {
    const auto session = find(handle);
    return session ? std::optional<PerformanceSnapshot>(session->performance()) : std::nullopt;
}

double SessionManager::framesPerSecond(const Handle handle) const noexcept {
    const auto session = find(handle);
    return session ? session->framesPerSecond() : 0.0;
}

std::optional<Lifecycle> SessionManager::snapshot(const Handle handle) const noexcept {
    const auto session = find(handle);
    return session ? std::optional<Lifecycle>(session->snapshot()) : std::nullopt;
}

const char* buildId() noexcept {
    return kBuildId;
}

}  // namespace retro::runtime
