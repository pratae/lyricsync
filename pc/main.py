import json
import sys
import threading
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from pathlib import Path

from PySide6.QtCore import QPoint, Qt, QTimer, QSize
from PySide6.QtGui import QColor, QFont, QIcon, QMouseEvent, QPainter, QPixmap
from PySide6.QtWidgets import (
    QApplication,
    QFrame,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QVBoxLayout,
    QWidget,
)
from PySide6.QtSvg import QSvgRenderer


# ========= 播放状态 =========


@dataclass
class PlayerState:
    track: str = "No Track"
    artist: str = "Unknown Artist"
    lyric: str = "No lyric yet"
    translation: str = ""
    delay_ms: int = 0


STATE = PlayerState()


# ========= HTTP Server =========


def make_handler(state: PlayerState):
    class LyricSyncRequestHandler(BaseHTTPRequestHandler):
        def _send_ok(self):
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(b'{"status":"ok"}')

        def do_POST(self):
            try:
                length = int(self.headers.get("Content-Length", "0"))
                raw = self.rfile.read(length)
                payload = json.loads(raw.decode("utf-8"))
            except Exception as e:
                self.send_response(400)
                self.end_headers()
                self.wfile.write(f"Bad request: {e}".encode("utf-8"))
                return

            path = self.path
            msg_type = payload.get("type")

            if path == "/sync" or msg_type == "track":
                title = payload.get("title") or state.track
                artist = payload.get("artist") or state.artist
                state.track = title
                state.artist = artist

            if path == "/lyric" or msg_type in ("lyric", "stop"):
                if msg_type == "lyric":
                    state.lyric = payload.get("lyric") or state.lyric
                    state.translation = payload.get("translation") or ""
                    try:
                        state.delay_ms = int(payload.get("delay") or 0)
                    except Exception:
                        state.delay_ms = 0
                elif msg_type == "stop":
                    state.lyric = "Playback stopped"
                    state.translation = ""
                    state.delay_ms = 0

            self._send_ok()

        def log_message(self, format, *args):
            return

    return LyricSyncRequestHandler


def start_server(state: PlayerState, port: int = 8080):
    handler_cls = make_handler(state)
    server = ThreadingHTTPServer(("", port), handler_cls)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server


# ========= PySide6 Overlay =========


