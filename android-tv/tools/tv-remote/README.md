# 通用电视镜像遥控器

需要 Python 3、已授权的 ADB 连接、支持 WebCodecs 的 Chromium 浏览器，以及官方 [scrcpy 5.0.1 服务端](https://github.com/Genymobile/scrcpy/releases/tag/v5.0.1)。

```sh
python3 android-tv/tools/tv-remote/server.py \
  --adb /path/to/adb \
  --device TV_IP:5555 \
  --scrcpy-server /path/to/scrcpy-server
```

将 `TV_IP` 替换为电视局域网地址。服务仅监听 127.0.0.1，默认端口 8766；如需独立 ADB 服务，可传入 `--adb-port`。浏览器打开 http://127.0.0.1:8766。Ctrl+C 停止服务。

同一窗口左侧显示电视，右侧是遥控器。支持方向键、回车确定、Esc 返回、空格播放/暂停。镜像中可鼠标左键点击、右键返回、滚轮上下滚动。

电视硬件编码 H.264，默认 960 像素长边、24 fps、1.5 Mbps，实际帧率取决于设备。浏览器直接解码视频，按键与触摸通过浏览器 WebSocket 和 scrcpy 持久控制连接发送，无声音。

服务运行时生成临时访问令牌，不在代码中保存连接凭据。截图、个人设备配置、运行日志和浏览器缓存不应上传。

浏览器控制不会排队重放旧按键，方向键重复频率受控。视频缓冲有上限，拥堵时丢弃失效画面并等待关键帧；后台窗口暂停视频解码。画面静止时不持续刷新属于正常行为。

需要更清晰的预览时，可加 `--max-size 1280 --max-fps 30 --bit-rate 3000000`，同时留意电视编码负载和网络状况。服务使用独立自动分配的 ADB 转发端口，不关闭共享 ADB 服务。

协议回归检查（不连接电视）：`python3 -m unittest discover -s android-tv/tests -p 'test_tv_remote.py' -v`。

预览优先采用低延迟软件解码，避免部分浏览器硬件解码器暂存静止画面；在本机验证过静止画面首次显示和连续按键后的更新。延迟仍受电视编码器及局域网影响。

遥控器不绑定任何应用，主页、返回、方向键、媒体键及画面点击均作用于电视当前界面。
