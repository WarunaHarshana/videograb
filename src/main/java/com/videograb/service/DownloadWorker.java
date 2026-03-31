package com.videograb.service;

import com.videograb.model.Download;

/**
 * Compatibility wrapper around DownloadService.
 *
 * The service owns queueing, concurrency limits and yt-dlp execution.
 * This class remains to preserve the planned architecture shape while the
 * implementation consolidates in DownloadService.
 */
public class DownloadWorker implements Runnable {

    private final Download download;
    private final DownloadService downloadService;

    public DownloadWorker(Download download, DownloadService downloadService) {
        this.download = download;
        this.downloadService = downloadService;
    }

    @Override
    public void run() {
        downloadService.addDownload(download);
    }
}