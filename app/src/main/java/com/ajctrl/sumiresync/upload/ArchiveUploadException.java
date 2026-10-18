package com.ajctrl.sumiresync.upload;

import java.io.IOException;

/** Failure confined to one remote archive; other files can still be uploaded. */
public final class ArchiveUploadException extends IOException {
    public ArchiveUploadException(String message) { super(message); }
}
