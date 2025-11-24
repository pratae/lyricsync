# LyricSync

桌面端（PySide6）悬浮歌词 + HTTP 接口，配套 Android 客户端（`android/`）。

## 桌面端（pc/main.py）
- 无边框、可拖动/拉伸，置顶显示。
- 顶部图标：锁定/解锁、关闭。锁定后背景全透明，2 秒后出现悬浮解锁按钮。
- 歌词/翻译信息每 200ms 刷新。

### 运行
```bash
pip install PySide6
python pc/main.py
```

### HTTP 接口（默认端口 8080）
- `POST /sync` 或 payload `{"type":"track"}`：`title`, `artist`
- `POST /lyric` 或 payload `{"type":"lyric"}`：`lyric`, `translation`（可选），`delay`（毫秒，可选）
- `POST /lyric` 或 payload `{"type":"stop"}`：停止并清空当前歌词


## Android 模块
- 路径：`android/`
- 构建：在 `android/` 目录执行 `./gradlew assembleDebug`（或使用 Android Studio 打开）。
- 运行前需解锁安卓设备并安装必要框架，同时需安装第三方模块 [SuperLyric](https://github.com/HChenX/SuperLyric)（感谢该项目提供能力）。

## 开发
- Python 3.10+

## 鸣谢
- 图标使用了 remixicon 的 SVG 资源。
- 感谢 [SuperLyric](https://github.com/HChenX/SuperLyric) 项目。
