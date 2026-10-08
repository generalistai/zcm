#ifndef CHANNEL_LENGTH_TEST_HPP
#define CHANNEL_LENGTH_TEST_HPP

#include "cxxtest/TestSuite.h"
#include "zcm/zcm-cpp.hpp"

#include <csignal>
#include <sys/resource.h>
#include <sys/wait.h>
#include <unistd.h>

class ChannelLengthTest : public CxxTest::TestSuite
{
    static void receive(const zcm::ReceiveBuffer* rbuf, const std::string& channel, void* usr)
    {
        *static_cast<std::string*>(usr) = channel;
        TS_ASSERT_EQUALS(rbuf->data_size, 1);
        TS_ASSERT_EQUALS(rbuf->data[0], 42);
    }

  public:
    void testMaximumLengthRoundTrip()
    {
        zcm::ZCM zcm("nonblock-inproc");
        TS_ASSERT(zcm.good());
        std::string received;
        std::string channel(ZCM_CHANNEL_MAXLEN, 'a');
        auto* sub = zcm.subscribe(channel, receive, &received);
        TS_ASSERT(sub);
        const uint8_t data = 42;
        TS_ASSERT_EQUALS(zcm.publish(channel, &data, 1), ZCM_EOK);
        TS_ASSERT_EQUALS(zcm.handleNonblock(), ZCM_EOK);
        TS_ASSERT_EQUALS(received, channel);
        TS_ASSERT_EQUALS(zcm.unsubscribe(sub), ZCM_EOK);
    }

    void testOversizedChannelsAbort()
    {
        for (bool publish : {false, true}) {
            pid_t pid = fork();
            TS_ASSERT_DIFFERS(pid, -1);
            if (pid == -1) return;
            if (pid == 0) {
                struct rlimit limit = {0, 0};
                setrlimit(RLIMIT_CORE, &limit);
                zcm::ZCM zcm("nonblock-inproc");
                std::string channel(ZCM_CHANNEL_MAXLEN + 1, 'a');
                const uint8_t data = 42;
                if (publish) zcm.publish(channel, &data, 1);
                else zcm.subscribe(channel, receive, nullptr);
                _exit(1);
            }
            int status = 0;
            TS_ASSERT_EQUALS(waitpid(pid, &status, 0), pid);
            TS_ASSERT(WIFSIGNALED(status));
            if (WIFSIGNALED(status)) TS_ASSERT_EQUALS(WTERMSIG(status), SIGABRT);
        }
    }
};

#endif
