package com.videograb.service;

import com.videograb.model.Download;
import com.videograb.model.DownloadEvent;
import com.videograb.util.Constants;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.File;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for managing downloads and executing yt-dlp processes
 */
public class DownloadService {
    private final ExecutorService executorService;
    private final Semaphore concurrencySemaphore;
    private final HistoryService historyService;
    private final ConfigService configService;
    private final BlockingQueue<DownloadEvent> eventQueue;
    private final ScheduledExecutorService cleanupExecutor;
    private final Map<String, Process> activeProcesses;
    private final Map<String, Download> trackedDownloads;
    private final Set<String> pausedDownloads;
    private final String windowsShell;
    private final List<String> ytDlpBaseCommand;
    private final String ffmpegCommand;
    private final String aria2Command;
    private volatile boolean isShutdown = false;
    private static final int MAX_TRACKED_DOWNLOADS = 100;
    private static final int FINISHED_RETENTION_SECONDS = 300;
    private static final int MAX_FAILURE_LINES = 12;

    private static final Pattern PERCENT_PATTERN = Pattern.compile("(\\d+\\.?\\d*)%");
    private static final Pattern SPEED_PATTERN = Pattern.compile("at\\s+([^\\s]+)");
    private static final Pattern SIZE_PATTERN = Pattern.compile("of\\s+([^\\s]+)");
    private static final Pattern ETA_PATTERN = Pattern.compile("ETA\\s+([^\\s]+)");
    private static final List<String> AUDIO_FORMATS = Arrays.asList("audio", "Audio Only");

    public DownloadService(int maxConcurrentDownloads) {
        this(maxConcurrentDownloads, null, null);
    }

    public DownloadService(int maxConcurrentDownloads, HistoryService historyService) {
        this(maxConcurrentDownloads, historyService, null);
    }

    private DownloadService(int maxConcurrentDownloads, HistoryService historyService, ConfigService configService) {
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        this.concurrencySemaphore = new Semaphore(maxConcurrentDownloads);
        this.historyService = historyService != null ? historyService : new HistoryService();
        this.configService = configService != null ? configService : new ConfigService();
        this.eventQueue = new LinkedBlockingQueue<>();
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "download-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.activeProcesses = new ConcurrentHashMap<>();
        this.trackedDownloads = new ConcurrentHashMap<>();
        this.pausedDownloads = ConcurrentHashMap.newKeySet();
        this.windowsShell = detectWindowsShell();
        this.ytDlpBaseCommand = resolveYtDlpBaseCommand();
        this.ffmpegCommand = resolveFfmpegCommand();
        this.aria2Command = resolveAria2Command();
    }

    /**
     * Add a download to the queue
     */
    public boolean addDownload(Download download) {
        if (isShutdown) {
            return false;
        }

        if (download == null || download.getUrl() == null || download.getUrl().isBlank()) {
            return false;
        }

        if (download.getSavePath() == null || download.getSavePath().isBlank()) {
            download.setSavePath(configService.getString(
                Constants.CONFIG_DOWNLOAD_PATH,
                System.getProperty("user.home") + "/Downloads"
            ));
        }

        if (isDuplicateActiveDownload(download)) {
            return false;
        }

        // Set initial status
        download.setStatus(Constants.STATUS_QUEUED);
        download.setAddedAt(Instant.now());
        trackedDownloads.put(download.getId(), download);
        pruneFinishedTrackedDownloads();
        historyService.addDownload(download);
        
        // Submit to executor service
        executorService.submit(() -> processDownload(download));
        return true;
    }

    private boolean isDuplicateActiveDownload(Download candidate) {
        if (candidate == null || candidate.getUrl() == null || candidate.getUrl().isBlank()) {
            return false;
        }

        String candidateUrl = candidate.getUrl().trim();
        String candidateFormat = safeValue(candidate.getFormat());
        String candidatePath = safeValue(candidate.getSavePath());
        String candidateOutput = safeValue(candidate.getOutputFormat());

        for (Download existing : trackedDownloads.values()) {
            if (existing == null || existing.isFinished()) {
                continue;
            }

            if (candidateUrl.equalsIgnoreCase(safeValue(existing.getUrl()))
                && candidateFormat.equalsIgnoreCase(safeValue(existing.getFormat()))
                && candidatePath.equalsIgnoreCase(safeValue(existing.getSavePath()))
                && candidateOutput.equalsIgnoreCase(safeValue(existing.getOutputFormat()))) {
                return true;
            }
        }
        return false;
    }

