"""Exercise the actual UDP transport with explicitly ordered wire fragments.

Run with ZCM_TEST_LIBRARY=/absolute/path/to/libzcm.so python3 -m unittest
discover -s test/python -p udp_reassembly_test.py -v. No Python ZCM binding
or generated message types are needed.
"""

import ctypes as C
import ctypes.util
import itertools
import os
import socket
import struct
import time
import unittest


class Message(C.Structure):
    _fields_ = [("utime", C.c_uint64), ("channel", C.c_char_p),
                ("length", C.c_size_t), ("data", C.c_void_p)]


class Methods(C.Structure):
    _fields_ = [(name, C.c_void_p) for name in
                ("get_mtu", "sendmsg", "enable", "recvmsg", "drops", "update", "destroy")]


class Transport(C.Structure):
    _fields_ = [("kind", C.c_int), ("methods", C.POINTER(Methods))]


class Receiver:
    def __init__(self, peer="127.0.0.1", port=0, library=None):
        self.lib = C.CDLL(library or os.environ.get("ZCM_TEST_LIBRARY")
                          or ctypes.util.find_library("zcm"))
        self.lib.zcm_url_create.argtypes = [C.c_char_p]
        self.lib.zcm_url_create.restype = C.c_void_p
        self.lib.zcm_url_destroy.argtypes = [C.c_void_p]
        self.lib.zcm_transport_find.argtypes = [C.c_char_p]
        self.lib.zcm_transport_find.restype = C.c_void_p
        if not port:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as reservation:
                reservation.bind(("127.0.0.1", 0))
                port = reservation.getsockname()[1]
        self.port = port
        url = self.lib.zcm_url_create(f"udp://{peer}:{port}:59999".encode())
        creator = self.lib.zcm_transport_find(b"udp")
        if not creator:
            raise RuntimeError("Candidate library has no UDP transport")
        create = C.CFUNCTYPE(C.POINTER(Transport), C.c_void_p, C.c_void_p)(creator)
        try:
            self.transport = create(url, None)
        finally:
            self.lib.zcm_url_destroy(url)
        if not self.transport:
            raise RuntimeError("Cannot create UDP test transport")
        methods = self.transport.contents.methods.contents
        self._receive = C.CFUNCTYPE(C.c_int, C.POINTER(Transport),
                                   C.POINTER(Message), C.c_uint)(methods.recvmsg)
        self._destroy = C.CFUNCTYPE(None, C.POINTER(Transport))(methods.destroy)

    def receive(self, timeout_ms=5):
        message = Message()
        code = self._receive(self.transport, C.byref(message), timeout_ms)
        if code != 0:
            return None
        return (message.channel.decode(), C.string_at(message.data, message.length))

    def close(self):
        if self.transport:
            self._destroy(self.transport)
            self.transport = None


def fragments(sequence, data, channel="LEFT", fragment_size=65487):
    """Encode the documented LC03 wire format, including channel only in #0."""
    channel_bytes = channel.encode() + b"\0"
    payload = channel_bytes + data
    count = (len(payload) + fragment_size - 1) // fragment_size
    return [struct.pack(">IIIIHH", 0x4C433033, sequence, len(data),
                        max(0, start - len(channel_bytes)), index, count)
            + payload[start:start + fragment_size]
            for index, start in enumerate(range(0, len(payload), fragment_size))]


def rewrite(packet, **changes):
    fields = list(struct.unpack(">IIIIHH", packet[:20]))
    names = ("magic", "sequence", "size", "offset", "index", "count")
    for name, value in changes.items():
        fields[names.index(name)] = value
    return struct.pack(">IIIIHH", *fields) + packet[20:]


