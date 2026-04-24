# VideoGrab

VideoGrab is a Java Swing desktop application for downloading videos with yt-dlp.

## Features

- Universal site support through yt-dlp
- Queue-based downloads with configurable max concurrency
- Format/quality options, playlist toggle, subtitles, proxy, speed limit, cookies
- Download cards with progress, speed, size, ETA, cancel and retry
- Download history with search, clear, export, and live refresh
- Light/dark theme switching (persisted)
- Drag and drop URL input, global paste, and keyboard shortcuts
- Startup/runtime diagnostics for python, yt-dlp, and ffmpeg
- System tray support and graceful shutdown cleanup

## Requirements

- Java 21+ to run the JAR, or a packaged app from `scripts/build-exe.ps1`
- JDK 21+ to build the Windows app image
- Maven 3.8+ if you want to use the Maven build
- `yt-dlp` and `ffmpeg`, either on PATH or bundled in `tools/`
- Python is optional when `yt-dlp.exe` is bundled or available on PATH

You can also bundle tools locally (no global PATH required):

- `tools/yt-dlp.exe` (or `tools/yt-dlp` on Linux/macOS)
- `tools/ffmpeg/bin/ffmpeg.exe` (or `tools/ffmpeg/bin/ffmpeg` on Linux/macOS)

## Build

```bash
mvn -q -DskipTests compile
```

## Build Windows EXE

On Windows with JDK 21+ installed:

```powershell
.\scripts\build-exe.ps1
```

The runnable app is created at:

```text
dist\VideoGrab\VideoGrab.exe
```

The build script bundles `tools\yt-dlp.exe` and `tools\ffmpeg\bin\ffmpeg.exe` into the app image when those files exist.

## Run

```bash
mvn -q exec:java -Dexec.mainClass=com.videograb.Main
```

Or package and run the JAR:

```bash
mvn -q -DskipTests package
java -jar target/videograb-1.0.0.jar
```

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| Ctrl+Enter | Start download |
| Ctrl+Shift+H | Open History tab |
| Escape | Focus URL field |
| Ctrl+V | Paste URL into input |

## Project Structure

```text
videograb/
├── src/main/java/com/videograb/
│   ├── Main.java
│   ├── model/
│   ├── service/
│   ├── ui/
│   └── util/
├── pom.xml
└── README.md
```

## Notes

- Runtime user state is stored in `%APPDATA%\VideoGrab\config.json` and `%APPDATA%\VideoGrab\history.json`.
- To keep state beside the executable for portable use, run with `-Dvideograb.portable=true`.
- On very new JDKs (e.g., 24+), VideoGrab disables FlatLaf optional native helpers by default for compatibility.
- If you want FlatLaf native integration enabled, run with: `--enable-native-access=ALL-UNNAMED`.

## License

MIT
