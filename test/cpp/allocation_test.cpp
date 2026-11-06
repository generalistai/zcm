#include "zcm/zcm.h"
#include "zcm/transport.h"
#include "zcm/util/message_buffer_pool.hpp"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <new>
#include <thread>
#include <vector>

static std::atomic<bool> counting{false};
static std::atomic<size_t> allocations{0};
void* operator new(size_t size) {
    if (counting) ++allocations;
    if (void* p = std::malloc(size ? size : 1)) return p;
    throw std::bad_alloc();
}
void* operator new[](size_t size) { return ::operator new(size); }
void operator delete(void* p) noexcept { std::free(p); }
void operator delete[](void* p) noexcept { std::free(p); }
void operator delete(void* p, size_t) noexcept { std::free(p); }
void operator delete[](void* p, size_t) noexcept { std::free(p); }

static void check(bool ok, const char* message) {
    if (!ok) { std::fprintf(stderr, "%s\n", message); std::exit(1); }
}

struct Transport : zcm_trans_t {
    std::atomic<int> remaining{0}, completed{0}, received{0}, sent{0};
    std::atomic<bool> hold{false}, bad{false};
    const char* incoming = "unrelated_robot_telemetry";
    uint8_t byte = 7;
};
static Transport& state(zcm_trans_t* trans) { return *static_cast<Transport*>(trans); }
static size_t mtu(zcm_trans_t*) { return 1024 * 1024; }
static int send(zcm_trans_t* trans, zcm_msg_t msg) {
    auto& t = state(trans);
    while (t.hold) std::this_thread::yield();
    if (std::strcmp(msg.channel, "camera_jpg_allocation_test") != 0) t.bad = true;
    for (size_t i = 0; i < msg.len; ++i) if (msg.buf[i] != 7) t.bad = true;
    ++t.sent;
    return ZCM_EOK;
}
static int enable(zcm_trans_t*, const char*, bool) { return ZCM_EOK; }
static int receive(zcm_trans_t* trans, zcm_msg_t* msg, unsigned) {
    auto& t = state(trans);
    int n = t.remaining.load();
    if (n == 0) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
        return ZCM_EAGAIN;
    }
    msg->utime = 1;
    msg->channel = n == 1 ? "done" : t.incoming;
    msg->buf = &t.byte;
    msg->len = 1;
    --t.remaining;
    return ZCM_EOK;
}
static void destroy(zcm_trans_t*) {}
static void callback(const zcm_recv_buf_t* msg, const char* channel, void* user) {
    auto& t = *static_cast<Transport*>(user);
    if (msg->data_size != 1 || static_cast<const uint8_t*>(msg->data)[0] != 7) t.bad = true;
    if (std::strcmp(channel, "done") == 0) ++t.completed;
    else ++t.received;
}
static void batch(Transport& t, const char* channel) {
    int target = t.completed + 1;
    t.incoming = channel;
    t.remaining = 5001;
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    while (t.completed < target && std::chrono::steady_clock::now() < deadline)
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    check(t.completed == target, "Receiver did not finish batch");
    // The final callback precedes the queue pop. Allow its storage to return.
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
}

int main() {
    Transport t;
    zcm_trans_methods_t methods{mtu, send, enable, receive, nullptr, nullptr, destroy};
    t.trans_type = ZCM_BLOCKING;
    t.vtbl = &methods;
    zcm_t* bus = zcm_create_from_trans(&t);
    check(bus != nullptr, "Could not create test bus");
    zcm_subscribe(bus, "done", callback, &t);
    zcm_subscribe(bus, "camera_trigger_left", callback, &t);
    zcm_start(bus);
    batch(t, "short");
    allocations = 0;
    counting = true;
    batch(t, "unrelated_robot_telemetry");
    counting = false;
    check(allocations == 0, "Unsubscribed long channel allocated after warmup");
    check(t.received == 0, "Unsubscribed traffic reached callbacks");

    batch(t, "camera_trigger_left");
    check(t.received == 5000, "Exact subscription routing changed");
    auto* sub = zcm_subscribe(bus, "camera_control_.*", callback, &t);
    batch(t, "camera_control_right");
    check(t.received == 10000, "Regex subscription routing changed");
    zcm_unsubscribe(bus, sub);
    batch(t, "camera_control_right");
    check(t.received == 10000, "Unsubscribe routing changed");
    zcm_stop(bus);

    std::vector<uint8_t> payload(256 * 1024, 7);
    // Fill a stopped send queue, proving independent ownership/backpressure.
    t.hold = true;
    for (int i = 0; i < 15; ++i)
        check(zcm_publish(bus, "camera_jpg_allocation_test", payload.data(), payload.size()) == ZCM_EOK,
              "Queue rejected a frame before it was full");
    check(zcm_publish(bus, "camera_jpg_allocation_test", payload.data(), payload.size()) == ZCM_EAGAIN,
          "Full queue stopped applying backpressure");
    std::memset(payload.data(), 99, payload.size());
    t.hold = false;
    zcm_flush(bus);
    check(t.sent == 15 && !t.bad, "Queued payload did not retain its own copy");

    // Alternate short/large frames through warmed queue storage.
    allocations = 0;
    counting = true;
    for (int i = 0; i < 200; ++i) {
        std::memset(payload.data(), 7, payload.size());
        size_t size = i % 2 ? 0 : payload.size();
        check(zcm_publish(bus, "camera_jpg_allocation_test", payload.data(), size) == ZCM_EOK,
              "Publish failed");
        std::memset(payload.data(), 99, payload.size());
        zcm_flush(bus);
    }
    counting = false;
    check(allocations == 0 && !t.bad, "Warmed send path allocated or corrupted data");
    zcm_set_queue_size(bus, 32);
    zcm_set_queue_size(bus, 16);
    zcm_destroy(bus);

    // Oversized idle buffers are freed instead of escaping the retention bound.
    MessageBufferPool pool;
    auto large = pool.acquire(MessageBufferPool::MAX_BYTES + 1);
    pool.release(std::move(large));
    allocations = 0;
    counting = true;
    auto fresh = pool.acquire(1);
    counting = false;
    check(allocations > 0, "Oversized buffer was retained");
    pool.release(std::move(fresh));
    std::puts("Allocation, routing, payload ownership, and backpressure checks passed");
}
