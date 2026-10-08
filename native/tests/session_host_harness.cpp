#include "../src/session_host.h"

#include <atomic>
#include <barrier>
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <mutex>
#include <thread>
#include <vector>

namespace {

using retro::runtime::CoreOperations;
using retro::runtime::CreateResult;
using retro::runtime::Lifecycle;
using retro::runtime::Result;
using retro::runtime::SessionManager;

void expect(const bool condition, const char* message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(EXIT_FAILURE);
    }
}

}  // namespace

int main() {
    // The production dependency is deliberately honest: no core is loaded.
    SessionManager production;
    const CreateResult unimplemented = production.create();
    expect(unimplemented.status == Result::kOk, "production session creates");
    expect(production.start(unimplemented.handle) == Result::kLibretroLoadingNotImplemented,
           "missing Libretro loader is a typed failure");
    expect(production.snapshot(unimplemented.handle).value() == Lifecycle::kCreated,
           "failed start does not enter running state");
    expect(production.close(unimplemented.handle) == Result::kOk,
           "created production session closes");
    expect(production.close(unimplemented.handle) == Result::kInvalidHandle,
           "closed handle is removed from manager");

    std::atomic<int> loads{0};
    std::atomic<int> unloads{0};
    std::mutex callback_mutex;
    std::thread::id load_thread;
    CoreOperations fake{
        [&]() {
            ++loads;
            std::lock_guard lock(callback_mutex);
            load_thread = std::this_thread::get_id();
            return Result::kOk;
        },
        [&]() {
            ++unloads;
            return Result::kOk;
        },
        []() { return Result::kOk; },
    };

    SessionManager manager(std::move(fake));
    const CreateResult created = manager.create();
    expect(created.status == Result::kOk, "fake session creates");
    expect(manager.snapshot(created.handle).value() == Lifecycle::kCreated,
           "new session starts created");
    expect(manager.start(created.handle) == Result::kOk, "start transitions to running");
    expect(manager.snapshot(created.handle).value() == Lifecycle::kRunning,
           "running state is observable");
    expect(loads == 1, "loader is called once");
    {
        std::lock_guard lock(callback_mutex);
        expect(load_thread != std::this_thread::get_id(), "loader runs on dedicated session thread");
    }
    expect(manager.start(created.handle) == Result::kInvalidState,
           "second start is rejected");
    expect(manager.pause(created.handle) == Result::kOk, "pause transitions to paused");
    expect(manager.pause(created.handle) == Result::kInvalidState,
           "second pause is rejected");
    expect(manager.resume(created.handle) == Result::kOk, "resume transitions to running");
    expect(manager.resume(created.handle) == Result::kInvalidState,
           "second resume is rejected");
    expect(manager.close(created.handle) == Result::kOk, "running session closes");
    expect(unloads == 1, "unloader is called once");
    expect(manager.snapshot(created.handle) == std::nullopt, "closed handle is no longer visible");
    expect(manager.pause(created.handle) == Result::kInvalidHandle,
           "commands on removed handle are safe errors");

    // Concurrent callers still serialize through the one session worker. The
    // exact winning caller is nondeterministic, but only one start may load.
    constexpr std::size_t concurrent_callers = 8;
    std::atomic<int> active_loads{0};
    std::atomic<int> max_active_loads{0};
    std::atomic<int> concurrent_loads{0};
    CoreOperations serialized{
        [&]() {
            const int active = active_loads.fetch_add(1) + 1;
            concurrent_loads.fetch_add(1);
            int observed_max = max_active_loads.load();
            while (active > observed_max &&
                   !max_active_loads.compare_exchange_weak(observed_max, active)) {
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
            active_loads.fetch_sub(1);
            return Result::kOk;
        },
        []() { return Result::kOk; },
        []() { return Result::kOk; },
    };
    SessionManager serialized_manager(std::move(serialized));
    const CreateResult serialized_session = serialized_manager.create();
    std::barrier gate(static_cast<std::ptrdiff_t>(concurrent_callers + 1));
    std::vector<Result> concurrent_results(concurrent_callers, Result::kInternalError);
    std::vector<std::thread> callers;
    callers.reserve(concurrent_callers);
    for (std::size_t index = 0; index < concurrent_callers; ++index) {
        callers.emplace_back([&, index] {
            gate.arrive_and_wait();
            concurrent_results[index] = serialized_manager.start(serialized_session.handle);
        });
    }
    gate.arrive_and_wait();
    for (auto& caller : callers) {
        caller.join();
    }
    int successful_starts = 0;
    int rejected_starts = 0;
    for (const Result result : concurrent_results) {
        successful_starts += result == Result::kOk;
        rejected_starts += result == Result::kInvalidState;
    }
    expect(successful_starts == 1, "concurrent starts have one serialized winner");
    expect(rejected_starts == static_cast<int>(concurrent_callers - 1),
           "concurrent starts reject later lifecycle transitions");
    expect(concurrent_loads == 1, "concurrent starts load exactly once");
    expect(max_active_loads == 1, "core loading is never concurrent");
    expect(serialized_manager.close(serialized_session.handle) == Result::kOk,
           "serialized session closes");

    std::cout << "session_host_harness: PASS\n";
    return EXIT_SUCCESS;
}
