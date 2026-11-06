// Exercise the control protocol synchronously, without timing-dependent sleeps.
#define main logger_main
#include "tools/cpp/logger/main.cpp"
#undef main

#include <stdexcept>

static void check(bool ok)
{
    if (!ok) throw std::runtime_error("logger cycle regression failed");
}

static vector<uint8_t> request(uint64_t sequence)
{
    vector<uint8_t> frame(begin(CONTROL_MAGIC), end(CONTROL_MAGIC));
    append_u16_be(frame, CONTROL_VERSION);
    append_u16_be(frame, MSG_CYCLE_REQUEST);
    append_u32_be(frame, CYCLE_REQUEST_FRAME_SIZE);
    for (uint64_t field : {uint64_t(123), sequence, uint64_t(0), uint64_t(0)})
        append_u64_be(frame, field);
    return frame;
}

static void receive(Logger& candidate, size_t shard, const string& channel,
                    vector<uint8_t> payload)
{
    zcm::ReceiveBuffer buffer{};
    buffer.recv_utime = TimeUtil::utime();
    buffer.data_size = payload.size();
    buffer.data = payload.data();
    candidate.handler(&buffer, channel, shard);
    while (!candidate.q.empty()) candidate.flushWhenReady();
}

static vector<uint8_t> reply(int fd, uint16_t type)
{
    vector<uint8_t> frame(65536);
    ssize_t count = recv(fd, frame.data(), frame.size(), MSG_DONTWAIT);
    check(count >= (ssize_t)CONTROL_HEADER_SIZE);
    frame.resize(count);
    check(read_u16_be(frame.data() + 6) == type);
    return frame;
}

static void run(const string& prefix, bool all_shards)
{
    int sockets[2];
    check(socketpair(AF_UNIX, SOCK_SEQPACKET, 0, sockets) == 0);
    {
        Logger candidate;
        vector<string> arguments = {"logger", "-i", "--control-fd", to_string(sockets[1]),
            "--control-session-id", "123", "-u", "ipcshm://input0",
            "-u", "ipcshm://input1", "-u", "ipcshm://input2"};
        if (all_shards) arguments.push_back("--control-all-shards");
        arguments.push_back(prefix);
        vector<char*> argv;
        for (auto& argument : arguments) argv.push_back(&argument[0]);
        argv.push_back(nullptr);
        optind = 0;
        check(candidate.init(arguments.size(), argv.data()));
        auto stale = request(99);
        stale[CONTROL_HEADER_SIZE + 7] = 124;
        receive(candidate, 0, CONTROL_REQUEST_CHANNEL, stale);
        receive(candidate, 1, CONTROL_REQUEST_CHANNEL, {});

        for (uint64_t cycle = 1; cycle <= 2; ++cycle) {
            string old_file = candidate.filename;
            vector<string> expected;
            for (size_t shard = 0; shard < 3; ++shard) {
                string channel = "input" + to_string(shard);
                receive(candidate, shard, channel, {'d', 'a', 't', 'a'});
                expected.push_back(channel);
                receive(candidate, shard, CONTROL_REQUEST_CHANNEL, request(cycle));
                if (!all_shards || shard == 2) break;
                // A retry cannot substitute for a marker on another input.
                receive(candidate, shard, CONTROL_REQUEST_CHANNEL, request(cycle));
                uint8_t ignored;
                check(recv(sockets[0], &ignored, 1, MSG_DONTWAIT) == -1 && errno == EAGAIN);
                check(candidate.filename == old_file);
                // This is a prefix guarantee, not a simultaneous cut across inputs.
                receive(candidate, shard, "after_early_marker", {'d', 'a', 't', 'a'});
                expected.push_back("after_early_marker");
                // A different cycle must not mix its markers with this one.
                receive(candidate, shard, CONTROL_REQUEST_CHANNEL, request(cycle + 10));
                auto busy = reply(sockets[0], MSG_CYCLE_ERROR);
                check(read_u32_be(busy.data() + CONTROL_HEADER_SIZE + 16) == ERR_BUSY_WITH_OTHER_CYCLE);
            }
            auto complete = reply(sockets[0], MSG_CYCLE_COMPLETE);
            check(read_u64_be(complete.data() + CONTROL_HEADER_SIZE + 8) == cycle);
            check(candidate.filename != old_file);
            // Completion retries keep the same files and do not rotate again.
            string new_file = candidate.filename;
            receive(candidate, 0, CONTROL_REQUEST_CHANNEL, request(cycle));
            check(reply(sockets[0], MSG_CYCLE_COMPLETE) == complete);
            check(candidate.filename == new_file);

            zcm::LogFile recording(old_file, "r");
            expected.push_back(BARRIER_MARKER_CHANNEL);
            for (const auto& channel : expected) {
                const auto* event = recording.readNextEvent();
                check(event && event->channel == channel);
                if (channel != BARRIER_MARKER_CHANNEL)
                    check(event->datalen == 4 && memcmp(event->data, "data", 4) == 0);
            }
            check(recording.readNextEvent() == nullptr);
        }
    }
    close(sockets[0]);
    close(sockets[1]);
}

int main(int argc, char** argv)
{
    if (argc != 2) return 1;
    run(string(argv[1]) + "-all", true);
    run(string(argv[1]) + "-legacy", false);
    return 0;
}