    private String safeValue(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Process a single download (runs in worker thread)
     */
    private void processDownload(Download download) {
        try {
            // Acquire semaphore permit (blocks if at limit)
            concurrencySemaphore.acquire();
            
            try {
                // Update status
                download.setStatus(Constants.STATUS_DOWNLOADING);
                download.setStartedAt(java.time.Instant.now());
                historyService.updateDownload(download);
                
                // Notify UI of status change
                eventQueue.put(new DownloadEvent(
                    download.getId(), 
                    DownloadEvent.EventType.STATUS_CHANGE, 
                    download
                ));
                
                // Execute yt-dlp
                executeYtDlp(download);
                
            } finally {
                activeProcesses.remove(download.getId());
                pausedDownloads.remove(download.getId());
                // Release semaphore permit
                concurrencySemaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handleDownloadError(download, "Download was interrupted");
        } catch (Exception e) {
            handleDownloadError(download, "Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Execute yt-dlp process for the download
     */
    private void executeYtDlp(Download download) throws IOException, InterruptedException {
        boolean aria2Available = aria2Command != null && !aria2Command.isBlank();
        List<DownloadAttempt> attempts = new ArrayList<>();
        if (aria2Available) {
            attempts.add(new DownloadAttempt(true, false, true, "accelerated"));
        }
        attempts.add(new DownloadAttempt(false, false, true, "standard"));
        attempts.add(new DownloadAttempt(false, true, true, "safe-mode"));
        attempts.add(new DownloadAttempt(false, true, false, "safe-mode no-ipv4-force"));

        ProcessRunResult result = null;
        for (DownloadAttempt attempt : attempts) {
            result = runYtDlpProcess(download, attempt);
            if (isShutdown || download.isCancelled() || result.exitCode == 0) {
                break;
            }
        }

        if (result == null) {
            handleDownloadError(download, "No download attempt was executed.");
            return;
        }

        int exitCode = result.exitCode;

        if (isShutdown) {
            return;
        }

        if (download.isCancelled()) {
            download.setStatus(Constants.STATUS_CANCELLED);
            download.setCompletedAt(Instant.now());
            historyService.updateDownload(download);
            eventQueue.put(new DownloadEvent(download.getId(), DownloadEvent.EventType.STATUS_CHANGE, download));
            scheduleFinishedCleanup(download.getId());
            pruneFinishedTrackedDownloads();
            return;
        }
        
        if (exitCode == 0) {
            download.setStatus(Constants.STATUS_COMPLETED);
            download.setProgress(100.0);
            download.setCompletedAt(Instant.now());
            eventQueue.put(new DownloadEvent(
                download.getId(),
                DownloadEvent.EventType.COMPLETED,
                download
            ));
        } else {
            handleDownloadError(download, buildFailureMessage(result));
        }
        
        historyService.updateDownload(download);
        eventQueue.put(new DownloadEvent(
            download.getId(),
            DownloadEvent.EventType.STATUS_CHANGE,
            download
        ));
        if (download.isFinished()) {
            scheduleFinishedCleanup(download.getId());
            pruneFinishedTrackedDownloads();
        }
    }

    private ProcessRunResult runYtDlpProcess(Download download, DownloadAttempt attempt)
        throws IOException, InterruptedException {
        List<String> cmd = buildCommand(download, attempt);
        Path saveDir = Paths.get(download.getSavePath());
        Files.createDirectories(saveDir);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(saveDir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        activeProcesses.put(download.getId(), process);
        ArrayDeque<String> recentOutput = new ArrayDeque<>();

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null && !isShutdown) {
                if (download.isCancelled()) {
                    process.destroyForcibly();
                    break;
                }

                if (!line.trim().isEmpty()) {
                    processOutputLine(download, line);
                    recordRecentOutput(recentOutput, line);
                }
            }
        }

        return new ProcessRunResult(process.waitFor(), recentOutput, attempt);
    }

    /**
     * Process a single line of yt-dlp output
     */
    private void processOutputLine(Download download, String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        String trimmed = line.trim();

        if (trimmed.contains("[download]") && trimmed.contains("%")) {
            Matcher percent = PERCENT_PATTERN.matcher(trimmed);
            if (percent.find()) {
                try {
                    download.setProgress(Double.parseDouble(percent.group(1)));
                } catch (NumberFormatException ignored) {
                    return;
                }

                Matcher speed = SPEED_PATTERN.matcher(trimmed);
                if (speed.find()) {
                    download.setSpeed(speed.group(1));
                }

                Matcher size = SIZE_PATTERN.matcher(trimmed);
                if (size.find()) {
                    download.setSize(size.group(1));
                }

                Matcher eta = ETA_PATTERN.matcher(trimmed);
                if (eta.find()) {
                    download.setEta(eta.group(1));
                }

                try {
                    eventQueue.put(new DownloadEvent(download.getId(), DownloadEvent.EventType.PROGRESS, download));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return;
        }

        if (trimmed.contains("Destination:")) {
            String dest = trimmed.substring(trimmed.indexOf("Destination:") + 12).trim();
            download.setFilename(dest);
            download.setTitle(new File(dest).getName());
            try {
                eventQueue.put(new DownloadEvent(download.getId(), DownloadEvent.EventType.STATUS_CHANGE, download));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }

        if (trimmed.contains("Merger") || trimmed.contains("Merging") || trimmed.contains("ExtractAudio")) {
            try {
                eventQueue.put(new DownloadEvent(
                    download.getId(),
                    DownloadEvent.EventType.LOG,
                    "Processing media..."
                ));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Handle download error
     */
    private void handleDownloadError(Download download, String errorMessage) {
        download.setStatus(Constants.STATUS_FAILED);
        download.setErrorMessage(errorMessage);
        download.setCompletedAt(Instant.now());
        
        try {
            historyService.updateDownload(download);
            eventQueue.put(new DownloadEvent(
                download.getId(),
                DownloadEvent.EventType.ERROR,
                download
            ));
            scheduleFinishedCleanup(download.getId());
            pruneFinishedTrackedDownloads();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void scheduleFinishedCleanup(String downloadId) {
        if (isShutdown) {
            return;
        }
        try {
            cleanupExecutor.schedule(() -> {
                Download tracked = trackedDownloads.get(downloadId);
                if (tracked != null && tracked.isFinished()) {
                    trackedDownloads.remove(downloadId);
                }
            }, FINISHED_RETENTION_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // Service is shutting down.
        }
    }

    private void pruneFinishedTrackedDownloads() {
        int oversize = trackedDownloads.size() - MAX_TRACKED_DOWNLOADS;
        if (oversize <= 0) {
            return;
        }

        List<Download> finished = new ArrayList<>();
        for (Download download : trackedDownloads.values()) {
            if (download != null && download.isFinished()) {
                finished.add(download);
            }
        }

        finished.sort(Comparator.comparing(
            d -> d.getCompletedAt() != null ? d.getCompletedAt() : d.getAddedAt()
        ));

        for (int i = 0; i < oversize && i < finished.size(); i++) {
            trackedDownloads.remove(finished.get(i).getId());
        }
    }

    private List<String> buildCommand(Download download) {
        return buildCommand(download, new DownloadAttempt(
            aria2Command != null && !aria2Command.isBlank(),
            false,
            true,
            "default"
        ));
    }

    private List<String> buildCommand(Download download, DownloadAttempt attempt) {
        String format = normalizeFormat(download.getFormat());
        String outputFormat = (download.getOutputFormat() == null || download.getOutputFormat().isBlank())
            ? "mp4"
            : download.getOutputFormat();
        String formatExpr = formatExpression(format);
        boolean isAudioOnly = AUDIO_FORMATS.contains(format);

        List<String> cmd = new ArrayList<>();
        cmd.addAll(ytDlpBaseCommand);

        if (ffmpegCommand != null && !ffmpegCommand.isBlank()) {
            Path ffmpegPath = Paths.get(ffmpegCommand);
            if (Files.exists(ffmpegPath)) {
                cmd.add("--ffmpeg-location");
                cmd.add(ffmpegPath.getParent() != null ? ffmpegPath.getParent().toString() : ffmpegPath.toString());
            }
        }

        if (attempt.useAria2) {
            cmd.add("--downloader");
            cmd.add(aria2Command);
            cmd.add("--downloader-args");
            cmd.add("aria2c:-x 16 -s 16 -k 1M --file-allocation=none --summary-interval=1");
        }

        if (isAudioOnly) {
            cmd.add("-f");
            cmd.add(formatExpr);
            cmd.add("-x");
            cmd.add("--audio-format");
            cmd.add("mp3");
            cmd.add("--audio-quality");
            cmd.add("0");
        } else {
            cmd.add("-f");
            cmd.add(formatExpr);
            cmd.add("--merge-output-format");
            cmd.add(outputFormat);
        }

        cmd.add("-o");
        cmd.add(Paths.get(download.getSavePath(), "%(title)s.%(ext)s").toString());
        cmd.add("--newline");
        cmd.add("--no-warnings");
        cmd.add("--continue");
        cmd.add("--encoding");
        cmd.add("utf-8");
        if (attempt.forceIpv4) {
            cmd.add("--force-ipv4");
        }
        cmd.add("--retries");
        cmd.add("5");
        cmd.add("--socket-timeout");
        cmd.add("30");

        if (download.isNoCertCheck()) {
            cmd.add("--no-check-certificates");
        }

        if (!attempt.safeMode
            && download.isUseCookies()
            && download.getCookieBrowser() != null
            && !download.getCookieBrowser().isBlank()) {
            cmd.add("--cookies-from-browser");
            cmd.add(download.getCookieBrowser().toLowerCase());
        }

        if (!attempt.safeMode && download.isWriteSubs()) {
            cmd.add("--write-subs");
            cmd.add("--write-auto-subs");
            cmd.add("--sub-langs");
            cmd.add(download.getSubLangs() == null || download.getSubLangs().isBlank() ? "en" : download.getSubLangs());
            if (!isAudioOnly) {
                cmd.add("--embed-subs");
            }
        }

        cmd.add(download.isPlaylist() ? "--yes-playlist" : "--no-playlist");

        if (download.getSpeedLimit() != null && !download.getSpeedLimit().isBlank()) {
            cmd.add("--limit-rate");
            cmd.add(download.getSpeedLimit());
        }

        if (download.getProxy() != null && !download.getProxy().isBlank()) {
            cmd.add("--proxy");
            cmd.add(download.getProxy());
        }

        if (!attempt.safeMode) {
            cmd.add("--embed-thumbnail");
            cmd.add("--embed-metadata");
        }
        cmd.add(download.getUrl());

        return cmd;
    }

    private List<String> resolveYtDlpBaseCommand() {
        String localYtDlp = resolveLocalYtDlpExecutable();
        if (localYtDlp != null) {
            return Arrays.asList(localYtDlp);
        }
        if (isCommandAvailable("python", "-m", "yt_dlp", "--version")) {
            return Arrays.asList("python", "-m", "yt_dlp");
        }
        if (isCommandAvailable("yt-dlp", "--version")) {
            return Arrays.asList("yt-dlp");
        }
        // Keep historical invocation style as final fallback.
        return Arrays.asList("python", "-m", "yt_dlp");
    }

    private String resolveFfmpegCommand() {
        String localFfmpeg = resolveLocalFfmpegExecutable();
        if (localFfmpeg != null) {
            return localFfmpeg;
        }
        return "ffmpeg";
    }

    private String resolveAria2Command() {
        String localAria2 = resolveLocalAria2Executable();
        if (localAria2 != null) {
            return localAria2;
        }
        if (isCommandAvailable("aria2c", "--version")) {
            return "aria2c";
        }
        return null;
    }

    private String resolveLocalYtDlpExecutable() {
        boolean win = isWindows();
        String[] candidates = win
            ? new String[]{"tools/yt-dlp.exe", "tools/bin/yt-dlp.exe", "yt-dlp.exe"}
            : new String[]{"tools/yt-dlp", "tools/bin/yt-dlp", "yt-dlp"};
        return resolveLocalExecutable(candidates);
    }

    private String resolveLocalFfmpegExecutable() {
        boolean win = isWindows();
        String[] candidates = win
            ? new String[]{"tools/ffmpeg/bin/ffmpeg.exe", "tools/ffmpeg.exe", "ffmpeg.exe"}
            : new String[]{"tools/ffmpeg/bin/ffmpeg", "tools/ffmpeg", "ffmpeg"};
        return resolveLocalExecutable(candidates);
    }

    private String resolveLocalAria2Executable() {
        boolean win = isWindows();
        String[] candidates = win
            ? new String[]{"tools/aria2c.exe", "tools/bin/aria2c.exe", "aria2c.exe"}
            : new String[]{"tools/aria2c", "tools/bin/aria2c", "aria2c"};
        return resolveLocalExecutable(candidates);
    }

    private String resolveLocalExecutable(String[] candidates) {
        for (Path root : getCandidateRoots()) {
            for (String candidate : candidates) {
                Path path = root.resolve(candidate).normalize();
                if (Files.exists(path) && Files.isRegularFile(path)) {
                    return path.toString();
                }
            }
        }
        return null;
    }

    private List<Path> getCandidateRoots() {
        java.util.LinkedHashSet<Path> roots = new java.util.LinkedHashSet<>();
        roots.add(Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize());
        roots.add(Paths.get("").toAbsolutePath().normalize());

        try {
            Path classLocation = Paths.get(DownloadService.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath()
                .normalize();
            roots.add(classLocation);
            Path parent = classLocation;
            for (int i = 0; i < 5 && parent != null; i++) {
                parent = parent.getParent();
                if (parent != null) {
                    roots.add(parent);
                }
            }
        } catch (URISyntaxException | NullPointerException ignored) {
            // Keep best-effort root list.
        }

        return new ArrayList<>(roots);
    }

    private void recordRecentOutput(ArrayDeque<String> lines, String line) {
        if (line == null) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        lines.addLast(trimmed);
        while (lines.size() > MAX_FAILURE_LINES) {
            lines.removeFirst();
        }
    }

    private String buildFailureMessage(ProcessRunResult result) {
        String reason = extractFailureReason(result.recentOutput);
        if (reason == null || reason.isBlank()) {
            return "yt-dlp exited with code: " + result.exitCode
                + " (attempt: " + result.attempt.label + ")"
                + tailOutputSuffix(result.recentOutput);
        }
        return "yt-dlp exited with code " + result.exitCode
            + " (attempt: " + result.attempt.label + "): " + reason;
    }

    private String tailOutputSuffix(ArrayDeque<String> recentOutput) {
        if (recentOutput == null || recentOutput.isEmpty()) {
            return "";
        }
        String last = sanitizeFailureLine(new ArrayList<>(recentOutput).get(recentOutput.size() - 1));
        return last.isBlank() ? "" : " | last output: " + last;
    }

    private String extractFailureReason(ArrayDeque<String> recentOutput) {
        if (recentOutput == null || recentOutput.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>(recentOutput);
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            int idx = line.toLowerCase().indexOf("error:");
            if (idx >= 0) {
                return sanitizeFailureLine(line.substring(idx + 6));
            }
        }
        return sanitizeFailureLine(lines.get(lines.size() - 1));
    }

    private String sanitizeFailureLine(String line) {
        if (line == null) {
            return "";
        }
        String compact = line.replaceAll("\\u001B\\[[;\\d]*m", "").trim();
        if (compact.length() <= 220) {
            return compact;
        }
        return compact.substring(0, 217) + "...";
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private String normalizeFormat(String format) {
        if (format == null || format.isBlank()) {
            return "best";
        }

        String lower = format.toLowerCase();
        if (lower.contains("1080")) return "1080";
        if (lower.contains("720")) return "720";
        if (lower.contains("480")) return "480";
        if (lower.contains("audio")) return "audio";
        if (lower.contains("best")) return "best";
        return "best";
    }

    private String formatExpression(String format) {
        switch (format) {
            case "1080":
                return "bestvideo[height<=1080]+bestaudio/best[height<=1080]";
            case "720":
                return "bestvideo[height<=720]+bestaudio/best[height<=720]";
            case "480":
                return "bestvideo[height<=480]+bestaudio/best[height<=480]";
            case "audio":
                return "bestaudio/best";
            case "best":
            default:
                return "bestvideo+bestaudio/best";
        }
    }

    public void cancelDownload(String downloadId) {
        pausedDownloads.remove(downloadId);
        Download tracked = trackedDownloads.get(downloadId);
        if (tracked != null) {
            tracked.setCancelled(true);
            tracked.setStatus(Constants.STATUS_CANCELLED);
            tracked.setCompletedAt(Instant.now());
            historyService.updateDownload(tracked);
            try {
                eventQueue.put(new DownloadEvent(downloadId, DownloadEvent.EventType.STATUS_CHANGE, tracked));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduleFinishedCleanup(downloadId);
            pruneFinishedTrackedDownloads();
        }

        Process process = activeProcesses.remove(downloadId);
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    public boolean pauseDownload(String downloadId) {
        Download tracked = trackedDownloads.get(downloadId);
        Process process = activeProcesses.get(downloadId);

        if (tracked == null || process == null || !process.isAlive()) {
            return false;
        }
        if (!Constants.STATUS_DOWNLOADING.equals(tracked.getStatus())) {
            return false;
        }

        boolean paused = controlProcessExecution(process, true);
        if (!paused) {
            return false;
        }

        pausedDownloads.add(downloadId);
        tracked.setStatus(Constants.STATUS_PAUSED);
        historyService.updateDownload(tracked);
        try {
            eventQueue.put(new DownloadEvent(downloadId, DownloadEvent.EventType.STATUS_CHANGE, tracked));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    public boolean resumeDownload(String downloadId) {
        Download tracked = trackedDownloads.get(downloadId);
        Process process = activeProcesses.get(downloadId);

        if (tracked == null || process == null || !process.isAlive()) {
            return false;
        }
        if (!Constants.STATUS_PAUSED.equals(tracked.getStatus())) {
            return false;
        }

        boolean resumed = controlProcessExecution(process, false);
        if (!resumed) {
            return false;
        }

        pausedDownloads.remove(downloadId);
        tracked.setStatus(Constants.STATUS_DOWNLOADING);
        historyService.updateDownload(tracked);
        try {
            eventQueue.put(new DownloadEvent(downloadId, DownloadEvent.EventType.STATUS_CHANGE, tracked));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    private boolean controlProcessExecution(Process process, boolean pause) {
        long pid = process.pid();
        String os = System.getProperty("os.name", "").toLowerCase();

        try {
            Process control;
            if (os.contains("win")) {
                if (windowsShell == null) {
                    return false;
                }
                String command = (pause ? "Suspend-Process -Id " : "Resume-Process -Id ") + pid;
                control = new ProcessBuilder(windowsShell, "-NoProfile", "-Command", command)
                    .redirectErrorStream(true)
                    .start();
            } else {
                String signal = pause ? "-STOP" : "-CONT";
                control = new ProcessBuilder("kill", signal, String.valueOf(pid))
                    .redirectErrorStream(true)
                    .start();
            }

            boolean finished = control.waitFor(5, TimeUnit.SECONDS);
            return finished && control.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String detectWindowsShell() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            return null;
        }
        if (isCommandAvailable("pwsh", "-NoProfile", "-Command", "$PSVersionTable.PSVersion")) {
            return "pwsh";
        }
        if (isCommandAvailable("powershell", "-NoProfile", "-Command", "$PSVersionTable.PSVersion")) {
            return "powershell";
        }
        return null;
    }

    public int getActiveDownloadCount() {
        return activeProcesses.size();
    }

    public int getMaxConcurrentDownloads() {
        return concurrencySemaphore.availablePermits() + activeProcesses.size();
    }

    public synchronized void setMaxConcurrentDownloads(int newMaxConcurrent) {
        int normalized = Math.max(1, newMaxConcurrent);
        int active = activeProcesses.size();
        int currentMax = getMaxConcurrentDownloads();

        if (normalized == currentMax) {
            return;
        }

        int targetAvailable = Math.max(0, normalized - active);
        int currentAvailable = concurrencySemaphore.availablePermits();

        if (targetAvailable > currentAvailable) {
            concurrencySemaphore.release(targetAvailable - currentAvailable);
        } else if (targetAvailable < currentAvailable) {
            concurrencySemaphore.acquireUninterruptibly(currentAvailable - targetAvailable);
        }
    }

    public boolean isPauseResumeSupported() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return windowsShell != null;
        }
        return true;
    }

    public Set<String> getActiveDownloadIds() {
        return new java.util.LinkedHashSet<>(activeProcesses.keySet());
    }

    /**
     * Get download events queue for UI consumption
     */
    public BlockingQueue<DownloadEvent> getEventQueue() {
        return eventQueue;
    }

    /**
     * Basic runtime tool diagnostics for startup checks.
     */
    public Map<String, Boolean> getToolAvailability() {
        Map<String, Boolean> tools = new LinkedHashMap<>();
        boolean pythonAvailable = isCommandAvailable("python", "--version");
        boolean ytDlpAsModule = isCommandAvailable("python", "-m", "yt_dlp", "--version");
        boolean ytDlpStandalone = isCommandAvailable("yt-dlp", "--version");
        boolean ytDlpLocal = ytDlpBaseCommand.size() == 1 && isCommandAvailable(ytDlpBaseCommand.get(0), "--version");
        boolean ytDlpAvailable = ytDlpAsModule || ytDlpStandalone;
        ytDlpAvailable = ytDlpAvailable || ytDlpLocal;

        // Python is optional when yt-dlp standalone executable is installed.
        tools.put("python", pythonAvailable || ytDlpStandalone || ytDlpLocal);
        tools.put("yt-dlp", ytDlpAvailable);
        tools.put("ffmpeg", isCommandAvailable(ffmpegCommand, "-version"));
        return tools;
    }

    public Map<String, String> getToolDetails() {
        Map<String, String> tools = new LinkedHashMap<>();
        String pythonSummary = getCommandSummary("python", "--version");
        String ytDlpModuleSummary = getCommandSummary("python", "-m", "yt_dlp", "--version");
        String ytDlpStandaloneSummary = getCommandSummary("yt-dlp", "--version");
        String ytDlpLocalSummary = ytDlpBaseCommand.isEmpty()
            ? "Not available"
            : getCommandSummary(ytDlpBaseCommand.get(0), "--version");

        String ytDlpSummary = ytDlpModuleSummary;
        if (ytDlpSummary.toLowerCase().startsWith("not available")) {
            ytDlpSummary = ytDlpStandaloneSummary;
        }
        if (ytDlpSummary.toLowerCase().startsWith("not available")) {
            ytDlpSummary = ytDlpLocalSummary;
        }

        if (pythonSummary.toLowerCase().startsWith("not available")
            && (!ytDlpStandaloneSummary.toLowerCase().startsWith("not available")
                || !ytDlpLocalSummary.toLowerCase().startsWith("not available"))) {
            pythonSummary = "Optional (not required when yt-dlp executable is installed)";
        }

        tools.put("python", pythonSummary);
        tools.put("yt-dlp", ytDlpSummary);
        tools.put("ffmpeg", getCommandSummary(ffmpegCommand, "-version"));
        tools.put("aria2c", aria2Command == null ? "Not available (optional)" : getCommandSummary(aria2Command, "--version"));
        return tools;
    }

    private static final class ProcessRunResult {
        private final int exitCode;
        private final ArrayDeque<String> recentOutput;
        private final DownloadAttempt attempt;

        private ProcessRunResult(int exitCode, ArrayDeque<String> recentOutput, DownloadAttempt attempt) {
            this.exitCode = exitCode;
            this.recentOutput = recentOutput;
            this.attempt = attempt;
        }
    }

    private static final class DownloadAttempt {
        private final boolean useAria2;
        private final boolean safeMode;
        private final boolean forceIpv4;
        private final String label;

        private DownloadAttempt(boolean useAria2, boolean safeMode, boolean forceIpv4, String label) {
            this.useAria2 = useAria2;
            this.safeMode = safeMode;
            this.forceIpv4 = forceIpv4;
            this.label = label;
        }
    }

    private boolean isCommandAvailable(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(8, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String getCommandSummary(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(8, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return "Not available (timeout)";
            }

            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String firstLine = reader.readLine();
                if (p.exitValue() == 0 && firstLine != null && !firstLine.isBlank()) {
                    return firstLine.trim();
                }
            }
            return "Not available";
        } catch (Exception e) {
            return "Not available";
        }
    }

    /**
     * Shutdown the service
     */
    public void shutdown() {
        isShutdown = true;

        // Mark unfinished downloads as cancelled before stopping workers.
        for (Download download : trackedDownloads.values()) {
            if (download != null && !download.isFinished()) {
                download.setCancelled(true);
                download.setStatus(Constants.STATUS_CANCELLED);
                download.setCompletedAt(Instant.now());
                historyService.updateDownload(download);
                try {
                    eventQueue.put(new DownloadEvent(
                        download.getId(),
                        DownloadEvent.EventType.STATUS_CHANGE,
                        download
                    ));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // Attempt graceful process termination before forcing stop.
        for (Process process : activeProcesses.values()) {
            if (process == null || !process.isAlive()) {
                continue;
            }
            try {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS) && process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // Best-effort shutdown path.
            }
        }

        activeProcesses.clear();
        pausedDownloads.clear();
        cleanupExecutor.shutdownNow();

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.err.println("DownloadService did not shutdown gracefully");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executorService.shutdownNow();
        }
    }
}