import unittest
import os
import tempfile

from zerocm import CHANNEL_MAXLEN, ZCM, ZCM_EOK


class Message:
    def encode(self):
        return b"payload"

    @staticmethod
    def decode(data):
        return data


class ChannelLengthTest(unittest.TestCase):
    def test_boundary_round_trip(self):
        for channel in ("a" * CHANNEL_MAXLEN, "é" * (CHANNEL_MAXLEN // 2)):
            for typed in (False, True):
                with self.subTest(channel=channel, typed=typed):
                    zcm = ZCM("nonblock-inproc")
                    self.assertTrue(zcm.good())
                    received = []

                    def handler(name, data, recv_utime):
                        received.append((name, data))

                    if typed:
                        sub = zcm.subscribe(channel, Message, handler)
                        result = zcm.publish(channel, Message())
                    else:
                        sub = zcm.subscribe_raw(channel, handler)
                        result = zcm.publish_raw(channel, b"payload")
                    self.assertEqual(result, ZCM_EOK)
                    self.assertEqual(zcm.handleNonblock(), ZCM_EOK)
                    self.assertEqual(received, [(channel, b"payload")])
                    zcm.unsubscribe(sub)

    def test_oversized_channels_raise(self):
        zcm = ZCM("block-inproc")
        for channel in (
            "a" * (CHANNEL_MAXLEN + 1),
            "é" * (CHANNEL_MAXLEN // 2 + 1),
        ):
            operations = (
                lambda: zcm.publish_raw(channel, b"payload"),
                lambda: zcm.publish(channel, Message()),
                lambda: zcm.subscribe_raw(channel, lambda name, data, recv_utime: None),
                lambda: zcm.subscribe(channel, Message, lambda name, data, recv_utime: None),
            )
            for operation in operations:
                with self.assertRaisesRegex(ValueError, "too long.*bytes, max is"):
                    operation()

    def test_long_blocking_regex(self):
        pattern = "^(?!(camera_.*|gen_camera_obs_.*|camera_left|camera_right|projection_left|projection_right)$).*$"
        self.assertGreater(len(pattern), CHANNEL_MAXLEN)
        for typed in (False, True):
            zcm = ZCM("block-inproc")
            received = []

            def handler(name, data, recv_utime):
                received.append((name, data))

            if typed:
                sub = zcm.subscribe(pattern, Message, handler)
                self.assertEqual(zcm.publish("event", Message()), ZCM_EOK)
            else:
                sub = zcm.subscribe_raw(pattern, handler)
                self.assertEqual(zcm.publish_raw("event", b"payload"), ZCM_EOK)
            self.assertEqual(zcm.handle(), ZCM_EOK)
            self.assertEqual(received, [("event", b"payload")])
            zcm.unsubscribe(sub)

            # Keep dispatch running so a stale regex callback would be observable.
            sentinel = zcm.subscribe_raw(".*", lambda name, data, recv_utime: None)
            self.assertEqual(zcm.publish_raw("event", b"after unsubscribe"), ZCM_EOK)
            self.assertEqual(zcm.handle(), ZCM_EOK)
            self.assertEqual(received, [("event", b"payload")])
            zcm.unsubscribe(sentinel)
            with self.assertRaises(ValueError):
                zcm.publish_raw(pattern, b"payload")

    def test_named_ipc_path_failure_does_not_retry(self):
        with tempfile.TemporaryDirectory(prefix="zcm-ipc-") as directory:
            zcm = ZCM("ipc://" + os.path.basename(directory))
            self.assertTrue(zcm.good())
            for typed in (False, True):
                handler = lambda name, data: None
                with self.assertRaisesRegex(RuntimeError, "subscription.*Invalid arguments"):
                    if typed:
                        zcm.subscribe("a" * CHANNEL_MAXLEN, Message, handler)
                    else:
                        zcm.subscribe_raw("a" * CHANNEL_MAXLEN, handler)
                sub = zcm.subscribe_raw("event", handler)
                zcm.unsubscribe(sub)
            del zcm

    def test_invalid_regex_does_not_retry(self):
        for url, pattern in (("block-inproc", "("), ("nonblock-inproc", "a|b")):
            zcm = ZCM(url)
            with self.assertRaisesRegex(RuntimeError, "subscription.*Invalid arguments"):
                zcm.subscribe_raw(pattern, lambda name, data: None)


    def test_nonblocking_regex_still_has_a_length_limit(self):
        zcm = ZCM("nonblock-inproc")
        with self.assertRaises(ValueError):
            zcm.subscribe_raw("a" * CHANNEL_MAXLEN + ".*", lambda name, data, recv_utime: None)


if __name__ == "__main__":
    unittest.main()
