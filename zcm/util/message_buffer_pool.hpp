#pragma once

#include "zcm/zcm.h"

#include <memory>
#include <mutex>
#include <string>
#include <vector>

// Queue messages keep exclusive ownership until send/dispatch completes. Only
// idle storage is pooled: bound retention even when callers enlarge the queues
// or occasionally publish very large messages. No public ABI changes.
class MessageBufferPool
{
  public:
    struct Buffer {
        Buffer() { channel.reserve(ZCM_CHANNEL_MAXLEN); }
        std::string channel;
        std::vector<uint8_t> payload;
    };

    static constexpr size_t MAX_BUFFERS = 16;
    static constexpr size_t MAX_BYTES = 16u << 20;

    MessageBufferPool() { idle.reserve(MAX_BUFFERS); }

    std::unique_ptr<Buffer> acquire(size_t size)
    {
        std::unique_ptr<Buffer> buffer;
        {
            std::lock_guard<std::mutex> lock(mut);
            if (!idle.empty()) {
                buffer = std::move(idle.back());
                idle.pop_back();
                cachedBytes -= buffer->payload.capacity();
            }
        }
        if (!buffer) buffer.reset(new Buffer());
        // Keep a non-null data pointer even for empty messages.
        if (size == 0) size = 1;
        if (buffer->payload.size() < size) buffer->payload.resize(size);
        return buffer;
    }

    void release(std::unique_ptr<Buffer> buffer)
    {
        std::lock_guard<std::mutex> lock(mut);
        const size_t capacity = buffer->payload.capacity();
        if (idle.size() < MAX_BUFFERS && capacity <= MAX_BYTES - cachedBytes) {
            cachedBytes += capacity;
            idle.push_back(std::move(buffer));
        }
    }

  private:
    std::mutex mut;
    std::vector<std::unique_ptr<Buffer>> idle;
    size_t cachedBytes = 0;
};
