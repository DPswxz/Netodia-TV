#!/usr/bin/env python3
"""Local TV preview with bounded video buffering and a persistent control connection."""
import argparse
import base64
import hashlib
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import queue
import runpy
import secrets
import select
import socket
import struct
import subprocess
import threading
import time

KEYS = {'up': 19, 'down': 20, 'left': 21, 'right': 22, 'ok': 23,
        'back': 4, 'home': 3, 'menu': 82, 'play': 85, 'next': 87,
        'previous': 88, 'volume_up': 24, 'volume_down': 25, 'mute': 164}


def receive(sock, size):
    data = bytearray()
    while len(data) < size:
        part = sock.recv(size - len(data))
        if not part:
            raise ConnectionError('Connection closed')
        data.extend(part)
    return bytes(data)


def ws_packet(payload, opcode=2):
    n = len(payload)
    header = bytes([0x80 | opcode, n]) if n < 126 else (
        bytes([0x80 | opcode, 126]) + struct.pack('>H', n) if n < 65536 else
        bytes([0x80 | opcode, 127]) + struct.pack('>Q', n))
    return header + payload


def ws_read(sock):
    first, second = receive(sock, 2)
    if not first & 0x80 or not second & 0x80:
        raise ValueError('Expected a masked, complete WebSocket message')
    length = second & 127
    if length == 126:
        length = struct.unpack('>H', receive(sock, 2))[0]
    elif length == 127:
        length = struct.unpack('>Q', receive(sock, 8))[0]
    if length > 4096:
        raise ValueError('Control message too large')
    mask = receive(sock, 4)
    body = receive(sock, length)
    return first & 15, bytes(value ^ mask[i % 4] for i, value in enumerate(body))


def control_packet(path, data):
    if path == '/key' and data.get('key') in KEYS:
        code = KEYS[data['key']]
        return struct.pack('>BBIII', 0, 0, code, 0, 0) + struct.pack('>BBIII', 0, 1, code, 0, 0)
    if path not in ('/tap', '/scroll'):
        raise ValueError('Unknown control')
    width, height = int(data['width']), int(data['height'])
    x, y = int(data['x']), int(data['y'])
    if not (1 <= width <= 16384 and 1 <= height <= 16384 and 0 <= x < width and 0 <= y < height):
        raise ValueError('点击坐标超出画面')
    if path == '/tap':
        def touch(action):
            return struct.pack('>BBqiiHHHII', 2, action, -2, x, y, width, height,
                               65535 if action == 0 else 0, 0, 0)
        return touch(0) + touch(1)
    horizontal = max(-4, min(4, float(data.get('horizontal', 0))))
    vertical = max(-4, min(4, float(data.get('vertical', 0))))
    return struct.pack('>BiiHHhhI', 3, x, y, width, height,
                       int(horizontal * 2048), int(vertical * 2048), 0)


