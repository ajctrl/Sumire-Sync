package com.ajctrl.sumiresync.data;

public final class SyncStatus {
    public final int apiVersion;
    public final String databaseInstanceId;
    public final int clipboardGeneration;
    public final long currentSequence;

    public SyncStatus(int apiVersion, String databaseInstanceId, int clipboardGeneration,
                      long currentSequence) {
        this.apiVersion = apiVersion;
        this.databaseInstanceId = databaseInstanceId;
        this.clipboardGeneration = clipboardGeneration;
        this.currentSequence = currentSequence;
    }
}
