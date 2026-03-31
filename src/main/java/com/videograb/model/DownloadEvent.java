package com.videograb.model;

/**
 * Event class for download progress updates
 * Used for communication between worker threads and UI
 */
public class DownloadEvent {
    public enum EventType {
        PROGRESS,
        STATUS_CHANGE,
        COMPLETED,
        ERROR,
        LOG
    }

    private final String downloadId;
    private final EventType eventType;
    private final Object data;

    public DownloadEvent(String downloadId, EventType eventType, Object data) {
        this.downloadId = downloadId;
        this.eventType = eventType;
        this.data = data;
    }

    public String getDownloadId() {
        return downloadId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public Object getData() {
        return data;
    }

    @Override
    public String toString() {
        return "DownloadEvent{" +
                "downloadId='" + downloadId + '\'' +
                ", eventType=" + eventType +
                ", data=" + data +
                '}';
    }
}