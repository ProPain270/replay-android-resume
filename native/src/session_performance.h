#pragma once

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <mutex>

namespace retro::runtime {

using PerformanceClock = std::chrono::steady_clock;

inline std::int64_t monotonicNanos() noexcept {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        PerformanceClock::now().time_since_epoch()).count();
}

struct PerformanceSnapshot {
    std::uint64_t frame_count = 0;
    std::uint64_t frames_executed = 0;
    std::uint64_t frame_failures = 0;
    // -1 means not measured, not a zero-duration measurement.
    std::int64_t frame_mean_ns = -1;
    std::int64_t frame_p95_upper_bound_ns = -1;
    std::int64_t frame_max_ns = -1;
    std::int64_t startup_ns = -1;
    std::int64_t first_frame_ns = -1;
    std::int64_t session_duration_ns = -1;
    std::int64_t started_at_ns = -1;
};

// One fixed-size histogram per session (8 KiB). No allocation, sorting or
// logging on the frame path. The lock is never held while invoking a core.
// Timings cover run_frame itself, not the queue, scheduler or Java audio writes.
class SessionPerformance final {
public:
    static constexpr std::int64_t kBucketWidthNs = 250'000;
    static constexpr std::size_t kFiniteBuckets = 1024;

    void begin(const std::int64_t now_ns) noexcept {
        std::lock_guard lock(mutex_);
        snapshot_ = {};
        snapshot_.started_at_ns = now_ns;
        stopped_at_ns_ = -1;
        total_frame_ns_ = 0;
        histogram_.fill(0);
    }

    void loaded(const std::int64_t now_ns, const bool success) noexcept {
        std::lock_guard lock(mutex_);
        if (success) snapshot_.startup_ns = elapsed(now_ns, snapshot_.started_at_ns);
    }

    void frame(const std::int64_t duration_ns, const std::int64_t ended_at_ns,
               const bool success) noexcept {
        std::lock_guard lock(mutex_);
        const auto duration = std::max<std::int64_t>(0, duration_ns);
        ++snapshot_.frame_count;
        if (success) {
            ++snapshot_.frames_executed;
            if (snapshot_.first_frame_ns < 0) {
                snapshot_.first_frame_ns = elapsed(ended_at_ns, snapshot_.started_at_ns);
            }
        } else {
            ++snapshot_.frame_failures;
        }
        total_frame_ns_ += duration;
        snapshot_.frame_max_ns = std::max(snapshot_.frame_max_ns, duration);
        // Buckets are [0, 0.25ms], (0.25ms, 0.5ms], ... (256ms, infinity).
        const auto index = duration == 0 ? 0 : static_cast<std::size_t>((duration - 1) / kBucketWidthNs);
        ++histogram_[std::min(index, kFiniteBuckets)];
    }

    void stop(const std::int64_t now_ns) noexcept {
        std::lock_guard lock(mutex_);
        if (stopped_at_ns_ < 0) stopped_at_ns_ = now_ns;
    }

    PerformanceSnapshot snapshot(const std::int64_t now_ns) const noexcept {
        std::lock_guard lock(mutex_);
        auto result = snapshot_;
        result.session_duration_ns = elapsed(stopped_at_ns_ < 0 ? now_ns : stopped_at_ns_, result.started_at_ns);
        if (result.frame_count == 0) return result;
        result.frame_mean_ns = static_cast<std::int64_t>(total_frame_ns_ / result.frame_count);
        // ceil(0.95 * count), avoiding overflow in count * 95.
        const auto rank = result.frame_count - result.frame_count / 20;
        std::uint64_t cumulative = 0;
        for (std::size_t index = 0; index < histogram_.size(); ++index) {
            cumulative += histogram_[index];
            if (cumulative >= rank) {
                result.frame_p95_upper_bound_ns = index == kFiniteBuckets ? result.frame_max_ns :
                    std::min(result.frame_max_ns, static_cast<std::int64_t>(index + 1) * kBucketWidthNs);
                break;
            }
        }
        return result;
    }

private:
    static std::int64_t elapsed(const std::int64_t end, const std::int64_t start) noexcept {
        return start < 0 ? -1 : std::max<std::int64_t>(0, end - start);
    }

    mutable std::mutex mutex_;
    PerformanceSnapshot snapshot_;
    std::int64_t stopped_at_ns_ = -1;
    long double total_frame_ns_ = 0;
    std::array<std::uint64_t, kFiniteBuckets + 1> histogram_{};
};

}  // namespace retro::runtime
