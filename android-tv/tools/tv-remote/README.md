# 电视镜像遥控器

需要 Python 3、已授权的 ADB 连接、支持 WebCodecs 的 Chromium 浏览器，以及官方 [scrcpy 5.0.1 服务端](https://github.com/Genymobile/scrcpy/releases/tag/v5.0.1)。

```sh
python3 android-tv/tools/tv-remote/server.py \
  --adb /path/to/adb \
  --device TV_IP:5555 \
  --scrcpy-server /path/to/scrcpy-server
```

将 `TV_IP` 替换为电视局域网地址。服务仅监听 127.0.0.1，默认端口 8766；如需独立 ADB 服务，可传入 `--adb-port`。浏览器打开 http://127.0.0.1:8766。Ctrl+C 停止服务。

同一窗口左侧显示电视，右侧是遥控器。支持方向键、回车确定、Esc 返回、空格播放/暂停。镜像中可鼠标左键点击、右键返回、滚轮上下滚动。

电视硬件编码 H.264，最高 1280 像素长边、30 fps、3 Mbps，实际帧率取决于设备。浏览器直接解码视频，按键与触摸通过持久控制连接发送，无声音。

服务运行时生成临时访问令牌，不在代码中保存连接凭据。截图、个人设备配置、运行日志和浏览器缓存不应上传。
