"""
VideoGrab - Universal Video Downloader v4.0
Web-based UI with Flask backend + SSE progress streaming
"""

import os
import re
import sys
import json
import time
import signal
import argparse
import platform
import subprocess
import threading
import webbrowser
import tkinter as tk
from tkinter import filedialog
from pathlib import Path
from datetime import datetime

from flask import Flask, request, jsonify, Response, send_from_directory

APP_NAME = "VideoGrab"
APP_VER = "4.0"
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
STATIC_DIR = os.path.join(BASE_DIR, "static")
CONFIG_FILE = os.path.join(BASE_DIR, "config.json")
HISTORY_FILE = os.path.join(BASE_DIR, "history.json")

# Parse CLI args
_parser = argparse.ArgumentParser(description="VideoGrab - Universal Video Downloader")
_parser.add_argument("--port", type=int, default=8457, help="Port to run on (default: 8457)")
_parser.add_argument("--max-concurrent", type=int, default=3, help="Max concurrent downloads (default: 3)")
_args = _parser.parse_args()
PORT = _args.port
MAX_CONCURRENT = _args.max_concurrent

FORMATS = {
    "best": "bestvideo+bestaudio/best",
    "1080": "bestvideo[height<=1080]+bestaudio/best[height<=1080]",
    "720": "bestvideo[height<=720]+bestaudio/best[height<=720]",
    "480": "bestvideo[height<=480]+bestaudio/best[height<=480]",
    "audio": "bestaudio/best",
}

COOKIES_BROWSERS = ["chrome", "firefox", "edge", "opera", "safari", "chromium", "brave", "vivaldi"]

SUBTITLE_LANGS = ["en", "es", "fr", "de", "pt", "ja", "ko", "zh", "ru", "ar", "hi", "all"]

app = Flask(__name__, static_folder=STATIC_DIR)

# ── State ──
downloads = {}
progress_listeners = {}
MAX_DOWNLOADS = 100  # Max entries to keep in memory


# ── Config ──
def load_config():
    try:
        if os.path.exists(CONFIG_FILE):
            with open(CONFIG_FILE, encoding="utf-8") as f:
                return json.load(f)
    except (OSError, json.JSONDecodeError):
        pass
    return {}


def save_config(cfg):
    try:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
    except OSError:
        pass


def get_save_path():
    c = load_config()
    if c.get("savePath") and os.path.exists(c["savePath"]):
        return c["savePath"]
    d = str(Path.home() / "Downloads" / "Videos")
    os.makedirs(d, exist_ok=True)
    return d


# ── History ──
def load_history():
    try:
        if os.path.exists(HISTORY_FILE):
            with open(HISTORY_FILE, encoding="utf-8") as f:
                return json.load(f)
    except (OSError, json.JSONDecodeError):
        pass
    return []


def save_history(url, title, status):
    history = load_history()
    history.append({
        "url": url,
        "title": title,
        "time": datetime.now().isoformat(),
        "status": status,
    })
    history = history[-100:]
    try:
        with open(HISTORY_FILE, "w", encoding="utf-8") as f:
            json.dump(history, f, indent=2, ensure_ascii=False)
    except OSError:
        pass


# ── Tools Check ──
def check_tools():
    tools = {}
    for name, cmd in [("yt-dlp", ["yt-dlp", "--version"]), ("ffmpeg", ["ffmpeg", "-version"])]:
        try:
            kwargs = {}
            if platform.system() == "Windows":
                kwargs["creationflags"] = subprocess.CREATE_NO_WINDOW
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=5, **kwargs)
            tools[name] = r.returncode == 0
        except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
            tools[name] = False
    return tools


# ── URL Detection ──
def detect_type(url):
    u = url.lower()
    if any(x in u for x in ["youtube", "youtu.be"]):
        return "▶", "YouTube"
    if "vimeo" in u:
        return "◉", "Vimeo"
    if "facebook" in u or "fb.watch" in u:
        return "f", "Facebook"
    if "instagram" in u:
        return "◎", "Instagram"
    if "tiktok" in u:
        return "♪", "TikTok"
    if "twitter" in u or "x.com" in u:
        return "✦", "Twitter/X"
    if "twitch" in u:
        return "◆", "Twitch"
    if "bilibili" in u:
        return "▶", "Bilibili"
    if "dailymotion" in u:
        return "◉", "Dailymotion"
    if any(x in u for x in ["porn", "xvideos", "xhamster", "xnxx"]):
        return "🔞", "Adult"
    if any(x in u for x in ["keep2share", "tezfiles", "rapidgator"]):
        return "📁", "File Host"
    if "mega.nz" in u:
        return "📁", "MEGA"
    if "mediafire" in u:
        return "📁", "MediaFire"
    if "gofile" in u:
        return "📁", "GoFile"
    if "drive.google" in u:
        return "📁", "Google Drive"
    if "dropbox" in u:
        return "📁", "Dropbox"
    if any(x in u for x in [".mp4", ".mkv", ".avi", ".mov", ".zip", ".rar"]):
        return "📎", "Direct"
    return "◉", "Video"


