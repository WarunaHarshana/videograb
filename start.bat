@echo off
title VideoGrab
echo.
echo   ╔══════════════════════════════╗
echo   ║    VideoGrab - Universal     ║
echo   ║      Video Downloader        ║
echo   ╚══════════════════════════════╝
echo.
pip install -U yt-dlp -q 2>nul
python downloader.py
