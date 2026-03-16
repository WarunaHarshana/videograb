@echo off
title VideoGrab
echo.
echo   ╔══════════════════════════════╗
echo   ║    VideoGrab - Universal     ║
echo   ║      Video Downloader        ║
echo   ╚══════════════════════════════╝
echo.

:: Check Python
where python >nul 2>&1
if errorlevel 1 (
    echo   [ERROR] Python is not installed or not in PATH.
    echo   Please install Python from https://python.org
    pause
    exit /b 1
)

:: Ensure yt-dlp and flask are up-to-date
echo   Checking dependencies...
pip install -U yt-dlp flask -q 2>nul

echo   Starting VideoGrab...
echo.
python downloader.py
if errorlevel 1 (
    echo.
    echo   [ERROR] VideoGrab exited with an error.
    pause
)
