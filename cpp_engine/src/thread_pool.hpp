// Fixed-size thread pool used by the renderer. Shading is a pure
// function of (scene, camera, pixel, settings) with deterministic
// per-pixel sampling, so parallel rows produce exactly the same image
// as a serial render regardless of scheduling.
#pragma once

#include <condition_variable>
#include <functional>
#include <mutex>
#include <queue>
#include <thread>
#include <vector>

namespace rt {

class ThreadPool {
public:
    explicit ThreadPool(size_t threads) : stop_(false) {
        if (threads == 0) threads = 1;
        for (size_t i = 0; i < threads; i++)
            workers_.emplace_back([this] { workerLoop(); });
    }
    ~ThreadPool() { shutdown(); }

    ThreadPool(const ThreadPool&) = delete;
    ThreadPool& operator=(const ThreadPool&) = delete;

    void enqueue(std::function<void()> task) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            tasks_.push(std::move(task));
        }
        cv_.notify_one();
    }

    size_t threadCount() const { return workers_.size(); }

    void shutdown() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (stop_) return;
            stop_ = true;
        }
        cv_.notify_all();
        for (auto& t : workers_)
            if (t.joinable()) t.join();
        workers_.clear();
    }

private:
    void workerLoop() {
        for (;;) {
            std::function<void()> task;
            {
                std::unique_lock<std::mutex> lock(mutex_);
                cv_.wait(lock, [this] { return stop_ || !tasks_.empty(); });
                if (stop_ && tasks_.empty()) return;
                task = std::move(tasks_.front());
                tasks_.pop();
            }
            task();
        }
    }

    std::vector<std::thread> workers_;
    std::queue<std::function<void()>> tasks_;
    std::mutex mutex_;
    std::condition_variable cv_;
    bool stop_;
};

}  // namespace rt
