import os
import sys
import re
import json
import time
import threading
import subprocess
import urllib.request
import urllib.parse
import tkinter as tk
from tkinter import ttk, messagebox, filedialog
from pathlib import Path

APP_NAME = "VideoGrab"
CONFIG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")

# ── Theme ──
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


class VideoDownloader:
    def __init__(self):
        self.window = None
        self.downloads = {}

    def _get_config(self):
        try:
            if os.path.exists(CONFIG_FILE):
                with open(CONFIG_FILE, 'r') as f:
                    return json.load(f)
        except: pass
        return {}

    def _save_config(self, cfg):
        try:
            with open(CONFIG_FILE, 'w') as f:
                json.dump(cfg, f, indent=2)
        except: pass

    def _save_path(self):
        cfg = self._get_config()
        if cfg.get('savePath') and os.path.exists(cfg['savePath']):
            return cfg['savePath']
        d = str(Path.home() / "Downloads" / "Videos")
        os.makedirs(d, exist_ok=True)
        return d

    def _check_ytdlp(self):
        try:
            r = subprocess.run(['yt-dlp', '--version'], capture_output=True, text=True, timeout=5)
            return r.returncode == 0, r.stdout.strip() if r.returncode == 0 else None
        except: return False, None

    def _detect_site(self, url):
        u = url.lower()
        if any(x in u for x in ['youtube', 'youtu.be']): return '▶', 'YouTube'
        if 'vimeo' in u: return '◉', 'Vimeo'
        if 'facebook' in u or 'fb.watch' in u: return 'f', 'Facebook'
        if 'instagram' in u: return '◎', 'Instagram'
        if 'tiktok' in u: return '♪', 'TikTok'
        if 'twitter' in u or 'x.com' in u: return '✦', 'Twitter'
        if 'twitch' in u: return '◆', 'Twitch'
        if 'keep2share' in u: return '📁', 'Keep2Share'
        if 'tezfiles' in u: return '📁', 'TezFiles'
        if 'rapidgator' in u: return '📁', 'Rapidgator'
        if 'uploaded' in u or 'ul.to' in u: return '📁', 'Uploaded'
        if 'mega.nz' in u: return '📁', 'MEGA'
        if 'gofile' in u: return '📁', 'GoFile'
        if 'mediafire' in u: return '📁', 'MediaFire'
        if 'drive.google' in u: return '📁', 'Google Drive'
        if 'dropbox' in u: return '📁', 'Dropbox'
        if any(x in u for x in ['.mp4', '.mkv', '.avi', '.mov', '.zip', '.rar', '.pdf']): return '📎', 'Direct'
        return '◉', 'Video'

    def _is_direct_download(self, url):
        """Check if URL should be downloaded directly (not via yt-dlp)"""
        u = url.lower()
        direct_sites = ['keep2share', 'tezfiles', 'rapidgator', 'uploaded.net', 'ul.to',
                        'gofile.io', 'mediafire.com', 'drive.google.com', 'dropbox.com',
                        'zippyshare', 'workupload', 'terabox', 'disk.yandex']
        return any(s in u for s in direct_sites)

    def _safe_ui(self, fn, **kw):
        try: self.window.after(0, lambda: fn(**kw))
        except: pass

    def _browse(self):
        d = filedialog.askdirectory()
        if d:
            self.path_entry.delete(0, tk.END)
            self.path_entry.insert(0, d)
            cfg = self._get_config()
            cfg['savePath'] = d
            self._save_config(cfg)

    def build_ui(self):
        self.window = tk.Tk()
        self.window.title(APP_NAME)
        self.window.geometry("750x600")
        self.window.minsize(600, 450)
        self.window.configure(bg=BG)

        main = tk.Frame(self.window, bg=BG, padx=32, pady=24)
        main.pack(fill="both", expand=True)

        # Header
        tk.Label(main, text=APP_NAME, font=("Segoe UI", 20, "bold"), bg=BG, fg=TEXT).pack(anchor="w")
        tk.Label(main, text="Download videos & files from any website", font=("Segoe UI", 12), bg=BG, fg=TEXT_DIM).pack(anchor="w", pady=(2, 20))

        # URL
        tk.Label(main, text="URL", font=("Segoe UI", 11, "bold"), bg=BG, fg=TEXT).pack(anchor="w", pady=(0, 6))

        url_frame = tk.Frame(main, bg=BG)
        url_frame.pack(fill="x", pady=(0, 14))

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
        path_frame.pack(fill="x", pady=(0, 20))

        self.path_entry = tk.Entry(path_frame, bg=CARD, fg=TEXT, relief="flat", font=("Segoe UI", 11),
                                   highlightthickness=2, highlightbackground=BORDER, highlightcolor=ACCENT)
        self.path_entry.pack(side="left", fill="x", expand=True, ipady=10)
        self.path_entry.insert(0, self._save_path())

        tk.Button(path_frame, text="Browse", command=self._browse, bg=CARD, fg=TEXT,
                  relief="flat", font=("Segoe UI", 10), padx=16, cursor="hand2", bd=0).pack(side="right", padx=(8,0), ipady=8)

        # Download button
        btn_row = tk.Frame(main, bg=BG)
        btn_row.pack(fill="x", pady=(0, 20))

        self.download_btn = tk.Button(btn_row, text="  Download", command=self.start_download,
                                       bg=ACCENT, fg="white", relief="flat",
                                       font=("Segoe UI", 12, "bold"), padx=28, cursor="hand2", bd=0)
        self.download_btn.pack(side="left", ipady=10)

        # Status
        self.status_label = tk.Label(main, text="", font=("Segoe UI", 10), bg=BG, fg=SUBTLE)
        self.status_label.pack(anchor="w", pady=(0, 12))

        ok, ver = self._check_ytdlp()
        if ok:
            self.status_label.config(text=f"✓ yt-dlp {ver}")
        else:
            self.status_label.config(text="Installing yt-dlp...", fg=WARN)
            self.window.after(500, self._install_ytdlp)

        # Downloads
        tk.Label(main, text="Downloads", font=("Segoe UI", 11, "bold"), bg=BG, fg=TEXT).pack(anchor="w", pady=(0, 8))

        self.downloads_frame = tk.Frame(main, bg=BG)
        self.downloads_frame.pack(fill="both", expand=True)

        self.empty_label = tk.Label(self.downloads_frame, text="Paste a URL to get started",
                                     font=("Segoe UI", 12), bg=BG, fg=SUBTLE)
        self.empty_label.pack(pady=40)

    def _install_ytdlp(self):
        try:
            subprocess.run([sys.executable, '-m', 'pip', 'install', '-U', 'yt-dlp'],
                          capture_output=True, timeout=60)
            ok, ver = self._check_ytdlp()
            if ok:
                self.status_label.config(text=f"✓ yt-dlp {ver}", fg=SUCCESS)
        except: pass

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
        icon, site = self._detect_site(url)

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
            'frame': card, 'url': url, 'save_path': save_path, 'site': site,
            'progress': prog, 'speed_lbl': speed_lbl, 'size_lbl': size_lbl,
            'pct_lbl': pct_lbl, 'title_lbl': title_lbl, 'cancel_btn': cancel_btn,
            'thread': None, 'cancelled': False
        }

        t = threading.Thread(target=self._dl_worker, args=(dl_id,), daemon=True)
        self.downloads[dl_id]['thread'] = t
        cancel_btn.config(command=lambda: self._cancel(dl_id))
        t.start()

    def _dl_worker(self, dl_id):
        dl = self.downloads[dl_id]
        try:
            cmd = [
                sys.executable, '-m', 'yt_dlp',
                '-f', 'bestvideo+bestaudio/best',
                '--merge-output-format', 'mp4',
                '-o', os.path.join(dl['save_path'], '%(title)s.%(ext)s'),
                '--newline', '--no-warnings',
                dl['url']
            ]
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, universal_newlines=True, encoding='utf-8', errors='replace')
            for line in proc.stdout:
                if dl['cancelled']:
                    proc.kill()
                    break
                line = line.strip()
                if not line: continue
                if '[download]' in line and '%' in line:
                    m = re.search(r'(\d+\.?\d*)%', line)
                    if m:
                        pct = float(m.group(1))
                        self._safe_ui(dl['pct_lbl'].config, text=f"{pct:.1f}%")
                        self._safe_ui(dl['progress'].config, value=pct)
                    s = re.search(r'at\s+([^\s]+)', line)
                    if s: self._safe_ui(dl['speed_lbl'].config, text=s.group(1))
                    z = re.search(r'of\s+([^\s]+)', line)
                    if z: self._safe_ui(dl['size_lbl'].config, text=f"/ {z.group(1)}")
                    e = re.search(r'ETA\s+([^\s]+)', line)
                    if e: self._safe_ui(dl['title_lbl'].config, text=f"{dl['site']}: ETA {e.group(1)}")
            proc.wait()
            if not dl['cancelled']:
                if proc.returncode == 0:
                    self._safe_ui(dl['speed_lbl'].config, text="Done ✓", foreground=SUCCESS)
                    self._safe_ui(dl['pct_lbl'].config, text="100%")
                    self._safe_ui(dl['progress'].config, value=100)
                else:
                    self._safe_ui(dl['speed_lbl'].config, text="Failed", foreground=ERROR)
        except Exception as e:
            self._safe_ui(dl['speed_lbl'].config, text=str(e)[:40], foreground=ERROR)

    def _cancel(self, dl_id):
        dl = self.downloads.get(dl_id)
        if dl:
            dl['cancelled'] = True
            dl['speed_lbl'].config(text="Cancelled", fg=WARN)

    def run(self):
        self.build_ui()
        self.window.mainloop()


if __name__ == "__main__":
    VideoDownloader().run()
