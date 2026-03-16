"""Basic smoke tests for VideoGrab."""
import json
import os
import sys
import tempfile
import pytest

# Add project root to path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import downloader


@pytest.fixture
def client(tmp_path, monkeypatch):
    """Create a Flask test client with temp directories."""
    monkeypatch.setattr(downloader, "CONFIG_FILE", str(tmp_path / "config.json"))
    monkeypatch.setattr(downloader, "HISTORY_FILE", str(tmp_path / "history.json"))
    downloader.app.config["TESTING"] = True
    with downloader.app.test_client() as c:
        yield c


# ── detect_type() ──

def test_detect_youtube():
    icon, site = downloader.detect_type("https://www.youtube.com/watch?v=abc")
    assert site == "YouTube"

def test_detect_youtu_be():
    icon, site = downloader.detect_type("https://youtu.be/abc")
    assert site == "YouTube"

def test_detect_tiktok():
    icon, site = downloader.detect_type("https://www.tiktok.com/@user/video/123")
    assert site == "TikTok"

def test_detect_vimeo():
    icon, site = downloader.detect_type("https://vimeo.com/123456")
    assert site == "Vimeo"

def test_detect_direct():
    icon, site = downloader.detect_type("https://example.com/video.mp4")
    assert site == "Direct"

def test_detect_unknown():
    icon, site = downloader.detect_type("https://example.com/page")
    assert site == "Video"


# ── Config ──

def test_load_config_missing(tmp_path, monkeypatch):
    monkeypatch.setattr(downloader, "CONFIG_FILE", str(tmp_path / "missing.json"))
    cfg = downloader.load_config()
    assert cfg == {}

def test_save_and_load_config(tmp_path, monkeypatch):
    monkeypatch.setattr(downloader, "CONFIG_FILE", str(tmp_path / "config.json"))
    downloader.save_config({"savePath": "/test/path"})
    cfg = downloader.load_config()
    assert cfg["savePath"] == "/test/path"


# ── History ──

def test_load_history_missing(tmp_path, monkeypatch):
    monkeypatch.setattr(downloader, "HISTORY_FILE", str(tmp_path / "missing.json"))
    history = downloader.load_history()
    assert history == []

def test_save_and_load_history(tmp_path, monkeypatch):
    monkeypatch.setattr(downloader, "HISTORY_FILE", str(tmp_path / "history.json"))
    downloader.save_history("https://example.com", "Test Video", "success")
    history = downloader.load_history()
    assert len(history) == 1
    assert history[0]["url"] == "https://example.com"
    assert history[0]["title"] == "Test Video"
    assert history[0]["status"] == "success"


# ── API Routes ──

def test_index(client):
    resp = client.get("/")
    assert resp.status_code == 200
    assert b"VideoGrab" in resp.data

def test_info(client):
    resp = client.get("/api/info")
    assert resp.status_code == 200
    data = resp.get_json()
    assert data["name"] == "VideoGrab"
    assert "version" in data
    assert "savePath" in data
    assert "tools" in data
    assert "formats" in data
    assert "browsers" in data

def test_download_missing_url(client):
    resp = client.post("/api/download", json={})
    assert resp.status_code == 400
    assert "URL is required" in resp.get_json()["error"]

def test_download_invalid_url(client):
    resp = client.post("/api/download", json={"url": "not-a-url"})
    assert resp.status_code == 400
    assert "http://" in resp.get_json()["error"]

def test_history_empty(client):
    resp = client.get("/api/history")
    assert resp.status_code == 200
    assert resp.get_json() == []

def test_cancel_not_found(client):
    resp = client.post("/api/cancel", json={"id": "nonexistent"})
    assert resp.status_code == 404


# ── FORMATS ──

def test_formats_defined():
    assert "best" in downloader.FORMATS
    assert "1080" in downloader.FORMATS
    assert "720" in downloader.FORMATS
    assert "480" in downloader.FORMATS
    assert "audio" in downloader.FORMATS
