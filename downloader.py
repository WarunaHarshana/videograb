"""
VideoGrab - Universal Video Downloader v4.0
Web-based UI with Flask backend + SSE progress streaming
"""

import os
import re
import sys
import json
import time
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
PORT = 8457
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
STATIC_DIR = os.path.join(BASE_DIR, "static")
CONFIG_FILE = os.path.join(BASE_DIR, "config.json")
HISTORY_FILE = os.path.join(BASE_DIR, "history.json")

app = Flask(__name__, static_folder=STATIC_DIR)

# ── State ──
downloads = {}
progress_listeners = {}


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


# ── Send SSE event ──
def send_event(dl_id, event_type, data):
    downloads[dl_id]["events"].append({
        "type": event_type,
        "data": data,
    })


# ── Download Worker ──
def download_worker(dl_id):
    dl = downloads[dl_id]
    url = dl["url"]
    save_path = dl["save_path"]
    final_title = url

    try:
        send_event(dl_id, "status", {"message": "Starting download..."})

        cmd = [
            sys.executable, "-m", "yt_dlp",
            "-f", "bestvideo+bestaudio/best",
            "--merge-output-format", "mp4",
            "-o", os.path.join(save_path, "%(title)s.%(ext)s"),
            "--newline", "--no-warnings", "--force-overwrites",
            "--encoding", "utf-8",
            "--no-check-certificates",
            "--force-ipv4",
            "--retries", "5",
            "--socket-timeout", "30",
            "--concurrent-fragments", "8",
            "--throttled-rate", "100K",
            "--buffer-size", "16K",
            "--http-chunk-size", "10M",
            "--referer", "/".join(url.split("/")[:3]) + "/",
            url,
        ]

        if dl.get("use_cookies"):
            cmd.extend(["--cookies-from-browser", "chrome"])

        kwargs = {}
        if platform.system() == "Windows":
            kwargs["creationflags"] = subprocess.CREATE_NO_WINDOW

        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
            **kwargs,
        )
        dl["proc"] = proc

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

        proc.wait()

        if not dl["cancelled"]:
            if proc.returncode == 0:
                send_event(dl_id, "complete", {"title": final_title})
                dl["finished"] = True
                save_history(url, final_title, "success")
            else:
                send_event(dl_id, "error", {"message": "Download failed — try enabling cookies"})
                dl["finished"] = True
                save_history(url, final_title, "failed")

    except Exception as e:
        send_event(dl_id, "error", {"message": str(e)[:80]})
        dl["finished"] = True
        save_history(url, final_title, "error")


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

    if not url:
        return jsonify({"error": "URL is required"}), 400
    if not re.match(r"https?://", url, re.IGNORECASE):
        return jsonify({"error": "URL must start with http:// or https://"}), 400

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
    else:
        path = get_save_path()
        
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
    print(f"  Running at http://localhost:{PORT}\n")

    # Open browser after a short delay
    def open_browser():
        time.sleep(1.2)
        webbrowser.open(f"http://localhost:{PORT}")

    threading.Thread(target=open_browser, daemon=True).start()

    app.run(host="127.0.0.1", port=PORT, debug=False, use_reloader=False)
