#!/usr/bin/env python3
"""人臉註冊伺服器 —— 用瀏覽器上傳照片、標註人名，再讓眼鏡同步過去。

只用 Python 標準函式庫，**不需要 pip install 任何東西**。
也不需要 InsightFace 或任何模型 —— 特徵是眼鏡自己算的，這台機器只負責存照片。

為什麼特徵在眼鏡算而不是在這裡算：
    後端與眼鏡若用不同模型，特徵空間不同，比對結果是隨機的，而且不會報錯。
    同步照片、讓眼鏡用自己的模型重算，特徵空間就永遠一致。

用法：
    python face_enroll_server.py                # 預設 port 8100
    python face_enroll_server.py --port 9000
    python face_enroll_server.py --dir /path/to/photos

然後：
    1. 手機或電腦瀏覽器開 http://<這台機器的IP>:8100
       （啟動時會印出可用的網址）
    2. 選照片、打名字、上傳
    3. 對眼鏡說「同步人臉」

API（給眼鏡用）：
    GET /manifest          -> {"people":[{"name":"...","photos":["..."]}]}
    GET /photos/<路徑>     -> 圖片位元組
"""

from __future__ import annotations

import argparse
import html
import json
import re
import shutil
import socket
import sys
from dataclasses import dataclass
from email.parser import BytesParser
from email.policy import default as email_policy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote

ALLOWED_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp"}
MAX_PHOTO_BYTES = 10 * 1024 * 1024
MAX_BODY_BYTES = 60 * 1024 * 1024


@dataclass(slots=True)
class UploadedFile:
    field: str
    filename: str
    data: bytes


def parse_form(headers, rfile) -> tuple[dict[str, str], list[UploadedFile]]:
    """解析表單，支援 multipart 與 urlencoded。

    刻意不用 `cgi.FieldStorage` —— 它在 Python 3.13 已被移除，
    用了會讓這支程式在較新的 Python 上直接壞掉。改用 `email` 模組解析
    multipart，那是同一套 MIME 規格，而且不會被淘汰。
    """
    length = int(headers.get("Content-Length") or 0)
    if length <= 0:
        return {}, []
    if length > MAX_BODY_BYTES:
        raise ValueError("上傳內容過大")

    body = rfile.read(length)
    content_type = headers.get("Content-Type", "")

    if not content_type.lower().startswith("multipart/"):
        decoded = parse_qs(body.decode("utf-8", "replace"))
        return {k: v[0] for k, v in decoded.items()}, []

    # email 的解析器需要完整的 MIME 訊息，補上檔頭再交給它。
    raw = b"Content-Type: " + content_type.encode() + b"\r\nMIME-Version: 1.0\r\n\r\n" + body
    message = BytesParser(policy=email_policy).parsebytes(raw)

    fields: dict[str, str] = {}
    files: list[UploadedFile] = []
    for part in message.iter_parts():
        if part.get("Content-Disposition") is None:
            continue
        name = part.get_param("name", header="content-disposition")
        if not isinstance(name, str):
            continue
        payload = part.get_payload(decode=True) or b""
        filename = part.get_filename()
        if filename:
            files.append(UploadedFile(name, filename, payload))
        else:
            fields[name] = payload.decode("utf-8", "replace").strip()
    return fields, files

# 姓名會變成資料夾名稱，必須擋掉路徑穿越與檔案系統保留字元。
_UNSAFE_NAME = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


def safe_person_name(raw: str) -> str | None:
    """把使用者輸入的姓名轉成安全的資料夾名稱，不合法則回 None。"""
    name = unquote(raw or "").strip().strip(".")
    if not name or name in {".", ".."}:
        return None
    if _UNSAFE_NAME.search(name):
        return None
    if len(name) > 64:
        return None
    return name


