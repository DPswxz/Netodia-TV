# Netodia TV

基于 [Netodia](https://github.com/HELPMEEADICE/Netodia) 的 Android TV 移植版，保留原项目源码与署名，电视端使用原生 Android 界面，复用 Rust 网络和核心模块。

## 相比原版修改了什么

- **电视界面**：适配远距离阅读，调整侧边栏、歌单、收藏、最近播放及设置布局，歌曲列表每页显示六首。
- **遥控器操作**：补齐方向键、确定、返回的焦点导航，支持队列多选、翻页、弹出菜单和播放控制。
- **歌词体验**：进入歌词区定位当前句并跟随播放；手动浏览后，空闲五秒恢复跟随。
- **Android 播放与平台适配**：使用 Android 播放器、后台服务和媒体会话；替换桌面相关集成，隐藏电视端不可用的选项。
- **电脑预览工具**：提供通用电视镜像遥控器，支持实时画面、鼠标点击和按键操作。

构建与安装见 [Android TV 说明](android-tv/README.md)，镜像工具见 [电视镜像遥控器说明](android-tv/tools/tv-remote/README.md)。本仓库已停用原桌面版的 GitHub Actions 自动构建。

## 来源与署名

- **Netodia 原项目 / 网易云音乐适配**：[HELPMEEADICE / Netodia](https://github.com/HELPMEEADICE/Netodia)
- **原始播放器与 UI 框架**：[Kenan Salar / Melodia](https://github.com/KenanSalar/Melodia)
- **Android TV 移植与维护**：[DPswxz / Netodia-TV](https://github.com/DPswxz/Netodia-TV)

许可证：[AGPL-3.0-or-later](LICENSE)。第三方组件许可见 [licenses/](licenses/)。
