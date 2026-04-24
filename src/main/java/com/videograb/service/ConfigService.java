package com.videograb.service;

import com.videograb.util.AppPaths;
import com.videograb.util.Constants;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service for loading and saving application configuration
 */
public class ConfigService {
    private static final String CONFIG_FILE = "config.json";
    private final Path configPath;
    private final ObjectMapper objectMapper;
    private Map<String, Object> properties;

    public ConfigService() {
        this.configPath = AppPaths.dataFile(CONFIG_FILE);
        this.objectMapper = new ObjectMapper();
        this.properties = new LinkedHashMap<>();
        loadConfig();
    }

    /**
     * Load configuration from file
     */
    public void loadConfig() {
        if (Files.exists(configPath)) {
            try (InputStream input = new FileInputStream(configPath.toFile())) {
                Map<String, Object> loaded = objectMapper.readValue(input, new TypeReference<Map<String, Object>>() {});
                properties.clear();
                if (loaded != null) {
                    properties.putAll(loaded);
                }
                normalizeLegacyKeys();
            } catch (IOException e) {
                System.err.println("Error loading config: " + e.getMessage());
                // Initialize with defaults
                initializeDefaults();
            }
        } else {
            initializeDefaults();
            saveConfig(); // Create default config file
        }
    }

    /**
     * Initialize default configuration values
     */
    private void initializeDefaults() {
        properties.clear();
        properties.put(Constants.CONFIG_DOWNLOAD_PATH, System.getProperty("user.home") + "/Downloads");
        properties.put(Constants.CONFIG_CONCURRENT_DOWNLOADS, Constants.DEFAULT_CONCURRENT_DOWNLOADS);
        properties.put(Constants.CONFIG_THEME, Constants.DEFAULT_THEME);
        properties.put(Constants.CONFIG_AUTO_OPEN_FOLDER, true);
    }

    private void normalizeLegacyKeys() {
        // Keep compatibility with any earlier properties-style keys.
        migrateKey("download.path", Constants.CONFIG_DOWNLOAD_PATH);
        migrateKey("concurrent.downloads", Constants.CONFIG_CONCURRENT_DOWNLOADS);
        migrateKey("auto.open.folder", Constants.CONFIG_AUTO_OPEN_FOLDER);
    }

    private void migrateKey(String oldKey, String newKey) {
        if (!properties.containsKey(newKey) && properties.containsKey(oldKey)) {
            properties.put(newKey, properties.get(oldKey));
        }
    }

    /**
     * Save configuration to file
     */
    public void saveConfig() {
        try {
            Path parent = configPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (OutputStream output = new FileOutputStream(configPath.toFile())) {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(output, properties);
            }
        } catch (IOException e) {
            System.err.println("Error saving config: " + e.getMessage());
        }
    }

    /**
     * Get a configuration value as String
     */
    public String getString(String key, String defaultValue) {
        Object value = properties.get(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }

    /**
     * Get a configuration value as Integer
     */
    public int getInt(String key, int defaultValue) {
        Object raw = properties.get(key);
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        String value = raw != null ? String.valueOf(raw) : null;
        try {
            return value != null ? Integer.parseInt(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Get a configuration value as Boolean
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        Object raw = properties.get(key);
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        String value = raw != null ? String.valueOf(raw) : null;
        if (value == null) {
            return defaultValue;
        }
        return value.equalsIgnoreCase("true");
    }

    /**
     * Set a configuration value
     */
    public void set(String key, String value) {
        set(key, (Object) value);
    }

    /**
     * Set a configuration value with typed payload (String, Boolean, Number)
     */
    public void set(String key, Object value) {
        properties.put(key, value);
        saveConfig();
    }

    /**
     * Get all configuration properties
     */
    public Map<String, Object> getProperties() {
        return new LinkedHashMap<>(properties); // Return copy
    }
}