class EnrollHandler(BaseHTTPRequestHandler):
    photo_root: Path = Path("face_photos")

    # --- 共用 ---------------------------------------------------------

    def _send(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        # 眼鏡與瀏覽器可能不同來源，開放讀取。這是區網內的開發工具。
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def _send_html(self, status: int, markup: str) -> None:
        self._send(status, markup.encode("utf-8"), "text/html; charset=utf-8")

    def _send_json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self._send(status, body, "application/json; charset=utf-8")

    def log_message(self, fmt: str, *args) -> None:  # noqa: A003
        sys.stderr.write("  %s\n" % (fmt % args))

    # --- 資料 ---------------------------------------------------------

    def _people(self) -> list[dict]:
        root = self.photo_root
        if not root.is_dir():
            return []
        people = []
        for person_dir in sorted(p for p in root.iterdir() if p.is_dir()):
            photos = sorted(
                f"{person_dir.name}/{f.name}"
                for f in person_dir.iterdir()
                if f.is_file() and f.suffix.lower() in ALLOWED_SUFFIXES
            )
            if photos:
                people.append({"name": person_dir.name, "photos": photos})
        return people

    # --- GET ----------------------------------------------------------

    def do_GET(self) -> None:  # noqa: N802
        path = unquote(self.path.split("?", 1)[0])

        if path == "/":
            self._send_html(200, self._page())
        elif path == "/manifest":
            self._send_json(200, {"people": self._people()})
        elif path.startswith("/photos/"):
            self._serve_photo(path[len("/photos/"):])
        else:
            self._send_html(404, "<h1>404</h1>")

    def _serve_photo(self, relative: str) -> None:
        root = self.photo_root.resolve()
        target = (root / relative).resolve()

        # 路徑穿越防護：解析後必須仍在 root 底下。
        if not target.is_file() or root not in target.parents:
            self._send_html(404, "<h1>找不到這張照片</h1>")
            return
        if target.suffix.lower() not in ALLOWED_SUFFIXES:
            self._send_html(403, "<h1>不支援的檔案類型</h1>")
            return

        mime = {
            ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
            ".png": "image/png", ".webp": "image/webp",
        }[target.suffix.lower()]
        self._send(200, target.read_bytes(), mime)

    # --- POST ---------------------------------------------------------

    def do_POST(self) -> None:  # noqa: N802
        path = self.path.split("?", 1)[0]
        if path == "/add":
            self._add_person()
        elif path == "/delete":
            self._delete_person()
        else:
            self._send_html(404, "<h1>404</h1>")

    def _add_person(self) -> None:
        try:
            fields, files = parse_form(self.headers, self.rfile)
        except ValueError as exc:
            self._send_html(413, self._page(str(exc)))
            return

        name = safe_person_name(fields.get("name", ""))
        if not name:
            self._send_html(400, self._page("姓名不可空白，也不能包含 / \\ : * ? \" < > |"))
            return

        items = [f for f in files if f.field == "photos" and f.filename]
        if not items:
            self._send_html(400, self._page("請至少選一張照片"))
            return

        person_dir = self.photo_root / name
        person_dir.mkdir(parents=True, exist_ok=True)

        saved, skipped = 0, 0
        existing = len(list(person_dir.glob("*")))
        for item in items:
            suffix = Path(item.filename).suffix.lower()
            if suffix not in ALLOWED_SUFFIXES or not item.data:
                skipped += 1
                continue
            if len(item.data) > MAX_PHOTO_BYTES:
                skipped += 1
                continue
            existing += 1
            (person_dir / f"{existing:03d}{suffix}").write_bytes(item.data)
            saved += 1

        note = f"已為「{name}」新增 {saved} 張照片"
        if skipped:
            note += f"（略過 {skipped} 張：格式不支援或過大）"
        if saved >= 1:
            note += "。現在對眼鏡說「同步人臉」"
        self._send_html(200, self._page(note))

    def _delete_person(self) -> None:
        try:
            fields, _ = parse_form(self.headers, self.rfile)
        except ValueError as exc:
            self._send_html(413, self._page(str(exc)))
            return
        name = safe_person_name(fields.get("name", ""))
        if not name:
            self._send_html(400, self._page("名稱不合法"))
            return
        target = (self.photo_root / name).resolve()
        root = self.photo_root.resolve()
        if target.is_dir() and root in target.parents:
            shutil.rmtree(target)
            self._send_html(200, self._page(f"已刪除「{name}」。記得再說一次「同步人臉」"))
        else:
            self._send_html(404, self._page("找不到這個人"))

    # --- 頁面 ---------------------------------------------------------

    def _page(self, note: str = "") -> str:
        people = self._people()
        total_photos = sum(len(p["photos"]) for p in people)

        rows = "".join(
            f"""
            <li>
              <b>{html.escape(p['name'])}</b>
              <span class="count">{len(p['photos'])} 張</span>
              <form method="post" action="/delete" class="inline"
                    onsubmit="return confirm('確定刪除 {html.escape(p['name'])}？')">
                <input type="hidden" name="name" value="{html.escape(p['name'])}">
                <button class="danger">刪除</button>
              </form>
            </li>"""
            for p in people
        ) or '<li class="empty">還沒有任何人</li>'

        note_html = f'<p class="note">{html.escape(note)}</p>' if note else ""

        return f"""<!doctype html>
<html lang="zh-Hant"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>人臉註冊 — Guide Glasses</title>
<style>
  :root {{ color-scheme: light dark; }}
  body {{ font-family: system-ui,-apple-system,"Noto Sans TC",sans-serif;
         max-width: 640px; margin: 0 auto; padding: 20px; line-height: 1.6; }}
  h1 {{ font-size: 1.4rem; }}
  .card {{ border: 1px solid rgba(128,128,128,.35); border-radius: 12px;
           padding: 16px; margin: 16px 0; }}
  label {{ display: block; margin: 12px 0 4px; font-weight: 600; }}
  input[type=text] {{ width: 100%; padding: 10px; font-size: 1rem; box-sizing: border-box;
                      border: 1px solid rgba(128,128,128,.5); border-radius: 8px; }}
  input[type=file] {{ width: 100%; padding: 10px 0; font-size: 1rem; }}
  button {{ padding: 10px 18px; font-size: 1rem; border-radius: 8px; cursor: pointer;
            border: 1px solid rgba(128,128,128,.5); background: #2e7d32; color: #fff; }}
  button.danger {{ background: transparent; color: #c62828; padding: 4px 10px; font-size: .85rem; }}
  .inline {{ display: inline; }}
  ul {{ list-style: none; padding: 0; }}
  li {{ display: flex; align-items: center; gap: 10px; padding: 8px 0;
        border-bottom: 1px solid rgba(128,128,128,.2); }}
  li.empty {{ opacity: .6; }}
  .count {{ opacity: .7; font-size: .9rem; margin-right: auto; }}
  .note {{ background: rgba(46,125,50,.15); padding: 10px 14px; border-radius: 8px; }}
  .hint {{ opacity: .75; font-size: .9rem; }}
</style></head><body>
<h1>人臉註冊</h1>
{note_html}

<div class="card">
  <form method="post" action="/add" enctype="multipart/form-data">
    <label for="name">姓名（會直接唸出來）</label>
    <input type="text" id="name" name="name" required placeholder="例如：王小明">

    <label for="photos">照片（可多選）</label>
    <input type="file" id="photos" name="photos" accept="image/*" multiple required>
    <p class="hint">建議 3–5 張不同角度與光線的正面照，辨識會穩很多。<br>
       手機點下去會直接開相簿。</p>

    <button type="submit">新增</button>
  </form>
</div>

<div class="card">
  <b>已註冊</b>（{len(people)} 人、{total_photos} 張照片）
  <ul>{rows}</ul>
</div>

<p class="hint">改完之後，對眼鏡說「<b>同步人臉</b>」。<br>
   照片存放在 <code>{html.escape(str(self.photo_root))}</code></p>
</body></html>"""


def local_ip() -> str:
    """取得本機在區網中的 IP。眼鏡要用這個位址連進來，不是 127.0.0.1。"""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))  # 不會真的送封包，只是讓 OS 選出對外介面
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


def main() -> int:
    parser = argparse.ArgumentParser(description="人臉註冊伺服器")
    parser.add_argument("--port", type=int, default=8100)
    parser.add_argument("--dir", default=str(Path(__file__).parent / "face_photos"))
    args = parser.parse_args()

    root = Path(args.dir).resolve()
    root.mkdir(parents=True, exist_ok=True)
    EnrollHandler.photo_root = root

    ip = local_ip()
    print("人臉註冊伺服器已啟動")
    print(f"  照片資料夾  {root}")
    print(f"  本機開      http://127.0.0.1:{args.port}")
    print(f"  手機／眼鏡  http://{ip}:{args.port}      <-- 用這個")
    print()
    print(f"眼鏡設定（寫進 guide-glasses/local.properties）：")
    print(f"  guideglasses.photoEndpoint=http://{ip}:{args.port}")
    print()
    print("Ctrl+C 結束")

    server = ThreadingHTTPServer(("0.0.0.0", args.port), EnrollHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
