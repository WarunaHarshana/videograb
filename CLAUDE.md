# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

VideoGrab v4.0 — a single-file Python/Flask web application for downloading videos from various platforms. It uses `yt-dlp` as the download engine and provides a browser-based UI with real-time progress via Server-Sent Events (SSE).

## Running the Application

```bash
# Install dependencies
pip install -U yt-dlp flask

# Run directly (opens browser at http://localhost:8457)
python downloader.py

# Custom port and concurrency
python downloader.py --port 9000 --max-concurrent 5

# Windows batch launcher (also auto-installs deps)
start.bat
```

The app requires `yt-dlp` and `ffmpeg` available on PATH. The UI at `/api/info` checks for both.

CLI arguments: `--port` (default 8457), `--max-concurrent` (default 3).

## Architecture

The entire backend is in a single file: [`downloader.py`](downloader.py).

**Key components:**

- **Flask server** (port 8457) — serves the web UI and REST API
- **`download_worker()`** — runs in a background thread per download, spawns `yt-dlp` via `python -m yt_dlp`, parses stdout for progress percentages/speed/ETA, and pushes typed events (`progress`, `status`, `title`, `playlist`, `complete`, `error`) into an in-memory event list via `send_event()`
- **SSE streaming** — `/api/progress/<dl_id>` yields events from the download's event list using a `text/event-stream` response; each event is JSON with `type` and `data` fields; the stream ends on `complete`/`error`/`cancelled`
- **URL detection** — `detect_type()` pattern-matches URLs to identify the platform (YouTube, TikTok, etc.) and returns an icon/label tuple for UI display
- **Config/History** — persisted as JSON files (`config.json`, `history.json`) in the project root

**API routes:**

| Route | Method | Purpose |
|---|---|---|
| `/` | GET | Serves the HTML UI |
| `/api/info` | GET | App version, save path, tool availability |
| `/api/download` | POST | Starts a download (body: `url`, `savePath?`, `useCookies?`) |
| `/api/progress/<id>` | GET | SSE stream of download events |
| `/api/cancel` | POST | Kills a running download |
| `/api/browse` | POST | Opens native folder picker (tkinter) |
| `/api/open-folder` | POST | Opens folder in OS file explorer |
| `/api/history` | GET | Returns last 100 downloads |

**Frontend** lives in [`static/`](static/):
- `index.html` — single-page UI
- `app.js` — SSE client, download management, history display
- `style.css` — all styling

## Key Implementation Details

**Download formats** are defined in the `FORMATS` dict: `best`, `1080`, `720`, `480` (video at capped resolution), and `audio` (MP3 extraction via `-x --audio-format mp3`).

**Concurrency model:** Each download runs in a daemon thread (`download_worker`). The `_events_lock` (threading.Lock) guards access to the per-download events list. A configurable `MAX_CONCURRENT` limit (default 3, via `--max-concurrent`) is enforced in the `/api/download` route.

**Cleanup:** Finished downloads are removed from memory after 5 minutes. A hard cap of `MAX_DOWNLOADS` (100 entries) evicts oldest-finished entries. The `cleanup_old_downloads()` function runs at the end of each download worker.

**Progress parsing:** `download_worker` spawns `yt-dlp` via `sys.executable -m yt_dlp` (not the `yt-dlp` binary directly), reads stdout line-by-line with regex extraction of percent/speed/size/ETA, and pushes typed events (`progress`, `status`, `title`, `complete`, `error`) into the download's event list.

**Platform detection:** `detect_type()` pattern-matches the URL against known domains (YouTube, TikTok, Vimeo, Twitch, Bilibili, etc.) and returns an icon/label tuple. This is cosmetic only — yt-dlp handles actual URL support.

**Persistence:** Config (`config.json`) stores the last-used save path. History (`history.json`) stores the last 100 downloads with URL, title, timestamp, and status (success/failed/error).

**Graceful shutdown:** SIGINT/SIGTERM handlers kill all active yt-dlp subprocesses before exiting.

## State Management

Downloads are tracked in-memory in a `downloads` dict keyed by millisecond timestamp. Each entry holds the subprocess, event list, and cancellation flag. When the server restarts, all active downloads are lost.
