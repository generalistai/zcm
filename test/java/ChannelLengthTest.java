import zcm.zcm.ZCM;
import zcm.zcm.ZCMSubscriber;

import java.util.concurrent.CountDownLatch;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class ChannelLengthTest
{
    private static final byte[] DATA = {42};
    private static final ZCMSubscriber IGNORE = (zcm, channel, data) -> {};

    private static String repeat(String value, int count)
    {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; ++i) result.append(value);
        return result.toString();
    }

    private static void checkAccepted(String channel) throws Exception
    {
        checkAccepted(channel, channel);
    }

    private static void checkAccepted(String pattern, String channel) throws Exception
    {
        ZCM zcm = new ZCM("block-inproc");
        CountDownLatch received = new CountDownLatch(1);
        try {
            ZCM.Subscription sub = zcm.subscribe(pattern, (z, actual, data) -> {
                if (channel.equals(actual)) received.countDown();
            });
            zcm.start();
            zcm.publish(channel, DATA, 0, DATA.length);
            if (!received.await(5, TimeUnit.SECONDS))
                throw new AssertionError("Maximum-length channel did not round-trip");
            zcm.stop();
            if (zcm.unsubscribe(sub) != 0)
                throw new AssertionError("Unsubscribe failed");
        } finally {
            zcm.close();
        }
    }

    private static void checkRejected(String channel) throws Exception
    {
        checkRejected(channel, "block-inproc");
    }

    private static void checkRejected(String channel, String url) throws Exception
    {
        ZCM zcm = new ZCM(url);
        try {
            try {
                zcm.publish(channel, DATA, 0, DATA.length);
                throw new AssertionError("Oversized publication was accepted");
            } catch (IllegalArgumentException expected) {}
            try {
                zcm.subscribe(channel, IGNORE);
                throw new AssertionError("Oversized subscription was accepted");
            } catch (IllegalArgumentException expected) {}
        } finally {
            zcm.close();
        }
    }

    private static void checkIpcFailure() throws Exception
    {
        Path directory = Files.createTempDirectory("zcm-ipc-");
        ZCM zcm = new ZCM("ipc://" + directory.getFileName());
        try {
            try {
                zcm.subscribe(repeat("a", ZCM.CHANNEL_MAXLEN), IGNORE);
                throw new AssertionError("Oversized IPC endpoint was accepted");
            } catch (IllegalStateException expected) {}
            ZCM.Subscription sub = zcm.subscribe("event", IGNORE);
            if (zcm.unsubscribe(sub) != 0) throw new AssertionError("Unsubscribe failed");
        } finally {
            zcm.close();
            Files.delete(directory);
        }
    }

    public static void main(String[] args) throws Exception
    {
        checkIpcFailure();
        checkAccepted(repeat("a", ZCM.CHANNEL_MAXLEN));
        checkRejected(repeat("a", ZCM.CHANNEL_MAXLEN + 1));
        checkAccepted("(" + repeat("a", ZCM.CHANNEL_MAXLEN) + "|event)", "event");
        checkRejected(repeat("a", ZCM.CHANNEL_MAXLEN) + ".*", "nonblock-inproc");
        checkAccepted(repeat("\u00e9", ZCM.CHANNEL_MAXLEN / 2));
        checkRejected(repeat("\u00e9", ZCM.CHANNEL_MAXLEN / 2 + 1));
        // JNI encodes each surrogate separately, and NUL takes two bytes.
        checkAccepted(repeat("\ud83d\ude80", ZCM.CHANNEL_MAXLEN / 6));
        checkRejected(repeat("\ud83d\ude80", ZCM.CHANNEL_MAXLEN / 6 + 1));
        checkAccepted(repeat("\u0000", ZCM.CHANNEL_MAXLEN / 2));
        checkRejected(repeat("\u0000", ZCM.CHANNEL_MAXLEN / 2 + 1));
        System.out.println("Java channel length tests passed");
    }
}
