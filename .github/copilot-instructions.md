# Project Guidelines

## Scope
- This repository is Java Swing only.
- Application source is under src/main/java.
- Preserve behavior and compatibility with existing config/history JSON.

## Build And Run
- Build: mvn -q -DskipTests compile
- Package: mvn -q -DskipTests package
- Run from source: mvn -q exec:java -Dexec.mainClass=com.videograb.Main
- Run packaged JAR: java -jar target/videograb-1.0.0.jar

## Architecture
- Persistent local state is in config.json and history.json.
- Java code is in src/main/java/com/videograb with ui, service, model, and util packages.

## Conventions
- Preserve existing JSON structure in config.json and history.json for compatibility.
- Keep download execution centered on yt-dlp subprocess invocation, and preserve progress parsing behavior.
- Keep long-running operations off the EDT and publish UI updates through event/listener patterns.
- Make minimal, targeted edits and avoid broad refactors unless requested.

## Pitfalls
- External tools yt-dlp and ffmpeg must be available on PATH for full functionality.
- Maven and JDK must both be available for build validation.

## Reference Docs
- See README.md for user-facing setup and feature overview.
