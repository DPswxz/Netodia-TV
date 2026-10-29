"""Protocol and backpressure regression checks; does not connect to a TV."""
import importlib.util
from pathlib import Path
import socket
import struct
import unittest

source = Path(__file__).resolve().parents[1] / 'tools/tv-remote/server.py'
spec = importlib.util.spec_from_file_location('tv_remote', source)
remote = importlib.util.module_from_spec(spec)
spec.loader.exec_module(remote)


class RemoteTest(unittest.TestCase):
    def test_slow_viewer_discards_gop_until_new_key(self):
        mailbox = remote.VideoMailbox()
        self.assertFalse(mailbox.offer(b'\0old-delta'))
        self.assertTrue(mailbox.frames.empty())
        mailbox.offer(b'\1key')
        mailbox.offer(b'\0delta-a')
        mailbox.offer(b'\0delta-b')
        self.assertTrue(mailbox.offer(b'\0too-late'))
        self.assertTrue(mailbox.frames.empty())
        mailbox.offer(b'\0still-undecodable')
        self.assertTrue(mailbox.frames.empty())
        mailbox.offer(b'\1fresh-key')
        self.assertEqual(mailbox.frames.get_nowait(), b'\1fresh-key')

    def test_websocket_mask_and_extended_payload(self):
        writer, reader = socket.socketpair()
        try:
            body = b'input' * 40
            mask = b'ABCD'
            encoded = bytes(value ^ mask[i % 4] for i, value in enumerate(body))
            writer.sendall(bytes([0x81, 0xfe]) + struct.pack('>H', len(body)) + mask + encoded)
            self.assertEqual(remote.ws_read(reader), (1, body))
        finally:
            writer.close()
            reader.close()

    def test_key_press_has_matching_release(self):
        packet = remote.control_packet('/key', {'key': 'right'})
        self.assertEqual(struct.unpack('>BBIII', packet[:14]), (0, 0, 22, 0, 0))
        self.assertEqual(struct.unpack('>BBIII', packet[14:]), (0, 1, 22, 0, 0))

    def test_touch_uses_actual_video_size_and_rejects_outside(self):
        packet = remote.control_packet('/tap', {'x': 480, 'y': 270, 'width': 960, 'height': 540})
        down = struct.unpack('>BBqiiHHHII', packet[:32])
        self.assertEqual(down[:7], (2, 0, -2, 480, 270, 960, 540))
        with self.assertRaises(ValueError):
            remote.control_packet('/tap', {'x': 960, 'y': 0, 'width': 960, 'height': 540})


if __name__ == '__main__':
    unittest.main()
