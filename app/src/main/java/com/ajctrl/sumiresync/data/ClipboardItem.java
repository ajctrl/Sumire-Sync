package com.ajctrl.sumiresync.data;

import android.net.Uri;

public final class ClipboardItem {
    public final long id;
    public final String itemType;
    public final long createdAt;
    public final boolean pinned;
    public final String preview;
    public final Uri contentUri;

    public ClipboardItem(long id, String itemType, long createdAt, boolean pinned,
                         String preview, Uri contentUri) {
        this.id = id;
        this.itemType = itemType;
        this.createdAt = createdAt;
        this.pinned = pinned;
        this.preview = preview;
        this.contentUri = contentUri;
    }
}
