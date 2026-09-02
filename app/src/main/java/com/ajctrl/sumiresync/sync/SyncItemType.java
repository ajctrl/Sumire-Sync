package com.ajctrl.sumiresync.sync;

public enum SyncItemType {
    TEXT,
    IMAGE;

    public static SyncItemType fromContractValue(String value) {
        if ("TEXT".equals(value)) return TEXT;
        if ("IMAGE".equals(value)) return IMAGE;
        throw new IllegalStateException("Unsupported clipboard itemType: " + value);
    }
}
