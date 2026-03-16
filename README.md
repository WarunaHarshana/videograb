# VideoGrab

A single-file Python/Flask web application for downloading videos from any website. Uses [yt-dlp](https://github.com/yt-dlp/yt-dlp) as the download engine with a modern browser-based UI and real-time progress via Server-Sent Events.

## Features

- **Universal support** — YouTube, TikTok, Vimeo, Twitch, Bilibili, and 1000+ more sites
- **Quality selection** — Best, 1080p, 720p, 480p, or audio-only (MP3)
- **Batch downloads** — Paste multiple URLs and download them all at once
- **Playlist support** — Download full playlists with a single toggle
- **Subtitle download** — Download and embed subtitles in 10+ languages
- **Output formats** — MP4, MKV, or WebM
- **Speed limiting** — Throttle downloads to 500K/1M/2M/5M
- **Proxy support** — Route downloads through a proxy
- **Browser cookies** — Use cookies from Chrome, Firefox, Edge, and more
- **Download history** — Search, filter, and export your download history
- **Dark/Light theme** — Toggle between themes, saved to localStorage
- **Desktop notifications** — Get notified when downloads complete
- **Drag & drop** — Drag URLs directly onto the page
- **Keyboard shortcuts** — Ctrl+Enter to download, Esc to close panels
- **Graceful shutdown** — Active downloads are cleaned up on exit

## Requirements

- Python 3.8+
- [yt-dlp](https://github.com/yt-dlp/yt-dlp) (installed automatically via pip)
- [ffmpeg](https://ffmpeg.org/) (required for merging video/audio and format conversion)

## Installation

```bash
# Clone the repository
git clone https://github.com/youruser/videograb.git
cd videograb

# Install dependencies
pip install -r requirements.txt

# Make sure ffmpeg is installed and on PATH
# Windows: download from https://ffmpeg.org/download.html
# macOS: brew install ffmpeg
# Linux: sudo apt install ffmpeg
```

## Usage

```bash
# Run with defaults (port 8457, max 3 concurrent downloads)
python downloader.py

# Custom port and concurrency
python downloader.py --port 9000 --max-concurrent 5
```

The app will open in your browser at `http://localhost:8457`.

### Windows

Double-click `start.bat` — it auto-installs dependencies and launches the app.

## CLI Arguments

| Argument | Default | Description |
|----------|---------|-------------|
| `--port` | 8457 | Port to run the web server on |
| `--max-concurrent` | 3 | Maximum simultaneous downloads |

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Ctrl+Enter` | Start download |
| `Ctrl+Shift+H` | Toggle history panel |
| `Escape` | Close open panels |
| `Ctrl+V` (anywhere) | Paste URL into input |

## Project Structure

```
videograb/
├── downloader.py          # Flask backend + yt-dlp wrapper (single file)
├── static/
│   ├── index.html         # Web UI
│   ├── app.js             # Frontend logic (SSE, toasts, theme)
│   └── style.css          # Dark/Light theme styling
├── requirements.txt       # Python dependencies
├── start.bat              # Windows launcher
├── CLAUDE.md              # Development notes
└── README.md              # This file
```

## API Endpoints

| Route | Method | Purpose |
|-------|--------|---------|
| `/` | GET | Web UI |
| `/api/info` | GET | App version, save path, tool availability |
| `/api/download` | POST | Start download(s) |
| `/api/progress/<id>` | GET | SSE stream of download events |
| `/api/cancel` | POST | Cancel a running download |
| `/api/browse` | POST | Open native folder picker |
| `/api/open-folder` | POST | Open folder in OS file explorer |
| `/api/history` | GET | Download history (last 100) |

## License

MIT
