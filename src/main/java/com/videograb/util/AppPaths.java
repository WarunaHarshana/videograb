package com.videograb.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Resolves writable application data paths.
 */
public final class AppPaths {
    private static final String APP_DIR_NAME = "VideoGrab";
    private static final String PORTABLE_PROPERTY = "videograb.portable";

    private AppPaths() {
    }

    public static Path dataFile(String fileName) {
        Path target = dataDirectory().resolve(fileName).toAbsolutePath().normalize();
        migrateLegacyFile(target, fileName);
        return target;
    }

    public static Path dataDirectory() {
        if (Boolean.getBoolean(PORTABLE_PROPERTY)) {
            return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        }

        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Paths.get(appData, APP_DIR_NAME).toAbsolutePath().normalize();
        }

        return Paths.get(System.getProperty("user.home", "."), "." + APP_DIR_NAME.toLowerCase())
            .toAbsolutePath()
            .normalize();
    }

    private static void migrateLegacyFile(Path target, String fileName) {
        if (Files.exists(target)) {
            return;
        }

        Path legacy = Paths.get(fileName).toAbsolutePath().normalize();
        if (legacy.equals(target) || !Files.exists(legacy) || !Files.isRegularFile(legacy)) {
            return;
        }

        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(legacy, target, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException ignored) {
            // Best effort migration; services will create defaults if needed.
        }
    }
}
