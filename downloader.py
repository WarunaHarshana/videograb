"""
VideoGrab - Universal Video Downloader v3.0
Supports: yt-dlp, Streamlink, Direct CDN, M3U8/HLS, Browser cookies
"""

import os
import re
import sys
import json
import time
import glob
import asyncio
import threading
import subprocess
import urllib.request
import urllib.parse
import tkinter as tk
from tkinter import ttk, messagebox, filedialog
from pathlib import Path

APP_NAME = "VideoGrab"
APP_VER = "3.0"
CONFIG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")

# ── Theme (Apple-inspired) ──
BG = "#ffffff"
CARD = "#f5f5f7"
CARD_HOVER = "#e8e8ed"
BORDER = "#d2d2d7"
TEXT = "#1d1d1f"
TEXT_DIM = "#6e6e73"
ACCENT = "#0071e3"
SUCCESS = "#30d158"
WARN = "#ff9f0a"
ERROR = "#ff3b30"
SUBTLE = "#86868b"


class VideoGrab:
    def __init__(self):
        self.window = None
        self.downloads = {}

    def _cfg(self):
        try:
            if os.path.exists(CONFIG_FILE):
                with open(CONFIG_FILE) as f: return json.load(f)
        except: pass
        return {}

    def _save_cfg(self, cfg):
        try:
            with open(CONFIG_FILE, 'w') as f: json.dump(cfg, f, indent=2)
        except: pass

    def _save_path(self):
        c = self._cfg()
        if c.get('savePath') and os.path.exists(c['savePath']): return c['savePath']
        d = str(Path.home() / "Downloads" / "Videos")
        os.makedirs(d, exist_ok=True)
        return d

    def _check_tools(self):
        tools = {}
        for name, cmd in [('yt-dlp', ['yt-dlp', '--version']), ('ffmpeg', ['ffmpeg', '-version'])]:
            try:
                r = subprocess.run(cmd, capture_output=True, text=True, timeout=5)
                tools[name] = r.returncode == 0
            except: tools[name] = False
        return tools

    def _detect_type(self, url):
        u = url.lower()
        # Video sites
        if any(x in u for x in ['youtube', 'youtu.be']): return '▶', 'YouTube'
        if 'vimeo' in u: return '◉', 'Vimeo'
        if 'facebook' in u or 'fb.watch' in u: return 'f', 'Facebook'
        if 'instagram' in u: return '◎', 'Instagram'
        if 'tiktok' in u: return '♪', 'TikTok'
        if 'twitter' in u or 'x.com' in u: return '✦', 'Twitter/X'
        if 'twitch' in u: return '◆', 'Twitch'
        if 'bilibili' in u: return '▶', 'Bilibili'
        if 'dailymotion' in u: return '◉', 'Dailymotion'
        # Adult sites
        if any(x in u for x in ['porn', 'xvideos', 'xhamster', 'xnxx']): return '🔞', 'Adult'
        # File hosts
        if any(x in u for x in ['keep2share', 'tezfiles', 'rapidgator']): return '📁', 'File Host'
        if 'mega.nz' in u: return '📁', 'MEGA'
        if 'mediafire' in u: return '📁', 'MediaFire'
        if 'gofile' in u: return '📁', 'GoFile'
        if 'drive.google' in u: return '📁', 'Google Drive'
        if 'dropbox' in u: return '📁', 'Dropbox'
        # Direct files
        if any(x in u for x in ['.mp4', '.mkv', '.avi', '.mov', '.zip', '.rar']): return '📎', 'Direct'
        return '◉', 'Video'

    def _needs_cookies(self, url):
        """Sites that typically need browser cookies"""
        u = url.lower()
        cookie_sites = ['fullporn', 'pornhub', 'xvideos', 'xhamster', 'xnxx',
                        'keep2share', 'tezfiles', 'rapidgator', 'uploaded.net']
        return any(s in u for s in cookie_sites)

    def _safe_ui(self, fn, **kw):
        try: self.window.after(0, lambda: fn(**kw))
        except: pass

    def _browse(self):
        d = filedialog.askdirectory()
        if d:
            self.path_entry.delete(0, tk.END)
            self.path_entry.insert(0, d)
            cfg = self._cfg(); cfg['savePath'] = d; self._save_cfg(cfg)

    def build_ui(self):
        self.window = tk.Tk()
        self.window.title(f"{APP_NAME} v{APP_VER}")
        self.window.geometry("780x620")
        self.window.minsize(650, 480)
        self.window.configure(bg=BG)

        main = tk.Frame(self.window, bg=BG, padx=32, pady=24)
        main.pack(fill="both", expand=True)

        # Header
        tk.Label(main, text=APP_NAME, font=("Segoe UI", 20, "bold"), bg=BG, fg=TEXT).pack(anchor="w")
        tk.Label(main, text="Download any video from any website", font=("Segoe UI", 12), bg=BG, fg=TEXT_DIM).pack(anchor="w", pady=(2, 20))

        # URL
        tk.Label(main, text="Video URL", font=("Segoe UI", 11, "bold"), bg=BG, fg=TEXT).pack(anchor="w", pady=(0, 6))

        url_frame = tk.Frame(main, bg=BG)
        url_frame.pack(fill="x", pady=(0, 12))

        self.url_entry = tk.Entry(url_frame, bg=CARD, fg=TEXT, insertbackground=TEXT, relief="flat",
                                  font=("Segoe UI", 12), highlightthickness=2, highlightbackground=BORDER,
                                  highlightcolor=ACCENT)
        self.url_entry.pack(side="left", fill="x", expand=True, ipady=12)
        self.url_entry.bind('<Return>', lambda e: self.start_download())

        self.url_icon = tk.Label(url_frame, text="◉", font=("Segoe UI", 16), bg=BG, fg=ACCENT, width=3)
        self.url_icon.pack(side="right", padx=(8, 0))

        # Save path
        tk.Label(main, text="Save to", font=("Segoe UI", 11, "bold"), bg=BG, fg=TEXT).pack(anchor="w", pady=(0, 6))

        path_frame = tk.Frame(main, bg=BG)
        path_frame.pack(fill="x", pady=(0, 16))

        self.path_entry = tk.Entry(path_frame, bg=CARD, fg=TEXT, relief="flat", font=("Segoe UI", 11),
                                   highlightthickness=2, highlightbackground=BORDER, highlightcolor=ACCENT)
        self.path_entry.pack(side="left", fill="x", expand=True, ipady=10)
        self.path_entry.insert(0, self._save_path())

        tk.Button(path_frame, text="Browse", command=self._browse, bg=CARD, fg=TEXT,
                  relief="flat", font=("Segoe UI", 10), padx=16, cursor="hand2", bd=0).pack(side="right", padx=(8,0), ipady=8)

        # Action row
        btn_row = tk.Frame(main, bg=BG)
        btn_row.pack(fill="x", pady=(0, 16))

        self.download_btn = tk.Button(btn_row, text="  Download", command=self.start_download,
                                       bg=ACCENT, fg="white", relief="flat",
                                       font=("Segoe UI", 12, "bold"), padx=28, cursor="hand2", bd=0)
        self.download_btn.pack(side="left", ipady=10)

        # Cookie checkbox
        self.use_cookies = tk.BooleanVar(value=False)
        tk.Checkbutton(btn_row, text="Use browser cookies (close browser first)",
                       variable=self.use_cookies, bg=BG, fg=TEXT_DIM, selectcolor=CARD,
                       activebackground=BG, font=("Segoe UI", 9)).pack(side="left", padx=(16,0))

        # Status
        self.status_label = tk.Label(main, text="", font=("Segoe UI", 10), bg=BG, fg=SUBTLE)
        self.status_label.pack(anchor="w", pady=(0, 12))

        tools = self._check_tools()
        status_parts = [f"{'✓' if tools.get('yt-dlp') else '✗'} yt-dlp", f"{'✓' if tools.get('ffmpeg') else '✗'} ffmpeg"]
        self.status_label.config(text="  ·  ".join(status_parts))

        # Downloads
        tk.Label(main, text="Downloads", font=("Segoe UI", 11, "bold"), bg=BG, fg=TEXT).pack(anchor="w", pady=(0, 8))

        self.downloads_frame = tk.Frame(main, bg=BG)
        self.downloads_frame.pack(fill="both", expand=True)

        self.empty_label = tk.Label(self.downloads_frame, text="Paste a video URL to get started",
                                     font=("Segoe UI", 12), bg=BG, fg=SUBTLE)
        self.empty_label.pack(pady=40)

    def start_download(self):
        url = self.url_entry.get().strip()
        save_path = self.path_entry.get().strip()

        if not url:
            messagebox.showerror("Error", "Please enter a URL")
            return
        if not save_path:
            messagebox.showerror("Error", "Please select a save folder")
            return

        os.makedirs(save_path, exist_ok=True)
        self.empty_label.pack_forget()

        dl_id = str(int(time.time() * 1000))
        icon, site = self._detect_type(url)
        needs_cookies = self._needs_cookies(url) and self.use_cookies.get()

        # Download card
        card = tk.Frame(self.downloads_frame, bg=CARD, highlightthickness=1, highlightbackground=BORDER)
        card.pack(fill="x", pady=4, ipady=2)

        top = tk.Frame(card, bg=CARD)
        top.pack(fill="x", padx=12, pady=(8, 4))

        tk.Label(top, text=f"{icon}  {site}", font=("Segoe UI", 10), bg=CARD, fg=TEXT_DIM).pack(side="left")
        title_lbl = tk.Label(top, text=f" {url[:50]}{'...' if len(url)>50 else ''}",
                             font=("Segoe UI", 10), bg=CARD, fg=TEXT)
        title_lbl.pack(side="left", fill="x", expand=True)
        cancel_btn = tk.Button(top, text="✕", bg=CARD, fg=SUBTLE, relief="flat",
                               font=("Segoe UI", 11), cursor="hand2", bd=0)
        cancel_btn.pack(side="right")

        prog = ttk.Progressbar(card, mode='determinate', style="Green.Horizontal.TProgressbar")
        prog.pack(fill="x", padx=12, pady=4)

        stats = tk.Frame(card, bg=CARD)
        stats.pack(fill="x", padx=12, pady=(4, 8))
        speed_lbl = tk.Label(stats, text="Starting...", font=("Segoe UI", 9), bg=CARD, fg=SUBTLE)
        speed_lbl.pack(side="left")
        size_lbl = tk.Label(stats, text="", font=("Segoe UI", 9), bg=CARD, fg=SUBTLE)
        size_lbl.pack(side="left", padx=16)
        pct_lbl = tk.Label(stats, text="0%", font=("Segoe UI", 11, "bold"), bg=CARD, fg=ACCENT)
        pct_lbl.pack(side="right")

        self.downloads[dl_id] = {
            'url': url, 'save_path': save_path, 'site': site,
            'progress': prog, 'speed_lbl': speed_lbl, 'size_lbl': size_lbl,
            'pct_lbl': pct_lbl, 'title_lbl': title_lbl,
            'cancelled': False, 'use_cookies': needs_cookies
        }

        t = threading.Thread(target=self._worker, args=(dl_id,), daemon=True)
        self.downloads[dl_id]['thread'] = t
        cancel_btn.config(command=lambda: self._cancel(dl_id))
        t.start()

    def _worker(self, dl_id):
        dl = self.downloads[dl_id]
        url = dl['url']
        save_path = dl['save_path']

        try:
            # Build yt-dlp command
            cmd = [
                sys.executable, '-m', 'yt_dlp',
                '-f', 'bestvideo+bestaudio/best',
                '--merge-output-format', 'mp4',
                '-o', os.path.join(save_path, '%(title)s.%(ext)s'),
                '--newline', '--no-warnings', '--no-part', '--force-overwrites',
                '--referer', '/'.join(url.split('/')[:3]) + '/',
                url
            ]

            # Add browser cookies if requested
            if dl.get('use_cookies'):
                cmd.extend(['--cookies-from-browser', 'chrome'])

            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, encoding='utf-8', errors='replace', bufsize=1)

            for line in proc.stdout:
                if dl['cancelled']:
                    proc.kill()
                    break

                line = line.strip()
                if not line:
                    continue

                # Skip noise
                if any(x in line.lower() for x in ['generic] falling', 'generic] extract']):
                    continue

                if '[download]' in line and '%' in line:
                    import re
                    m = re.search(r'(\d+\.?\d*)%', line)
                    if m:
                        pct = float(m.group(1))
                        self._safe_ui(dl['pct_lbl'].config, text=f"{pct:.1f}%")
                        self._safe_ui(dl['progress'].config, value=pct)
                    s = re.search(r'at\s+([^\s]+)', line)
                    if s: self._safe_ui(dl['speed_lbl'].config, text=s.group(1))
                    z = re.search(r'of\s+([^\s]+)', line)
                    if z: self._safe_ui(dl['size_lbl'].config, text=f"/ {z.group(1)}")
                elif '[download]' in line and 'Destination' in line:
                    dl['dest'] = line.split('Destination:')[-1].strip()
                elif 'Merger' in line or 'Merging' in line:
                    self._safe_ui(dl['speed_lbl'].config, text="Merging...")
                elif 'already been downloaded' in line.lower():
                    self._safe_ui(dl['speed_lbl'].config, text="Already exists", foreground=WARN)

            proc.wait()

            if not dl['cancelled']:
                if proc.returncode == 0:
                    self._safe_ui(dl['speed_lbl'].config, text="Done ✓", foreground=SUCCESS)
                    self._safe_ui(dl['pct_lbl'].config, text="100%")
                    self._safe_ui(dl['progress'].config, value=100)
                else:
                    self._safe_ui(dl['speed_lbl'].config, text="Failed - try enabling cookies", foreground=ERROR)

        except Exception as e:
            self._safe_ui(dl['speed_lbl'].config, text=f"Error: {str(e)[:30]}", foreground=ERROR)

    def _cancel(self, dl_id):
        dl = self.downloads.get(dl_id)
        if dl:
            dl['cancelled'] = True
            dl['speed_lbl'].config(text="Cancelled", foreground=WARN)

    def run(self):
        self.build_ui()
        self.window.mainloop()


if __name__ == "__main__":
    VideoGrab().run()
