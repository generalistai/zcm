import unittest

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
            "a" * CHANNEL_MAXLEN + ".*",
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


if __name__ == "__main__":
    unittest.main()