class VideoMailbox:
    """Discard a broken GOP on congestion; never replay a growing queue of old frames."""
    def __init__(self):
        self.frames = queue.Queue(maxsize=3)
        self.waiting_key = True

    def clear(self):
        while True:
            try:
                self.frames.get_nowait()
            except queue.Empty:
                return

    def offer(self, packet):
        overflow = self.frames.full()
        if overflow:
            self.clear()
            self.waiting_key = True
        if self.waiting_key and packet[0] != 1:
            return overflow
        self.waiting_key = False
        self.frames.put_nowait(packet)
        return overflow

    def close(self):
        self.clear()
        self.frames.put_nowait(None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--device', required=True)
    parser.add_argument('--adb-port', default='5037')
    parser.add_argument('--scrcpy-server', type=Path)
    parser.add_argument('--port', type=int, default=8766)
    parser.add_argument('--max-size', type=int, default=960)
    parser.add_argument('--max-fps', type=int, default=24)
    parser.add_argument('--bit-rate', type=int, default=1500000)
    parser.add_argument('--no-browser', action='store_true')
    args = parser.parse_args()
    if not 320 <= args.max_size <= 3840 or not 1 <= args.max_fps <= 60 or not 100000 <= args.bit_rate <= 20000000:
        parser.error('Invalid video settings')
    base = [args.adb, '-P', args.adb_port]
    subprocess.run(base + ['connect', args.device], check=True, timeout=15)
    adb = base + ['-s', args.device]
    state = {'error': '正在连接电视…', 'sequence': 0, 'updated': 0, 'drops': 0}
    lock, controls, video_lock = threading.Lock(), threading.Lock(), threading.Lock()
    stop = threading.Event()
    token = secrets.token_urlsafe(24)
    subscribers = set()
    video_socket = control_socket = mirror_process = None
    mirror_port = 0
    last_key_request = 0.0

    def request_keyframe():
        nonlocal last_key_request
        now = time.monotonic()
        with controls:
            if control_socket and state['sequence'] > 0 and now - last_key_request >= 1:
                control_socket.sendall(bytes([17]))
                last_key_request = now

    def execute(path, data):
        if path == '/resync':
            request_keyframe()
            return
        packet = control_packet(path, data)
        with controls:
            if not control_socket:
                raise RuntimeError('遥控通道正在连接')
            control_socket.sendall(packet)

    def capture():
        nonlocal video_socket, control_socket, mirror_process, mirror_port
        server_jar = args.scrcpy_server or (Path(args.adb).parents[2] /
            'scrcpy/scrcpy-macos-aarch64-v5.0.1/scrcpy-server')
        try:
            subprocess.run(adb + ['push', str(server_jar), '/data/local/tmp/netodia-mirror.jar'],
                           check=True, capture_output=True, timeout=20)
            while not stop.is_set():
                try:
                    with lock:
                        state.update(sequence=0, error='正在连接电视…')
                    scid = '%08x' % (secrets.randbits(31))
                    forwarded = subprocess.run(adb + ['forward', 'tcp:0', 'localabstract:scrcpy_' + scid],
                                               check=True, capture_output=True, text=True, timeout=10)
                    mirror_port = int(forwarded.stdout.strip())
                    options = ('5.0.1 scid=' + scid + ' audio=false control=true tunnel_forward=true'
                               ' max_size=' + str(args.max_size) + ' max_fps=' + str(args.max_fps) +
                               ' video_bit_rate=' + str(args.bit_rate) +
                               ' video_codec_options=i-frame-interval=1'
                               ' send_device_meta=false send_dummy_byte=false send_stream_meta=false cleanup=false')
                    mirror_process = subprocess.Popen(adb + ['shell',
                        'CLASSPATH=/data/local/tmp/netodia-mirror.jar app_process / com.genymobile.scrcpy.Server ' + options],
                        stdout=open("/tmp/netodia-mirror-session.log", "w"), stderr=subprocess.STDOUT)
                    deadline = time.monotonic() + 12
                    while not stop.is_set():
                        sockets = subprocess.run(adb + ['shell', 'cat', '/proc/net/unix'],
                            capture_output=True, text=True, timeout=3)
                        if 'scrcpy_' + scid in sockets.stdout:
                            break
                        if mirror_process.poll() is not None or time.monotonic() > deadline:
                            raise ConnectionError('电视编码服务未就绪')
                        stop.wait(.2)
                    video_socket = socket.create_connection(('127.0.0.1', mirror_port), timeout=10)
                    control_socket = socket.create_connection(('127.0.0.1', mirror_port), timeout=10)
                    for sock in (video_socket, control_socket):
                        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                    video_socket.settimeout(None)
                    control_socket.settimeout(.75)
                    codec_config = b''
                    while not stop.is_set():
                        pts, size = struct.unpack('>QI', receive(video_socket, 12))
                        if size > 16000000:
                            raise ValueError('Invalid video packet')
                        packet = receive(video_socket, size)
                        if pts & (1 << 62):
                            codec_config = packet
                            continue
                        # scrcpy 5.0.1: session bit 63 (disabled), config bit 62, keyframe bit 61.
                        key = bool(pts & (1 << 61))
                        stamp = pts & ((1 << 61) - 1)
                        payload = bytes([int(key)]) + struct.pack('>Q', stamp) + (codec_config if key else b'') + packet
                        congested = False
                        with video_lock:
                            for client in subscribers:
                                congested = client.offer(payload) or congested
                        with lock:
                            state.update(error='', sequence=state['sequence'] + 1, updated=time.time())
                            if congested:
                                state['drops'] += 1
                        if congested:
                            request_keyframe()
                except Exception as error:
                    with lock:
                        state['error'] = '镜像连接中断，正在重连'
                        state['diagnostic'] = str(error)
                finally:
                    with controls:
                        for sock in (video_socket, control_socket):
                            if sock:
                                sock.close()
                        video_socket = control_socket = None
                    if mirror_process:
                        mirror_process.terminate()
                    if mirror_port:
                        subprocess.run(adb + ['forward', '--remove', 'tcp:' + str(mirror_port)],
                                       capture_output=True, timeout=5)
                        mirror_port = 0
                    with video_lock:
                        for client in subscribers:
                            client.close()
                        subscribers.clear()
                stop.wait(.5)
        except Exception:
            with lock:
                state['error'] = '无法启动镜像，请检查 ADB 与 scrcpy 路径'

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def reply(self, code, body, mime='application/json'):
            self.send_response(code)
            self.send_header('Content-Type', mime)
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            try:
                self.wfile.write(body)
            except OSError:
                pass

        def upgrade(self):
            if self.headers.get('Upgrade', '').lower() != 'websocket':
                raise ValueError('WebSocket required')
            key = self.headers.get('Sec-WebSocket-Key', '')
            accept = base64.b64encode(hashlib.sha1((key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
            self.send_response(101)
            self.send_header('Upgrade', 'websocket')
            self.send_header('Connection', 'Upgrade')
            self.send_header('Sec-WebSocket-Accept', accept)
            self.end_headers()
            self.connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.close_connection = True

        def do_GET(self):
            path = self.path.split('?')[0]
            if path in ('/control', '/video'):
                from urllib.parse import parse_qs, urlsplit
                supplied = parse_qs(urlsplit(self.path).query).get('token', [''])[0]
                if not secrets.compare_digest(supplied, token):
                    self.reply(403, b'Forbidden', 'text/plain')
                    return
                try:
                    self.upgrade()
                    if path == '/control':
                        self.control_stream()
                    else:
                        self.video_stream()
                except (OSError, ValueError, ConnectionError):
                    pass
                return
            if path == '/':
                html = Path(__file__).with_name('index.html').read_text().replace('__TOKEN__', token)
                self.reply(200, html.encode(), 'text/html; charset=utf-8')
            elif path == '/status':
                with lock:
                    data = dict(state)
                data.update(serverId=token[:8], maxSize=args.max_size, maxFps=args.max_fps)
                self.reply(200, json.dumps(data).encode())
            else:
                self.reply(404, b'Not found', 'text/plain')

        def control_stream(self):
            self.connection.settimeout(3)
            while not stop.is_set():
                if not select.select([self.connection], [], [], 1)[0]:
                    continue
                opcode, body = ws_read(self.connection)
                if opcode == 8:
                    return
                if opcode == 9:
                    self.connection.sendall(ws_packet(body, 10))
                    continue
                if opcode != 1:
                    continue
                data = json.loads(body)
                started = time.monotonic()
                try:
                    execute('/' + data.get('action', ''), data)
                    result = {'ok': True, 'id': data.get('id'), 'serverMs': round((time.monotonic() - started) * 1000, 2)}
                except Exception as error:
                    result = {'ok': False, 'id': data.get('id'), 'error': str(error)}
                self.connection.sendall(ws_packet(json.dumps(result).encode(), 1))

        def video_stream(self):
            client = VideoMailbox()
            with video_lock:
                subscribers.add(client)
            try:
                self.connection.settimeout(.3)
                request_keyframe()
                while not stop.is_set():
                    try:
                        data = client.frames.get(timeout=1)
                    except queue.Empty:
                        continue
                    if data is None:
                        return
                    self.connection.sendall(ws_packet(data))
            finally:
                with video_lock:
                    subscribers.discard(client)

        def do_POST(self):
            if not secrets.compare_digest(self.headers.get('X-TV-Token', ''), token):
                self.reply(403, b'Forbidden', 'text/plain')
                return
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 4096:
                    raise ValueError('Invalid request')
                execute(self.path, json.loads(self.rfile.read(length)))
                self.reply(200, b'{"ok":true}')
            except Exception as error:
                self.reply(400, json.dumps({'error': str(error)}).encode())

    server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    threading.Thread(target=capture, daemon=True).start()
    print('电视镜像遥控器：http://127.0.0.1:' + str(server.server_port), flush=True)
    if not args.no_browser:
        runpy.run_path(str(Path(__file__).with_name('open-preview.py')),
                       init_globals={'PREVIEW_URL': 'http://127.0.0.1:' + str(server.server_port)}, run_name='__main__')
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        server.server_close()
        with controls:
            for sock in (video_socket, control_socket):
                if sock:
                    sock.close()
        if mirror_process:
            mirror_process.terminate()
        if mirror_port:
            subprocess.run(adb + ['forward', '--remove', 'tcp:' + str(mirror_port)],
                           capture_output=True, timeout=5)


if __name__ == '__main__':
    main()