# ── Send SSE event (thread-safe) ──
_events_lock = threading.Lock()


def send_event(dl_id, event_type, data):
    with _events_lock:
        downloads[dl_id]["events"].append({
            "type": event_type,
            "data": data,
        })


def cleanup_old_downloads():
    """Remove finished downloads that completed >5 min ago, cap total entries."""
    now = time.time()
    to_remove = []
    for dl_id, dl in list(downloads.items()):
        if dl.get("finished") and dl.get("finish_time", now) < now - 300:
            to_remove.append(dl_id)
    for dl_id in to_remove:
        downloads.pop(dl_id, None)

    # Hard cap: remove oldest finished if over limit
    if len(downloads) > MAX_DOWNLOADS:
        finished = sorted(
            ((dl_id, dl) for dl_id, dl in downloads.items() if dl.get("finished")),
            key=lambda x: x[1].get("finish_time", 0),
        )
        for dl_id, _ in finished[: len(downloads) - MAX_DOWNLOADS]:
            downloads.pop(dl_id, None)


# ── Download Worker ──
def download_worker(dl_id):
    dl = downloads[dl_id]
    url = dl["url"]
    save_path = dl["save_path"]
    fmt = dl.get("format", "best")
    browser = dl.get("cookie_browser", "chrome")
    final_title = url

    try:
        send_event(dl_id, "status", {"message": "Starting download..."})

        format_str = FORMATS.get(fmt, FORMATS["best"])

        # Audio-only mode: download as mp3
        is_audio_only = fmt == "audio"

        cmd = [
            sys.executable, "-m", "yt_dlp",
        ]

        if is_audio_only:
            cmd.extend([
                "-f", format_str,
                "-x", "--audio-format", "mp3",
                "--audio-quality", "0",
            ])
        else:
            cmd.extend([
                "-f", format_str,
                "--merge-output-format", "mp4",
            ])

        cmd.extend([
            "-o", os.path.join(save_path, "%(title)s.%(ext)s"),
            "--newline", "--no-warnings", "--continue",
            "--encoding", "utf-8",
            "--force-ipv4",
            "--retries", "5",
            "--socket-timeout", "30",
            "--concurrent-fragments", "8",
            "--throttled-rate", "100K",
            "--buffer-size", "16K",
            "--http-chunk-size", "10M",
            "--referer", "/".join(url.split("/")[:3]) + "/",
            url,
        ])

        # Optional: skip certificate verification
        if dl.get("no_cert_check"):
            cmd.append("--no-check-certificates")

        if dl.get("use_cookies"):
            cmd.extend(["--cookies-from-browser", browser])

        # Subtitle options
        if dl.get("write_subs"):
            cmd.extend(["--write-subs", "--write-auto-subs"])
            sub_langs = dl.get("sub_langs", "en")
            cmd.extend(["--sub-langs", sub_langs])
            if not is_audio_only:
                cmd.append("--embed-subs")

        # Playlist option
        if dl.get("playlist"):
            cmd.append("--yes-playlist")
        else:
            cmd.append("--no-playlist")

        kwargs = {}
        if platform.system() == "Windows":
            kwargs["creationflags"] = subprocess.CREATE_NO_WINDOW

        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
            **kwargs,
        )
        dl["proc"] = proc

        # Collect stderr in a background thread for better error messages
        stderr_lines = []
        def read_stderr():
            for line in proc.stderr:
                stderr_lines.append(line.strip())
        stderr_thread = threading.Thread(target=read_stderr, daemon=True)
        stderr_thread.start()

        for line in proc.stdout:
            if dl["cancelled"]:
                proc.kill()
                break

            line = line.strip()
            if not line:
                continue

            if any(x in line.lower() for x in ["generic] falling", "generic] extract"]):
                continue

            if "[download]" in line and "%" in line:
                m = re.search(r"(\d+\.?\d*)%", line)
                if m:
                    pct = float(m.group(1))
                    data = {"percent": pct}
                    s = re.search(r"at\s+([^\s]+)", line)
                    if s:
                        data["speed"] = s.group(1)
                    z = re.search(r"of\s+([^\s]+)", line)
                    if z:
                        data["size"] = z.group(1)
                    eta = re.search(r"ETA\s+([^\s]+)", line)
                    if eta:
                        data["eta"] = eta.group(1)
                    send_event(dl_id, "progress", data)
            elif "[download]" in line and "Destination" in line:
                dest = line.split("Destination:")[-1].strip()
                dl["dest"] = dest
                final_title = os.path.basename(dest)
                send_event(dl_id, "title", {"title": final_title})
            elif "Merger" in line or "Merging" in line:
                send_event(dl_id, "status", {"message": "Merging audio & video..."})
            elif "already been downloaded" in line.lower():
                send_event(dl_id, "status", {"message": "File already exists"})
            elif "Downloading item" in line or "Downloading video" in line:
                m = re.search(r"(\d+)\s+of\s+(\d+)", line)
                if m:
                    send_event(dl_id, "playlist", {"current": int(m.group(1)), "total": int(m.group(2))})
            elif "[download] Downloading" in line and "playlist" in line.lower():
                m = re.search(r"(\d+)\s+videos", line)
                if m:
                    send_event(dl_id, "status", {"message": f"Playlist: {m.group(1)} videos"})

        proc.wait()
        stderr_thread.join(timeout=2)

        if not dl["cancelled"]:
            if proc.returncode == 0:
                send_event(dl_id, "complete", {"title": final_title})
                dl["finished"] = True
                dl["finish_time"] = time.time()
                save_history(url, final_title, "success")
            else:
                # Extract useful error from stderr
                error_msg = "Download failed"
                all_err = "\n".join(stderr_lines[-10:])  # last 10 stderr lines
                if "HTTP Error 403" in all_err or "403" in all_err:
                    error_msg = "Access denied (403) — try enabling cookies"
                elif "HTTP Error 404" in all_err or "404" in all_err:
                    error_msg = "Video not found (404)"
                elif "Sign in" in all_err or "login" in all_err.lower():
                    error_msg = "Login required — enable browser cookies"
                elif "geo" in all_err.lower() or "region" in all_err.lower():
                    error_msg = "Geo-restricted — content not available in your region"
                elif "Unsupported URL" in all_err:
                    error_msg = "Unsupported URL — this site may not be supported"
                elif stderr_lines:
                    # Use last meaningful stderr line
                    for line in reversed(stderr_lines):
                        if line and not line.startswith("[") and len(line) > 10:
                            error_msg = line[:100]
                            break
                send_event(dl_id, "error", {"message": error_msg})
                dl["finished"] = True
                dl["finish_time"] = time.time()
                save_history(url, final_title, "failed")

    except Exception as e:
        send_event(dl_id, "error", {"message": str(e)[:80]})
        dl["finished"] = True
        dl["finish_time"] = time.time()
        save_history(url, final_title, "error")
    finally:
        cleanup_old_downloads()


