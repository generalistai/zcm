#ifndef SUBSCRIPTION_STATUS_TEST_HPP
#define SUBSCRIPTION_STATUS_TEST_HPP

#include "cxxtest/TestSuite.h"
#include "zcm/zcm-cpp.hpp"
#include "zcm/transport.h"
#include <condition_variable>
#include <mutex>
#include <thread>
#include <sys/un.h>
#include <unistd.h>

class SubscriptionStatusTest : public CxxTest::TestSuite
{
    struct ControlledTransport : zcm_trans_t {
        std::mutex mutex;
        std::condition_variable changed;
        bool entered = false;
        bool release = false;
    };

    static int enable(zcm_trans_t* trans, const char* channel, bool enabled)
    {
        if (!enabled) return ZCM_EOK;
        if (std::string(channel) == "FAIL") return ZCM_ECONNECT;
        if (std::string(channel) != "WAIT") return ZCM_EOK;
        auto* controlled = static_cast<ControlledTransport*>(trans);
        std::unique_lock<std::mutex> lock(controlled->mutex);
        controlled->entered = true;
        controlled->changed.notify_all();
        controlled->changed.wait(lock, [&]{ return controlled->release; });
        return ZCM_EOK;
    }

  public:
    void testStatusDistinguishesContentionAndPermanentFailure()
    {
        zcm_trans_methods_t methods = {};
        methods.get_mtu = [](zcm_trans_t*) -> size_t { return 1024; };
        methods.recvmsg_enable = enable;
        methods.destroy = [](zcm_trans_t*) {};
        ControlledTransport trans;
        trans.trans_type = ZCM_BLOCKING;
        trans.vtbl = &methods;
        auto* z = zcm_create_from_trans(&trans);
        TS_ASSERT(z);
        if (!z) return;
        zcm_sub_t* first = nullptr;
        int firstStatus = ZCM_EUNKNOWN;
        std::thread subscribing([&] {
            firstStatus = zcm_subscribe_ex(z, "WAIT", nullptr, nullptr, &first);
        });
        {
            std::unique_lock<std::mutex> lock(trans.mutex);
            trans.changed.wait(lock, [&]{ return trans.entered; });
        }
        zcm_sub_t* sub = nullptr;
        TS_ASSERT_EQUALS(zcm_try_subscribe_ex(z, "NEXT", nullptr, nullptr, &sub), ZCM_EAGAIN);
        TS_ASSERT_EQUALS(sub, nullptr);
        {
            std::lock_guard<std::mutex> lock(trans.mutex);
            trans.release = true;
            trans.changed.notify_all();
        }
        subscribing.join();
        TS_ASSERT_EQUALS(firstStatus, ZCM_EOK);
        TS_ASSERT_EQUALS(zcm_try_subscribe_ex(z, "NEXT", nullptr, nullptr, &sub), ZCM_EOK);
        TS_ASSERT(sub);
        TS_ASSERT_EQUALS(zcm_unsubscribe(z, sub), ZCM_EOK);
        TS_ASSERT_EQUALS(zcm_unsubscribe(z, first), ZCM_EOK);
        TS_ASSERT_EQUALS(zcm_try_subscribe_ex(z, "FAIL", nullptr, nullptr, &sub), ZCM_ECONNECT);
        TS_ASSERT_EQUALS(sub, nullptr);
        TS_ASSERT_EQUALS(zcm_subscribe_ex(z, "(", nullptr, nullptr, &sub), ZCM_EINVALID);
        TS_ASSERT_EQUALS(sub, nullptr);
        TS_ASSERT_EQUALS(std::string(zcm_strerrno(ZCM_EINVALID)), "Invalid arguments");
        TS_ASSERT_EQUALS(std::string(zcm_strerrno(ZCM_ECONNECT)), "Transport connection failed");
        zcm_destroy(z);
    }

    void testNamedIpcPathBoundaryAndCleanup()
    {
        char directory[] = "/tmp/zcm-ipc-XXXXXX";
        TS_ASSERT(mkdtemp(directory));
        const std::string subnet = std::string(directory).substr(5);
        const std::string url = "ipc://" + subnet;
        const size_t available = sizeof(sockaddr_un::sun_path) - 1 -
                                 (std::string(directory) + "/zcm-channel-zmq-ipc-").size();
        TS_ASSERT_LESS_THAN(available, ZCM_CHANNEL_MAXLEN);
        {
            zcm::ZCM z(url);
            const std::string tooLong(available + 1, 'a');
            TS_ASSERT_EQUALS(z.subscribe(tooLong, nullptr, nullptr), nullptr);
            TS_ASSERT_EQUALS(z.err(), ZCM_EINVALID);
            auto* sub = z.subscribe(std::string(available, 'a'), nullptr, nullptr);
            TS_ASSERT(sub);
            TS_ASSERT_EQUALS(z.err(), ZCM_EOK);
            if (sub) TS_ASSERT_EQUALS(z.unsubscribe(sub), ZCM_EOK);
            // Pattern text does not form a Unix socket path.
            sub = z.subscribe("(" + std::string(100, 'a') + "|event)", nullptr, nullptr);
            TS_ASSERT(sub);
            if (sub) TS_ASSERT_EQUALS(z.unsubscribe(sub), ZCM_EOK);
        }
        {
            // Force a failure after zmq_socket has allocated a socket.
            zcm::ZCM z(url + "?subhwm=-1");
            TS_ASSERT_EQUALS(z.subscribe("event", nullptr, nullptr), nullptr);
            TS_ASSERT_EQUALS(z.err(), ZCM_ECONNECT);
        }
        TS_ASSERT_EQUALS(rmdir(directory), 0);
    }

    void testNonblockingPermanentErrors()
    {
        zcm::ZCM z("nonblock-inproc");
        zcm_sub_t* sub = nullptr;
        TS_ASSERT_EQUALS(zcm_try_subscribe_ex(z.getUnderlyingZCM(), "a|b", nullptr, nullptr, &sub), ZCM_EINVALID);
        TS_ASSERT_EQUALS(sub, nullptr);
    }
};
#endif