class LyricOverlay(QWidget):
    def __init__(self, state: PlayerState, port: int = 8080):
        super().__init__()
        self.state = state
        self.port = port
        self.base_dir = Path(__file__).resolve().parent

        self.locked = False
        self.alpha = 0.85
        self.bg_color = QColor(15, 17, 26)
        self.button_bg = QColor(40, 48, 68)
        self.button_active = QColor(50, 59, 82)
        self.border_color = QColor(86, 104, 255)
        self.border_width = 2
        self.icon_path_lock = self.base_dir / "icon" / "lock-line.svg"
        self.icon_path_unlock = self.base_dir / "icon" / "lock-unlock-line.svg"
        self.icon_path_close = self.base_dir / "icon" / "close-line.svg"
        self._drag_pos: QPoint | None = None
        self._resize_dir = None
        self._resize_start_geom = None
        self._unlock_timer = QTimer(self)
        self._unlock_timer.setSingleShot(True)
        self._unlock_timer.setInterval(2000)
        self._unlock_timer.timeout.connect(self._show_unlock_button)
        self._unlock_visible = False

        self.setWindowFlags(
            Qt.FramelessWindowHint | Qt.Tool | Qt.WindowStaysOnTopHint | Qt.NoDropShadowWindowHint
        )
        self.setAttribute(Qt.WA_TranslucentBackground, True)
        self.setMouseTracking(True)

        self._build_ui()
        self._position_default()

        self.timer = QTimer(self)
        self.timer.setInterval(200)
        self.timer.timeout.connect(self.refresh_ui)
        self.timer.start()

    # ------ UI 构建 ------

    def _build_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        self.container = QFrame()
        self.container.setStyleSheet("background: transparent;")
        layout.addWidget(self.container)

        vbox = QVBoxLayout(self.container)
        vbox.setContentsMargins(12, 10, 12, 10)
        vbox.setSpacing(4)

        top_bar = QHBoxLayout()
        top_bar.setSpacing(8)
        top_bar.setAlignment(Qt.AlignHCenter | Qt.AlignVCenter)
        self.btn_lock = self._make_icon_button(
            self.icon_path_lock, self.toggle_lock
        )
        self.btn_close = self._make_icon_button(
            self.icon_path_close, self.close
        )
        top_bar.addWidget(self.btn_lock)
        top_bar.addWidget(self.btn_close)
        vbox.addLayout(top_bar)

        font_track = QFont("Segoe UI", 10)
        font_lyric = QFont("Segoe UI", 16, QFont.Bold)
        font_trans = QFont("Segoe UI", 10)

        self.lbl_track = QLabel("")
        self.lbl_track.setFont(font_track)
        self.lbl_track.setStyleSheet("color: #BBBBBB; background: transparent;")
        vbox.addWidget(self.lbl_track)

        self.lbl_lyric = QLabel(self.state.lyric)
        self.lbl_lyric.setFont(font_lyric)
        self.lbl_lyric.setStyleSheet("color: white; background: transparent;")
        vbox.addWidget(self.lbl_lyric)

        self.lbl_trans = QLabel("")
        self.lbl_trans.setFont(font_trans)
        self.lbl_trans.setStyleSheet("color: #CCCCCC; background: transparent;")
        vbox.addWidget(self.lbl_trans)

        # 悬浮解锁按钮（默认隐藏）
        self.unlock_button = self._make_icon_button(
            self.icon_path_unlock, self._unlock
        )
        self.unlock_button.setParent(self)
        self.unlock_button.setVisible(False)
        self.unlock_button.setFixedSize(32, 32)
        self.unlock_button.setStyleSheet(
            """
            QPushButton {
                background-color: transparent;
                border: 0px;
                border-radius: 16px;
            }
            QPushButton:hover { background-color: transparent; }
            """
        )

    def _make_icon_button(self, icon_path: Path, slot):
        btn = QPushButton()
        btn.clicked.connect(slot)
        btn.setFixedSize(28, 24)
        normal_icon = self._load_icon(icon_path, color=QColor("white"))
        hover_icon = self._load_icon(icon_path, color=QColor(200, 220, 255))
        btn.setIcon(normal_icon)
        btn.setIconSize(btn.size() - QSize(8, 8))
        btn.setStyleSheet(
            """
            QPushButton {
                background-color: transparent;
                border: 0px;
                padding: 2px 6px;
            }
            QPushButton:hover {
                background-color: transparent;
            }
            """
        )

        def on_enter(event):
            btn.setIcon(hover_icon)
            QPushButton.enterEvent(btn, event)

        def on_leave(event):
            btn.setIcon(normal_icon)
            QPushButton.leaveEvent(btn, event)

        btn.enterEvent = on_enter
        btn.leaveEvent = on_leave
        return btn

    # ------ 位置与尺寸 ------

    def _position_default(self):
        screen = QApplication.primaryScreen().availableGeometry()
        width = 700
        height = 90
        x = (screen.width() - width) // 2
        y = screen.height() - height - 80
        self.setGeometry(x, y, width, height)

    # ------ 绘制背景/边框 ------

    def paintEvent(self, event):
        painter = QPainter(self)
        painter.setRenderHints(QPainter.Antialiasing)
        rect = self.rect().adjusted(1, 1, -1, -1)
        bg = QColor(self.bg_color)
        bg.setAlphaF(0.0 if self.locked else self.alpha)
        painter.setBrush(bg)
        pen = QColor(self.border_color)
        pen.setAlphaF(0.0 if self.locked else 1.0)
        painter.setPen(pen)
        painter.drawRoundedRect(rect, 6, 6)
        super().paintEvent(event)

    # ------ 鼠标拖动 / 拉伸 ------

    def mousePressEvent(self, event: QMouseEvent):
        if self.locked:
            return
        if event.button() == Qt.LeftButton:
            dir_hit = self._hit_test(event.position().toPoint())
            if dir_hit:
                self._resize_dir = dir_hit
                self._resize_start_geom = (self.geometry(), event.globalPosition())
            else:
                self._drag_pos = event.globalPosition().toPoint() - self.frameGeometry().topLeft()
        super().mousePressEvent(event)

    def mouseMoveEvent(self, event: QMouseEvent):
        if self.locked:
            self._maybe_start_unlock_timer()
            return
        if self._resize_dir and self._resize_start_geom:
            self._perform_resize(event.globalPosition())
            return
        if self._drag_pos and event.buttons() & Qt.LeftButton:
            self.move(event.globalPosition().toPoint() - self._drag_pos)
        else:
            cursor = self._cursor_for_pos(event.position().toPoint())
            self.setCursor(cursor)
        super().mouseMoveEvent(event)

    def mouseReleaseEvent(self, event: QMouseEvent):
        self._drag_pos = None
        self._resize_dir = None
        self._resize_start_geom = None
        self.setCursor(Qt.ArrowCursor)
        super().mouseReleaseEvent(event)

    def enterEvent(self, event):
        if self.locked:
            self._maybe_start_unlock_timer()
        super().enterEvent(event)

    def leaveEvent(self, event):
        if not self.locked:
            self._unlock_timer.stop()
            if self.unlock_button:
                self.unlock_button.hide()
        super().leaveEvent(event)

    def _hit_test(self, pos: QPoint):
        margin = 8
        rect = self.rect()
        left = pos.x() <= margin
        right = pos.x() >= rect.width() - margin
        top = pos.y() <= margin
        bottom = pos.y() >= rect.height() - margin
        if left and top:
            return "tl"
        if right and top:
            return "tr"
        if left and bottom:
            return "bl"
        if right and bottom:
            return "br"
        if left:
            return "l"
        if right:
            return "r"
        if top:
            return "t"
        if bottom:
            return "b"
        return None

    def _cursor_for_pos(self, pos: QPoint):
        hit = self._hit_test(pos)
        if hit in ("l", "r"):
            return Qt.SizeHorCursor
        if hit in ("t", "b"):
            return Qt.SizeVerCursor
        if hit in ("tl", "br"):
            return Qt.SizeFDiagCursor
        if hit in ("tr", "bl"):
            return Qt.SizeBDiagCursor
        return Qt.ArrowCursor

    def _perform_resize(self, global_pos):
        geom, start_pos = self._resize_start_geom
        dx = global_pos.x() - start_pos.x()
        dy = global_pos.y() - start_pos.y()
        x, y, w, h = geom.x(), geom.y(), geom.width(), geom.height()
        min_w, min_h = 300, 70

        if "l" in self._resize_dir:
            new_w = max(min_w, w - dx)
            x = x + (w - new_w)
            w = new_w
        if "r" in self._resize_dir:
            w = max(min_w, w + dx)
        if "t" in self._resize_dir:
            new_h = max(min_h, h - dy)
            y = y + (h - new_h)
            h = new_h
        if "b" in self._resize_dir:
            h = max(min_h, h + dy)

        self.setGeometry(int(x), int(y), int(w), int(h))

    # ------ 控制事件 ------

    def toggle_lock(self):
        if self.locked:
            self._unlock()
        else:
            self._lock()

    def _lock(self):
        self.locked = True
        self.btn_close.hide()
        self.btn_lock.hide()
        self._drag_pos = None
        self._resize_dir = None
        self._resize_start_geom = None
        self._unlock_visible = False
        self._unlock_timer.stop()
        self._unlock_timer.start()
        self.unlock_button.hide()
        self.update()

    def _unlock(self):
        self.locked = False
        self.btn_close.show()
        self.btn_lock.show()
        self._unlock_timer.stop()
        self.unlock_button.hide()
        self._unlock_visible = False
        self.update()

    def closeEvent(self, event):
        if self.unlock_button:
            self.unlock_button.hide()
        event.accept()

    # ------ UI 刷新 ------

    def refresh_ui(self):
        if self.state.track and self.state.artist:
            self.lbl_track.setText(f"{self.state.track} · {self.state.artist}")
        else:
            self.lbl_track.setText(self.state.track or "")
        self.lbl_lyric.setText(self.state.lyric or "")
        self.lbl_trans.setText(self.state.translation or "")

    # ------ 样式应用 ------

    def _apply_background_alpha(self):
        self.update()

    def _show_unlock_button(self):
        if not self.locked:
            return
        if not self.unlock_button:
            return
        btn_w = self.unlock_button.width()
        btn_h = self.unlock_button.height()
        x = (self.width() - btn_w) // 2
        y = 10
        self.unlock_button.move(x, y)
        self.unlock_button.show()
        self.unlock_button.raise_()
        self._unlock_visible = True

    def _maybe_start_unlock_timer(self):
        if self.locked and not self._unlock_visible and not self._unlock_timer.isActive():
            self._unlock_timer.start()

    # ------ 图标加载 ------

    def _load_icon(self, path: Path, size: QSize = QSize(20, 20), color: QColor = QColor("white")) -> QIcon:
        path = Path(path)
        if path.suffix.lower() == ".svg":
            renderer = QSvgRenderer(str(path))
            base = QPixmap(size)
            base.fill(Qt.transparent)
            painter = QPainter(base)
            renderer.render(painter)
            painter.end()
            pixmap = base
        else:
            pixmap = QPixmap(str(path)).scaled(size, Qt.KeepAspectRatio, Qt.SmoothTransformation)

        if pixmap.isNull():
            return QIcon(str(path))

        tinted = QPixmap(pixmap.size())
        tinted.fill(Qt.transparent)
        painter = QPainter(tinted)
        painter.fillRect(tinted.rect(), color)
        painter.setCompositionMode(QPainter.CompositionMode_DestinationIn)
        painter.drawPixmap(0, 0, pixmap)
        painter.end()
        return QIcon(tinted)


def main():
    port = 8080
    start_server(STATE, port=port)
    app = QApplication(sys.argv)
    overlay = LyricOverlay(STATE, port=port)
    overlay.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