# ── Routes ──
@app.route("/")
def index():
    return send_from_directory(STATIC_DIR, "index.html")


@app.route("/api/info")
def info():
    tools = check_tools()
    return jsonify({
        "name": APP_NAME,
        "version": APP_VER,
        "savePath": get_save_path(),
        "tools": tools,
        "formats": list(FORMATS.keys()),
        "browsers": COOKIES_BROWSERS,
        "subtitleLangs": SUBTITLE_LANGS,
        "maxConcurrent": MAX_CONCURRENT,
    })


@app.route("/api/download", methods=["POST"])
def start_download():
    data = request.json
    url = data.get("url", "").strip()
    save_path = data.get("savePath", "").strip()

    if save_path:
        save_path = os.path.normpath(save_path)
    else:
        save_path = get_save_path()

    use_cookies = data.get("useCookies", False)
    fmt = data.get("format", "best")
    cookie_browser = data.get("cookieBrowser", "chrome")
    write_subs = data.get("writeSubs", False)
    sub_langs = data.get("subLangs", "en")
    no_cert_check = data.get("noCertCheck", False)
    playlist = data.get("playlist", False)

    if not url:
        return jsonify({"error": "URL is required"}), 400
    if not re.match(r"https?://", url, re.IGNORECASE):
        return jsonify({"error": "URL must start with http:// or https://"}), 400

    # Check concurrent limit
    active = sum(1 for dl in downloads.values() if not dl.get("finished"))
    if active >= MAX_CONCURRENT:
        return jsonify({"error": f"Max {MAX_CONCURRENT} concurrent downloads reached"}), 429

    os.makedirs(save_path, exist_ok=True)

    # Save path preference
    cfg = load_config()
    cfg["savePath"] = save_path
    save_config(cfg)

    dl_id = str(int(time.time() * 1000))
    icon, site = detect_type(url)

    downloads[dl_id] = {
        "url": url,
        "save_path": save_path,
        "site": site,
        "icon": icon,
        "use_cookies": use_cookies,
        "format": fmt,
        "cookie_browser": cookie_browser,
        "write_subs": write_subs,
        "sub_langs": sub_langs,
        "no_cert_check": no_cert_check,
        "playlist": playlist,
        "cancelled": False,
        "finished": False,
        "proc": None,
        "events": [],
        "event_index": 0,
    }

    t = threading.Thread(target=download_worker, args=(dl_id,), daemon=True)
    t.start()

    return jsonify({
        "id": dl_id,
        "icon": icon,
        "site": site,
        "url": url,
    })


