package com.ajctrl.sumiresync.upload;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.UnknownHostException;

import org.junit.Test;

public final class ErrorDetailsTest {
    @Test public void describeIncludesTheExceptionTypeAndRootCause() {
        IOException error = new IOException(
                "Nextcloud接続失敗", new UnknownHostException("cloud.example.com"));

        assertEquals(
                "IOException: Nextcloud接続失敗\n"
                        + "原因: UnknownHostException: cloud.example.com",
                ErrorDetails.describe(error));
    }
}
