package com.videograb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Objects;

/**
 * Data model representing a download item
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Download {
    private String id;
    private String url;
    private String title;
    private String status; // QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
    private double progress; // 0.0 to 100.0
    private String speed; // e.g., "1.2 MB/s"
    private String eta; // e.g., "02:30"
    private String size; // e.g., "125.4 MB"
    private String filename;
    private String format;
    private String savePath;
    private boolean useCookies;
    private String cookieBrowser;
    private boolean playlist;
    private boolean writeSubs;
    private String subLangs;
    private boolean noCertCheck;
    private String speedLimit;
    private String outputFormat;
    private String proxy;
    private volatile boolean cancelled;
    private Instant addedAt;
    private Instant startedAt;
    private Instant completedAt;
    private String errorMessage;

    public Download() {
        this.id = java.util.UUID.randomUUID().toString();
        this.addedAt = Instant.now();
        this.status = "QUEUED";
        this.progress = 0.0;
        this.format = "best";
        this.outputFormat = "mp4";
        this.cookieBrowser = "chrome";
        this.subLangs = "en";
        this.savePath = System.getProperty("user.home") + "/Downloads";
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) {
        if (id != null && !id.isBlank()) {
            this.id = id;
        }
    }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; }
    public void setStatus(String status) {
        if (status == null) {
            this.status = null;
            return;
        }
        if ("success".equalsIgnoreCase(status)) {
            this.status = "COMPLETED";
            return;
        }
        if ("failed".equalsIgnoreCase(status)) {
            this.status = "FAILED";
            return;
        }
        this.status = status.toUpperCase();
    }
    public double getProgress() { return progress; }
    public void setProgress(double progress) { this.progress = Math.max(0.0, Math.min(100.0, progress)); }
    public String getSpeed() { return speed; }
    public void setSpeed(String speed) { this.speed = speed; }
    public String getEta() { return eta; }
    public void setEta(String eta) { this.eta = eta; }
    public String getSize() { return size; }
    public void setSize(String size) { this.size = size; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public String getSavePath() { return savePath; }
    public void setSavePath(String savePath) { this.savePath = savePath; }
    public boolean isUseCookies() { return useCookies; }
    public void setUseCookies(boolean useCookies) { this.useCookies = useCookies; }
    public String getCookieBrowser() { return cookieBrowser; }
    public void setCookieBrowser(String cookieBrowser) { this.cookieBrowser = cookieBrowser; }
    public boolean isPlaylist() { return playlist; }
    public void setPlaylist(boolean playlist) { this.playlist = playlist; }
    public boolean isWriteSubs() { return writeSubs; }
    public void setWriteSubs(boolean writeSubs) { this.writeSubs = writeSubs; }
    public String getSubLangs() { return subLangs; }
    public void setSubLangs(String subLangs) { this.subLangs = subLangs; }
    public boolean isNoCertCheck() { return noCertCheck; }
    public void setNoCertCheck(boolean noCertCheck) { this.noCertCheck = noCertCheck; }
    public String getSpeedLimit() { return speedLimit; }
    public void setSpeedLimit(String speedLimit) { this.speedLimit = speedLimit; }
    public String getOutputFormat() { return outputFormat; }
    public void setOutputFormat(String outputFormat) { this.outputFormat = outputFormat; }
    public String getProxy() { return proxy; }
    public void setProxy(String proxy) { this.proxy = proxy; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    public Instant getAddedAt() { return addedAt; }
    public void setAddedAt(Instant addedAt) { this.addedAt = addedAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public boolean isFinished() {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Download download = (Download) o;
        return Objects.equals(id, download.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Download{" +
                "id='" + id + '\'' +
                ", url='" + url + '\'' +
                ", title='" + title + '\'' +
                ", status='" + status + '\'' +
                ", progress=" + progress +
                '}';
    }
}