@app.route("/api/progress/<dl_id>")
def progress(dl_id):
    def stream():
        idx = 0
        while True:
            dl = downloads.get(dl_id)
            if not dl:
                yield f"data: {json.dumps({'type': 'removed'})}\n\n"
                break

            while idx < len(dl["events"]):
                with _events_lock:
                    evt = dl["events"][idx]
                yield f"data: {json.dumps(evt)}\n\n"
                idx += 1

                if evt["type"] in ("complete", "error"):
                    return

            if dl.get("cancelled"):
                yield f"data: {json.dumps({'type': 'cancelled'})}\n\n"
                return

            time.sleep(0.3)

    return Response(stream(), mimetype="text/event-stream",
                    headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@app.route("/api/cancel", methods=["POST"])
def cancel_download():
    data = request.json
    dl_id = data.get("id", "")
    dl = downloads.get(dl_id)

    if dl and not dl.get("finished"):
        dl["cancelled"] = True
        dl["finished"] = True
        dl["finish_time"] = time.time()
        proc = dl.get("proc")
        if proc:
            try:
                proc.kill()
            except OSError:
                pass
        return jsonify({"status": "cancelled"})

    return jsonify({"status": "not_found"}), 404


@app.route("/api/open-folder", methods=["POST"])
def open_folder():
    data = request.json
    path = data.get("path", "").strip()

    if path:
        path = os.path.normpath(path)
        # Prevent path traversal: resolve to real path and check it's under home
        real = os.path.realpath(path)
        home = os.path.realpath(str(Path.home()))
        if not real.startswith(home):
            return jsonify({"error": "Access denied"}), 403
    else:
        path = get_save_path()

    if not os.path.isdir(path):
        return jsonify({"error": "Folder not found"}), 404

    try:
        if platform.system() == "Windows":
            os.startfile(path)
        elif platform.system() == "Darwin":
            subprocess.Popen(["open", path])
        else:
            subprocess.Popen(["xdg-open", path])
        return jsonify({"status": "ok"})
    except OSError:
        return jsonify({"error": "Could not open folder"}), 500


@app.route("/api/browse", methods=["POST"])
def browse_folder():
    data = request.json
    initial_dir = data.get("path", "").strip() or get_save_path()
    
    # Run tk dialog in a separate short-lived root
    root = tk.Tk()
    root.withdraw()
    root.attributes("-topmost", True)
    
    folder = filedialog.askdirectory(
        initialdir=initial_dir,
        title="Select Download Folder"
    )
    
    root.destroy()
    
    if folder:
        folder = os.path.normpath(folder)
        return jsonify({"path": folder})
    return jsonify({"path": ""})


@app.route("/api/history")
def get_history():
    return jsonify(load_history())


# ── Main ──
if __name__ == "__main__":
    print(f"\n  {APP_NAME} v{APP_VER}")
    print(f"  Running at http://localhost:{PORT}")
    print(f"  Max concurrent downloads: {MAX_CONCURRENT}\n")

    # Open browser after a short delay
    def open_browser():
        time.sleep(1.2)
        webbrowser.open(f"http://localhost:{PORT}")

    threading.Thread(target=open_browser, daemon=True).start()

    # Graceful shutdown: kill active yt-dlp processes
    def shutdown(signum=None, frame=None):
        print("\n  Shutting down...")
        for dl in downloads.values():
            proc = dl.get("proc")
            if proc and proc.poll() is None:
                try:
                    proc.kill()
                except OSError:
                    pass
        sys.exit(0)

    signal.signal(signal.SIGINT, shutdown)
    if hasattr(signal, "SIGTERM"):
        signal.signal(signal.SIGTERM, shutdown)

    app.run(host="127.0.0.1", port=PORT, debug=False, use_reloader=False)