class UdpReassemblyTest(unittest.TestCase):
    def setUp(self):
        self.receiver = Receiver()
        self.addCleanup(self.receiver.close)
        self.sender = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.addCleanup(self.sender.close)
        self.received = []
        # Each of three fragments has independently distinguishable contents.
        self.data = bytes(range(251)) * 524  # 131524 bytes, three Linux fragments.

    def send(self, packet, sender=None):
        (sender or self.sender).sendto(packet, ("127.0.0.1", self.receiver.port))
        message = self.receiver.receive()
        if message is not None:
            self.received.append(message)

    def assertMessages(self, expected):
        self.assertEqual(len(self.received), len(expected), "Complete-message count differs")
        for actual, wanted in zip(self.received, expected):
            self.assertEqual(actual[0], wanted[0])
            self.assertTrue(actual[1] == wanted[1], "Reassembled payload differs")

    def test_ordered(self):
        for packet in fragments(1, self.data):
            self.send(packet)
        self.assertMessages([("LEFT", self.data)])

    def test_all_fragment_orders(self):
        for sequence, order in enumerate(itertools.permutations(range(3)), start=1):
            with self.subTest(order=order):
                packets = fragments(sequence, self.data)
                self.received.clear()
                for index in order:
                    self.send(packets[index])
                self.assertMessages([("LEFT", self.data)])

    def test_interleaved_cameras(self):
        left = fragments(10, self.data, "LEFT")
        right_data = self.data[::-1]
        right = fragments(11, right_data, "RIGHT")
        for packet in (left[2], right[2], left[0], right[0], left[1], right[1]):
            self.send(packet)
        self.assertMessages([("LEFT", self.data), ("RIGHT", right_data)])

    def test_duplicate_does_not_complete_missing_fragment(self):
        packets = fragments(1, self.data)
        for index in (0, 1, 1):
            self.send(packets[index])
        self.assertMessages([])
        self.send(packets[2])
        self.assertMessages([("LEFT", self.data)])

    def test_newer_frame_completes_before_older_frame(self):
        # Reassembly is deliberately NOT a freshness/ordering policy.
        old = fragments(1, self.data)
        new_data = self.data[::-1]
        new = fragments(2, new_data)
        for packet in (old[2], old[0], *new, old[1]):
            self.send(packet)
        self.assertMessages([("LEFT", new_data), ("LEFT", self.data)])

    def test_same_sequence_from_different_senders(self):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as other:
            left = fragments(1, self.data)
            right = fragments(1, self.data[::-1], "RIGHT")
            for index in (2, 0, 1):
                self.send(left[index])
                self.send(right[index], other)
        self.assertMessages([("LEFT", self.data), ("RIGHT", self.data[::-1])])

    def test_incomplete_message_expires(self):
        packets = fragments(1, self.data)
        self.send(packets[2])
        time.sleep(5.1)
        self.send(packets[0])
        self.send(packets[1])
        self.assertMessages([])
        self.send(packets[2])
        self.assertMessages([("LEFT", self.data)])

    def test_progress_keeps_slow_reassembly_alive(self):
        packets = fragments(1, self.data)
        self.send(packets[2])
        time.sleep(2.6)
        self.send(packets[0])
        time.sleep(2.6)
        self.send(packets[1])
        self.assertMessages([("LEFT", self.data)])

    def test_conflicting_duplicate_is_rejected(self):
        packets = fragments(1, self.data)
        self.send(packets[0])
        self.send(packets[1])
        self.send(packets[1][:-1] + bytes([packets[1][-1] ^ 1]))
        self.send(packets[2])
        self.assertMessages([])

    def test_smaller_wire_fragments_remain_compatible(self):
        packets = fragments(1, self.data[:6000], fragment_size=1423)
        for index in (4, 2, 0, 3, 1):
            self.send(packets[index])
        self.assertMessages([("LEFT", self.data[:6000])])

    def test_sequence_wrap_does_not_merge_messages(self):
        older = fragments(0xFFFFFFFF, self.data)
        newer = fragments(0, self.data[::-1])
        for packet in (older[2], newer[2], older[0], newer[0], older[1], newer[1]):
            self.send(packet)
        self.assertMessages([("LEFT", self.data), ("LEFT", self.data[::-1])])

    def test_single_fragment_long_message(self):
        self.send(fragments(1, b"small")[0])
        self.assertMessages([("LEFT", b"small")])

    def test_channel_space_near_transport_limit(self):
        # The previous receiver accepted a payload plus channel totaling 2^28.
        # Reserving the maximum channel length must not shrink that limit.
        data = b"x" * ((1 << 28) - len(b"LEFT\0"))
        for packet in fragments(1, data):
            self.sender.sendto(packet, ("127.0.0.1", self.receiver.port))
            message = self.receiver.receive(timeout_ms=0)
            if message is not None:
                self.received.append(message)
        self.assertMessages([("LEFT", data)])

    def test_inconsistent_size_near_transport_limit_is_rejected(self):
        # Channel reservations shrink near MTU; two declared sizes can otherwise
        # produce the same allocation size while disagreeing on payload layout.
        data = b"x" * ((1 << 28) - 32)
        packets = fragments(1, data)
        packets[0] = rewrite(packets[0], size=len(data) + 1)
        for packet in packets:
            self.sender.sendto(packet, ("127.0.0.1", self.receiver.port))
            message = self.receiver.receive(timeout_ms=0)
            if message is not None:
                self.received.append(message)
        self.assertMessages([])

    def test_short_message_unaffected(self):
        self.send(struct.pack(">II", 0x4C433032, 1) + b"LEFT\0small")
        self.assertMessages([("LEFT", b"small")])

    def test_malformed_fragments_never_deliver(self):
        packets = fragments(1, self.data)
        invalid = [packets[0][:8], packets[0][:19],
                   rewrite(packets[0], count=0), rewrite(packets[0], index=3),
                   rewrite(packets[0], offset=1),
                   rewrite(packets[1], offset=len(self.data)),
                   rewrite(packets[0], size=0xFFFFFFFF),
                   packets[0][:20] + b"unterminated"]
        for packet in invalid:
            self.send(packet)
        self.assertMessages([])
        for packet in fragments(2, self.data):
            self.send(packet)
        self.assertMessages([("LEFT", self.data)])

    def test_inconsistent_metadata_never_delivers(self):
        packets = fragments(1, self.data)
        for packet in (packets[0], rewrite(packets[1], count=4), packets[2]):
            self.send(packet)
        self.assertMessages([])

    def test_overlap_and_gap_never_deliver(self):
        for sequence, delta in ((1, -1), (2, 1)):
            packets = fragments(sequence, self.data)
            offset = struct.unpack(">IIIIHH", packets[1][:20])[3]
            for packet in (packets[0], rewrite(packets[1], offset=offset + delta), packets[2]):
                self.send(packet)
        self.assertMessages([])

    def test_duplicate_fragment_zero(self):
        packets = fragments(1, self.data)
        for index in (2, 0, 0, 1):
            self.send(packets[index])
        self.assertMessages([("LEFT", self.data)])

    def test_incomplete_messages_do_not_prevent_later_delivery(self):
        for sequence in range(1, 160):
            self.send(fragments(sequence, self.data)[2])
        self.assertMessages([])
        for packet in fragments(200, self.data):
            self.send(packet)
        self.assertMessages([("LEFT", self.data)])


if __name__ == "__main__":
    unittest.main()
