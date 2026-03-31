package com.videograb.service;

import com.videograb.model.Download;
import com.videograb.util.Constants;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for managing download history
 */
public class HistoryService {
    private static final String HISTORY_FILE = Constants.HISTORY_FILE;
    private final ObjectMapper objectMapper;
    private final Object historyLock;
    private List<Download> history;

    public HistoryService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.historyLock = new Object();
        this.history = new ArrayList<>();
        loadHistory();
    }

    /**
     * Load history from file
     */
    public void loadHistory() {
        synchronized (historyLock) {
            Path historyPath = Paths.get(HISTORY_FILE);
            if (Files.exists(historyPath)) {
                try (InputStream input = new FileInputStream(historyPath.toFile())) {
                    JsonNode root = objectMapper.readTree(input);
                    history = parseHistory(root);
                } catch (IOException e) {
                    System.err.println("Error loading history: " + e.getMessage());
                    history = new ArrayList<>();
                }
            } else {
                history = new ArrayList<>();
            }
        }
    }

    private List<Download> parseHistory(JsonNode root) {
        List<Download> parsed = new ArrayList<>();
        if (root == null || !root.isArray()) {
            return parsed;
        }

        for (JsonNode node : root) {
            try {
                if (node.has("time") && !node.has("addedAt")) {
                    parsed.add(parseLegacyHistoryEntry(node));
                } else {
                    Download download = objectMapper.treeToValue(node, Download.class);
                    if (download != null) {
                        parsed.add(download);
                    }
                }
            } catch (Exception ignored) {
                // Skip malformed entries while preserving remaining history.
            }
        }
        return parsed;
    }

    private Download parseLegacyHistoryEntry(JsonNode node) {
        Download download = new Download();
        download.setUrl(node.path("url").asText(null));
        download.setTitle(node.path("title").asText(null));
        download.setStatus(node.path("status").asText("FAILED"));

        String time = node.path("time").asText(null);
        download.setAddedAt(parseLegacyTime(time));
        download.setCompletedAt(download.getAddedAt());
        return download;
    }

    private Instant parseLegacyTime(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }

        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // Legacy format uses local datetime without zone.
        }

        try {
            LocalDateTime local = LocalDateTime.parse(value);
            return local.atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            return Instant.now();
        }
    }

    /**
     * Save history to file
     */
    public void saveHistory() {
        synchronized (historyLock) {
            Path historyPath = Paths.get(HISTORY_FILE);
            try {
                Path parent = historyPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                try (OutputStream output = new FileOutputStream(historyPath.toFile())) {
                    objectMapper.writerWithDefaultPrettyPrinter().writeValue(output, history);
                }
            } catch (IOException e) {
                System.err.println("Error saving history: " + e.getMessage());
            }
        }
    }

    /**
     * Add a download to history
     */
    public void addDownload(Download download) {
        synchronized (historyLock) {
            history.add(download);
            trimHistory();
            saveHistory();
        }
    }

    /**
     * Update a download in history
     */
    public void updateDownload(Download download) {
        if (download == null) {
            return;
        }
        synchronized (historyLock) {
            String targetId = download.getId();
            history.removeIf(d -> d != null && targetId != null && targetId.equals(d.getId()));
            history.add(download);
            trimHistory();
            saveHistory();
        }
    }

    private void trimHistory() {
        while (history.size() > Constants.MAX_HISTORY_ENTRIES) {
            history.remove(0);
        }
    }

    /**
     * Remove a download from history
     */
    public void removeDownload(String downloadId) {
        if (downloadId == null || downloadId.isBlank()) {
            return;
        }
        synchronized (historyLock) {
            history.removeIf(d -> d != null && downloadId.equals(d.getId()));
            saveHistory();
        }
    }

    /**
     * Get all history items
     */
    public List<Download> getHistory() {
        synchronized (historyLock) {
            return new ArrayList<>(history); // Return copy
        }
    }

    /**
     * Clear all history
     */
    public void clearHistory() {
        synchronized (historyLock) {
            history.clear();
            saveHistory();
        }
    }

    /**
     * Get history count
     */
    public int getHistoryCount() {
        synchronized (historyLock) {
            return history.size();
        }
    }
}