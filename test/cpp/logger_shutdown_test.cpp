// Leave an accepted event queued when shutdown starts, then verify the file.
#define main logger_main
#include "tools/cpp/logger/main.cpp"
#undef main

int main(int argc, char** argv)
{
    std::string filename;
    {
        Logger candidate;
        if (!candidate.init(argc, argv)) return 1;
        filename = candidate.filename;
        auto* event = new zcm::LogEvent;
        event->eventnum = 0;
        event->timestamp = TimeUtil::utime();
        event->channel = "shutdown_test";
        event->datalen = 3;
        event->data = new uint8_t[3]{'e', 'n', 'd'};
        candidate.q.push(event);
        candidate.totalMemoryUsage = event->datalen + event->channel.size() + sizeof(*event);
        done = 1;
        candidate.flushWhenReady();
        if (!candidate.q.empty()) return 2;
        candidate.flushWhenReady(); // Empty queues must return without waiting.
    }
    zcm::LogFile recording(filename, "r");
    const auto* event = recording.readNextEvent();
    if (!event || event->channel != "shutdown_test" || event->datalen != 3
        || memcmp(event->data, "end", 3) != 0) return 3;
    return recording.readNextEvent() ? 4 : 0;
}
