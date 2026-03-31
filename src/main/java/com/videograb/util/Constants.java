package com.videograb.util;

/**
 * Application constants
 */
public class Constants {
    // Configuration keys
    public static final String CONFIG_DOWNLOAD_PATH = "savePath";
    public static final String CONFIG_CONCURRENT_DOWNLOADS = "maxConcurrent";
    public static final String CONFIG_THEME = "theme";
    public static final String CONFIG_AUTO_OPEN_FOLDER = "autoOpenFolder";
    
    // Default values
    public static final int DEFAULT_CONCURRENT_DOWNLOADS = 3;
    public static final String DEFAULT_THEME = "light";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    
    // yt-dlp constants
    public static final String YT_DLP_COMMAND = "yt-dlp";
    
    // UI constants
    public static final int PROGRESS_BAR_WIDTH = 200;
    public static final int PROGRESS_BAR_HEIGHT = 20;
    
    // File constants
    public static final String CONFIG_FILE = "config.json";
    public static final String HISTORY_FILE = "history.json";
    public static final int MAX_HISTORY_ENTRIES = 100;
    
    // Status constants
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_DOWNLOADING = "DOWNLOADING";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
}