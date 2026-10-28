#!/usr/bin/env python3
"""Local-only TV preview and button remote, using the already authorized ADB."""
import argparse
import base64
import hashlib
import socket
import struct
import queue
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import secrets
import subprocess
import threading
import time
import webbrowser
import runpy

KEYS = {'up':19,'down':20,'left':21,'right':22,'ok':23,'back':4,
        'home':3,'menu':82,'play':85,'next':87,'previous':88,
        'volume_up':24,'volume_down':25,'mute':164}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--device', required=True)
    parser.add_argument('--adb-port', default='5037')
    parser.add_argument('--scrcpy-server', type=Path, help='Path to official scrcpy 5.0.1 server')
    parser.add_argument('--port', type=int, default=8766)
    parser.add_argument('--no-browser', action='store_true')
    args = parser.parse_args()
    base = [args.adb, '-P', args.adb_port]
    subprocess.run(base + ['connect', args.device], check=True, timeout=15)
    adb = base + ['-s', args.device]
    state = {'frame': b'', 'error': '正在获取电视画面…', 'sequence':0, 'updated':0}
    lock = threading.Lock()
    controls = threading.Lock()
    stop = threading.Event()
    token = secrets.token_urlsafe(24)

    subscribers = set()
    video_lock = threading.Lock()
    codec_config = b''
    video_socket = None
    control_socket = None
    mirror_process = None
    mirror_port = 18767
    def receive(sock, size):
        data = bytearray()
        while len(data) < size:
            part = sock.recv(size-len(data))
            if not part:
                raise ConnectionError('视频连接断开')
            data.extend(part)
        return bytes(data)

    def capture_once():
        nonlocal control_socket, video_socket, codec_config, mirror_process
        server_jar = args.scrcpy_server or (Path(args.adb).parents[2] / 'scrcpy/scrcpy-macos-aarch64-v5.0.1/scrcpy-server')
        try:
            subprocess.run(adb+['push',str(server_jar),'/data/local/tmp/netodia-mirror.jar'],check=True,capture_output=True,timeout=20)
            scid = secrets.token_hex(4)
            scid = '%08x' % (int(scid,16) & 0x7fffffff)
            subprocess.run(adb+['forward','tcp:'+str(mirror_port),'localabstract:scrcpy_'+scid],check=True,capture_output=True,timeout=10)
            mirror_process = subprocess.Popen(adb+['shell','CLASSPATH=/data/local/tmp/netodia-mirror.jar app_process / com.genymobile.scrcpy.Server 5.0.1 scid='+scid+' audio=false control=true tunnel_forward=true max_size=1280 max_fps=30 video_bit_rate=3000000 send_device_meta=false send_dummy_byte=false send_stream_meta=false cleanup=false'],stdout=open("/tmp/netodia-mirror-video.log","w"),stderr=subprocess.STDOUT)
            time.sleep(.8)
            video_socket = socket.create_connection(('127.0.0.1',mirror_port),timeout=15)
            control_socket = socket.create_connection(('127.0.0.1',mirror_port),timeout=15)
            video_socket.settimeout(None)
            control_socket.settimeout(3)
            while not stop.is_set():
                pts,size=struct.unpack('>QI',receive(video_socket,12))
                if size>16000000:
                    raise RuntimeError('无效视频帧')
                packet=receive(video_socket,size)
                config=bool(pts & (1<<62)); key=bool(pts & (1<<61))
                stamp=pts & ((1<<61)-1)
                if config:
                    codec_config=packet
                    continue
                payload=bytes([1 if key else 0])+struct.pack('>Q',stamp)+(codec_config if key else b'')+packet
                with video_lock:
                    for client in list(subscribers):
                        try:client.put_nowait(payload)
                        except queue.Full:
                            # Disconnect slow clients so they restart at a key frame, without backlog.
                            subscribers.discard(client)
                            while not client.empty():client.get_nowait()
                            client.put_nowait(None)
                with lock:
                    state.update(error='',sequence=state['sequence']+1,updated=time.time())
        except Exception as exc:
            with lock:state['error']='视频连接失败：'+str(exc)

    def capture():
        nonlocal control_socket,video_socket,codec_config,mirror_process
        while not stop.is_set():
            with lock:state.update(sequence=0,error='正在连接电视视频…')
            codec_config=b''
            capture_once()
            for sock in (video_socket,control_socket):
                if sock:
                    try:sock.close()
                    except OSError:pass
            control_socket=None;video_socket=None
            if mirror_process:mirror_process.terminate()
            with video_lock:
                for client in list(subscribers):
                    while not client.empty():client.get_nowait()
                    client.put_nowait(None)
                subscribers.clear()
            stop.wait(1)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def reply(self, code, body, mime):
            self.send_response(code)
            self.send_header('Content-Type', mime)
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options','nosniff')
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_GET(self):
            path = self.path.split('?')[0]
            if path == '/video':
                if self.headers.get('Upgrade','').lower()!='websocket':
                    self.reply(400,b'WebSocket required','text/plain');return
                key=self.headers.get('Sec-WebSocket-Key','')
                accept=base64.b64encode(hashlib.sha1((key+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
                self.send_response(101)
                self.send_header('Upgrade','websocket');self.send_header('Connection','Upgrade')
                self.send_header('Sec-WebSocket-Accept',accept);self.end_headers()
                client=queue.Queue(maxsize=8)
                with video_lock:subscribers.add(client)
                waiting_key=True
                try:
                    self.connection.settimeout(3)
                    # Request a fresh keyframe on every new viewer.
                    if control_socket and state['sequence']>0:
                        with controls:control_socket.sendall(bytes([17]))
                    while not stop.is_set():
                        try:data=client.get(timeout=3)
                        except queue.Empty:continue
                        if data is None:break
                        if waiting_key and data[0]!=1:continue
                        waiting_key=False
                        n=len(data)
                        header=bytes([130,n]) if n<126 else bytes([130,126])+struct.pack('>H',n) if n<65536 else bytes([130,127])+struct.pack('>Q',n)
                        self.wfile.write(header+data);self.wfile.flush()
                except (OSError,ConnectionError):pass
                finally:
                    with video_lock:subscribers.discard(client)
                    self.close_connection=True
                return
            if path == '/':
                html = Path(__file__).with_name('index.html').read_text().replace('__TOKEN__', token)
                self.reply(200, html.encode(), 'text/html; charset=utf-8')
            elif path == '/frame':
                with lock:
                    frame = state['frame']
                self.reply(200 if frame else 503, frame or b'Waiting', 'image/png')
            elif path == '/status':
                with lock:
                    data = {key:value for key,value in state.items() if key != 'frame'}
                data['device'] = args.device
                data['serverId'] = token[:8]
                self.reply(200, json.dumps(data).encode(), 'application/json')
            else:
                self.reply(404,b'Not found','text/plain')

        def do_POST(self):
            if self.headers.get('X-TV-Token') != token:
                self.reply(403,b'Forbidden','text/plain'); return
            try:
                length = int(self.headers.get('Content-Length','0'))
                if not 0 < length <= 1024:
                    raise ValueError('Invalid request')
                data = json.loads(self.rfile.read(length))
                if self.path == '/key' and data.get('key') in KEYS:
                    command = ['shell','input','keyevent',str(KEYS[data['key']])]
                elif self.path in ('/tap','/scroll'):
                    width,height=int(data['width']),int(data['height'])
                    x,y=int(data['x']),int(data['y'])
                    if not (1<=width<=16384 and 1<=height<=16384 and 0<=x<width and 0<=y<height):
                        raise ValueError('点击坐标超出画面')
                    def touch(action):
                        return struct.pack('>BBqiiHHHII',2,action,-2,x,y,width,height,65535 if action==0 else 0,0,0)
                    if self.path=='/tap':command=touch(0)+touch(1)
                    else:
                        horizontal=max(-4,min(4,float(data.get('horizontal',0))))
                        vertical=max(-4,min(4,float(data.get('vertical',0))))
                        command=struct.pack('>BiiHHhhI',3,x,y,width,height,int(horizontal*2048),int(vertical*2048),0)
                elif self.path == '/launch':
                    command = ['shell','am','start','-n','com.netodia.tv/.MainActivity']
                else:
                    raise ValueError('Unknown button')
                with controls:
                    if self.path in ('/tap','/scroll'):
                        if not control_socket:raise RuntimeError('遥控通道正在连接')
                        control_socket.sendall(command)
                    elif self.path == '/key':
                        if not control_socket:raise RuntimeError('遥控通道正在连接')
                        code=KEYS[data['key']]
                        control_socket.sendall(struct.pack('>BBIII',0,0,code,0,0)+struct.pack('>BBIII',0,1,code,0,0))
                    else:
                        subprocess.run(adb+command, check=True, timeout=10, capture_output=True)
                self.reply(200,b'{"ok":true}','application/json')
            except Exception as exc:
                self.reply(400,json.dumps({'error':str(exc)}).encode(),'application/json')

    server = ThreadingHTTPServer(('127.0.0.1',args.port),Handler)
    threading.Thread(target=capture,daemon=True).start()
    url = 'http://127.0.0.1:%d' % server.server_port
    print('电视镜像遥控器：'+url, flush=True)
    print('关闭此终端或按 Ctrl+C 停止。H.264 视频镜像，持续遥控连接。',flush=True)
    if not args.no_browser:
        runpy.run_path(str(Path(__file__).with_name('open-preview.py')),run_name='__main__')
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        server.server_close()
        for sock in (video_socket,control_socket):
            if sock:sock.close()
        if mirror_process:mirror_process.terminate()

if __name__ == '__main__':
    main()
